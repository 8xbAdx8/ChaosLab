package com.chaoslab.engine.infrastructure.blade;

import com.chaoslab.engine.application.model.*;
import com.chaoslab.engine.application.port.ChaosEngine;
import com.chaoslab.execution.application.*;
import com.chaoslab.execution.application.port.ExperimentExecutionRepository;
import com.chaoslab.execution.domain.*;
import com.chaoslab.experiment.application.port.ExperimentRepository;
import com.chaoslab.experiment.domain.Experiment;
import com.chaoslab.safety.application.model.*;
import com.chaoslab.safety.application.port.TargetIdentityVerifier;
import com.chaoslab.target.application.port.TargetRepository;
import com.chaoslab.target.domain.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Real H2 commits and real adapter, but ALL external process/identity operations are mocks. */
@SpringBootTest(properties = {"chaoslab.engine=blade", "chaoslab.execution.max-active-executions=1000",
        "spring.datasource.url=jdbc:h2:mem:blade-wiring;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "chaoslab.blade.node-id=node-1", "chaoslab.blade.state-id=state-1", "chaoslab.blade.tool-version=api3"})
class ChaosBladeWiringTests {
    @DynamicPropertySource static void configuration(DynamicPropertyRegistry r) {
        r.add("chaoslab.blade.executable", () -> Path.of("never-execute-blade").toAbsolutePath().toString());
        r.add("chaoslab.blade.state-directory", () -> Path.of(".").toAbsolutePath().normalize().toString());
        r.add("chaoslab.blade.cli-sha256", () -> "c".repeat(64));
    }
    @Autowired ExperimentExecutionApplicationService service;
    @Autowired ChaosEngine engine;
    @Autowired TargetRepository targets;
    @Autowired ExperimentRepository experiments;
    @Autowired JdbcTemplate jdbc;
    @Autowired org.springframework.context.ApplicationContext context;
    @MockitoSpyBean JdbcBladeExecutionJournal journal;
    @MockitoSpyBean ExperimentExecutionRepository executions;
    @MockitoBean BladeProcessChannel channel;
    @MockitoBean BladeLocalIdentityVerifier local;
    @MockitoBean TargetIdentityVerifier verifier;
    private Experiment ready;
    private VerifiedDockerTarget identity;
    private String uid;

    @BeforeEach void prepare() {
        uid = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        var target = targets.save(Target.register(UUID.randomUUID(), "order-service", TargetType.DOCKER_CONTAINER, TargetEnvironment.CHAOS_LAB));
        identity = new VerifiedDockerTarget(target.getId(), UUID.randomUUID().toString().replace("-", "").repeat(2),
                "sha256:"+"b".repeat(64), "order-service");
        ready = ready(target.getId());
        when(verifier.verify(any())).thenReturn(TargetIdentityVerification.verified(identity));
        when(local.verifyBeforeCreate(any(), any())).thenReturn(BladeLocalIdentityVerifier.Result.LOCAL_IDENTITY_MATCHED_NEEDS_TARGET_CHECK);
        when(local.verify(any())).thenReturn(BladeLocalIdentityVerifier.Result.LOCAL_IDENTITY_MATCHED_NEEDS_TARGET_CHECK);
        when(channel.create(any(), any())).thenAnswer(call -> {
            DockerCpuCommandPlan plan = call.getArgument(0);
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            // Independent connection/thread sees committed intent and PREPARING before dispatch.
            committed(() -> {
                assertThat(jdbc.queryForObject("SELECT status FROM experiment_executions WHERE id=?", String.class, plan.executionId().toString())).isEqualTo("PREPARING");
                assertThat(jdbc.queryForObject("SELECT snapshot_format FROM blade_execution_snapshots WHERE execution_id=?", String.class, plan.executionId().toString())).isEqualTo("CRI_CPU_V1");
                assertThat(journal.findByExecutionId(plan.executionId()).orElseThrow().uid()).isNull();
            });
            return ChaosBladeEngineTests.handoff("{\"code\":200,\"success\":true,\"result\":\""+uid+"\"}");
        });
        when(channel.status(any(), any())).thenReturn(ChaosBladeEngineTests.status(uid, "Destroyed"));
        when(channel.destroy(any(), any())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            BladeRecoveryHandle handle = call.getArgument(0);
            committed(() -> assertThat(jdbc.queryForObject("SELECT status FROM experiment_executions WHERE id=?", String.class,
                    handle.executionId().toString())).isEqualTo("DESTROYING"));
            return ChaosBladeEngineTests.strict("{\"code\":200,\"success\":true,\"result\":\"command: cri cpu fullload --cpu-count=1, destroy time: now\"}");
        });
    }
    private Experiment ready(UUID target) {
        return experiments.insert(Experiment.create(UUID.randomUUID(), "stub CRI", "available", target,
                UUID.fromString("00000000-0000-0000-0000-000000000101"), 10, "{\"percent\":10}").validate().ready());
    }
    private void committed(Runnable assertion) throws Exception {
        try (var executor = Executors.newSingleThreadExecutor()) {
            executor.submit(assertion).get(5, TimeUnit.SECONDS);
        }
    }
    @Test void startAndDestroyCrossCommitBoundariesWithoutClaimingFullRecovery() {
        assertThat(engine).isInstanceOf(ChaosBladeEngine.class);
        assertThat(context.getBeansOfType(com.chaoslab.engine.infrastructure.fake.FakeChaosEngine.class)).isEmpty();
        assertThat(context.getBeansOfType(com.chaoslab.execution.infrastructure.scheduling.AutomaticExperimentRecoveryJob.class)).isEmpty();
        var started = service.start(ready.getId(), "once");
        assertThat(started.execution().status()).isEqualTo(ExperimentExecutionStatus.RUNNING);
        var snapshot = journal.findByExecutionId(started.execution().id()).orElseThrow();
        assertThat(snapshot.uid()).isEqualTo(uid);
        var result = service.destroy(ready.getId(), started.execution().id());
        assertThat(result.status()).isEqualTo(ExperimentExecutionStatus.ROLLBACK_FAILED);
        assertThat(result.errorMessage()).contains("ENGINE_DESTROYED_RECOVERY_UNVERIFIED");
        assertThat(result.finishedAt()).isNull();
        assertThat(service.start(ready.getId(), "once").created()).isFalse();
        verify(channel, times(1)).create(any(), any());
        verify(channel).destroy(argThat(h -> uid.equals(h.uid())), any());
    }
    @Test void intentCommitFailurePreventsDispatch() {
        doAnswer(call -> {
            call.callRealMethod();
            TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization() {
                @Override public void beforeCommit(boolean readOnly) { throw new IllegalStateException("intent commit rejected"); }
            });
            return null;
        }).when(journal).recordIntent(any());
        var result = service.start(ready.getId(), "intent-fail");
        assertThat(result.execution().status()).isEqualTo(ExperimentExecutionStatus.FAILED);
        assertThat(journal.findByExecutionId(result.execution().id())).isEmpty();
        verify(channel, never()).create(any(), any());
    }
    @Test void localIdentityFailurePreventsDispatch() {
        when(local.verifyBeforeCreate(any(), any())).thenReturn(BladeLocalIdentityVerifier.Result.TOOL_CONTENT_MISMATCH);
        assertThat(service.start(ready.getId(), "identity-fail").execution().status()).isEqualTo(ExperimentExecutionStatus.FAILED);
        verify(channel, never()).create(any(), any());
    }
    @Test void freshTargetChangePreventsDispatch() {
        when(verifier.verify(any())).thenReturn(TargetIdentityVerification.verified(identity), TargetIdentityVerification.rejected("changed"));
        assertThat(service.start(ready.getId(), "target-fail").execution().status()).isEqualTo(ExperimentExecutionStatus.FAILED);
        verify(channel, never()).create(any(), any());
    }
    @Test void malformedJsonRetainsOccupancyAndDoesNotReplayCreate() {
        doReturn(ChaosBladeEngineTests.handoff("bad-json")).when(channel).create(any(), any());
        var result = service.start(ready.getId(), "uncertain");
        assertThat(result.execution().status()).isEqualTo(ExperimentExecutionStatus.CREATE_UNCERTAIN);
        assertThat(service.start(ready.getId(), "uncertain").created()).isFalse();
        var second = ready(identity.targetId());
        assertThatThrownBy(() -> service.start(second.getId(), "other"))
                .isInstanceOf(ExperimentExecutionStartRejectedException.class);
        verify(channel, times(1)).create(any(), any());
    }
    @Test void timeoutAfterDispatchIsUncertain() {
        doReturn(new ProcessRunResult(ProcessRunResult.Outcome.TIMED_OUT, null, "", "", true)).when(channel).create(any(), any());
        assertThat(service.start(ready.getId(), "timeout").execution().status()).isEqualTo(ExperimentExecutionStatus.CREATE_UNCERTAIN);
        verify(channel, times(1)).create(any(), any());
    }
    @Test void uidPersistenceFailureIsUncertainAndNoSecondReceiptIsInvented() {
        doAnswer(call -> {
            call.callRealMethod();
            TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization() {
                @Override public void beforeCommit(boolean readOnly) { throw new IllegalStateException("UID commit rejected"); }
            });
            return null;
        }).when(journal).recordUid(any(), anyString());
        var result = service.start(ready.getId(), "uid-fail");
        assertThat(result.execution().status()).isEqualTo(ExperimentExecutionStatus.CREATE_UNCERTAIN);
        assertThat(journal.findByExecutionId(result.execution().id()).orElseThrow().uid()).isNull();
        assertThat(service.start(ready.getId(), "uid-fail").created()).isFalse();
        assertThatThrownBy(() -> service.start(ready(identity.targetId()).getId(), "other"))
                .isInstanceOf(ExperimentExecutionStartRejectedException.class);
        verify(channel, times(1)).create(any(), any());
    }
    @Test void runningResultTransactionFailureBecomesUncertainWithDurableUid() {
        doAnswer(call -> {
            ExperimentExecution next = call.getArgument(0);
            var result = call.callRealMethod();
            if (next.getStatus() == ExperimentExecutionStatus.RUNNING)
                TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override public void beforeCommit(boolean readOnly) { throw new IllegalStateException("result commit rejected"); }
                });
            return result;
        }).when(executions).update(any());
        var result = service.start(ready.getId(), "result-fail");
        assertThat(result.execution().status()).isEqualTo(ExperimentExecutionStatus.CREATE_UNCERTAIN);
        assertThat(journal.findByExecutionId(result.execution().id()).orElseThrow().uid()).isEqualTo(uid);
        assertThat(service.start(ready.getId(), "result-fail").created()).isFalse();
        verify(channel, times(1)).create(any(), any());
    }
    @Test void destroySuccessWithoutDestroyedStatusDoesNotRecover() {
        var result = service.start(ready.getId(), "destroy-not-confirmed");
        when(channel.status(any(), any())).thenReturn(ChaosBladeEngineTests.status(uid, "Success"));
        assertThat(service.destroy(ready.getId(), result.execution().id()).status()).isEqualTo(ExperimentExecutionStatus.ROLLBACK_FAILED);
    }
    @Test void staleDestroyResultCannotOverwriteNewerDecision() {
        var started = service.start(ready.getId(), "stale-destroy");
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            var current = executions.findById(started.execution().id()).orElseThrow();
            assertThat(current.getStatus()).isEqualTo(ExperimentExecutionStatus.DESTROYING);
            executions.update(current.markRollbackFailed("concurrent recovery decision"));
            return ChaosBladeEngineTests.strict("{\"code\":200,\"success\":true,\"result\":\"command: cri cpu fullload --cpu-count=1, destroy time: now\"}");
        }).when(channel).destroy(any(), any());
        assertThatThrownBy(() -> service.destroy(ready.getId(), started.execution().id()))
                .isInstanceOf(org.springframework.dao.OptimisticLockingFailureException.class);
        assertThat(executions.findById(started.execution().id()).orElseThrow().getErrorMessage()).isEqualTo("concurrent recovery decision");
    }
}
