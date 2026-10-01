package com.chaoslab.execution.application;

import com.chaoslab.engine.application.model.EngineCreateResult;
import com.chaoslab.engine.application.model.EngineExperimentId;
import com.chaoslab.engine.application.model.EngineStatus;
import com.chaoslab.engine.application.model.ReadyExperimentRequest;
import com.chaoslab.engine.application.port.ChaosEngine;
import com.chaoslab.execution.application.port.ExperimentExecutionRepository;
import com.chaoslab.execution.domain.ExperimentExecution;
import com.chaoslab.execution.domain.ExperimentExecutionStatus;
import com.chaoslab.experiment.application.port.ExperimentRepository;
import com.chaoslab.experiment.domain.Experiment;
import com.chaoslab.target.application.port.TargetRepository;
import com.chaoslab.target.domain.Target;
import com.chaoslab.target.domain.TargetEnvironment;
import com.chaoslab.target.domain.TargetType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:start-transactions;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "chaoslab.execution.max-active-executions=10"
})
class ExecutionStartTransactionIntegrationTests {
    @Autowired ExperimentExecutionApplicationService service;
    @Autowired ExperimentRepository experiments;
    @Autowired TargetRepository targets;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoBean ChaosEngine engine;
    @MockitoSpyBean ExperimentExecutionRepository executions;

    @BeforeEach
    void simulatedEngine() {
        when(engine.simulated()).thenReturn(true);
        when(engine.create(any())).thenAnswer(call -> running(call.getArgument(0)));
    }

    @Test
    void engineRunsOutsideTransactionAfterIntentIsVisibleToAnotherConnection() {
        var experiment = ready();
        doAnswer(call -> {
            ReadyExperimentRequest request = call.getArgument(0);
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            try (var reader = Executors.newSingleThreadExecutor()) {
                var persisted = reader.submit(() -> executions.findById(request.executionId()).orElseThrow())
                        .get(5, TimeUnit.SECONDS);
                assertThat(persisted.getStatus()).isEqualTo(ExperimentExecutionStatus.PREPARING);
            }
            var replay = service.start(experiment.getId(), "key");
            assertThat(replay.created()).isFalse();
            assertThat(replay.execution().status()).isEqualTo(ExperimentExecutionStatus.PREPARING);
            assertThatThrownBy(() -> service.start(experiment.getId(), "second-key"))
                    .isInstanceOf(ExperimentExecutionStartRejectedException.class);
            return running(request);
        }).when(engine).create(any());
        var result = service.start(experiment.getId(), "key");
        assertThat(result.execution().status()).isEqualTo(ExperimentExecutionStatus.RUNNING);
        assertThat(executions.findById(result.execution().id()).orElseThrow().getStatus())
                .isEqualTo(ExperimentExecutionStatus.RUNNING);
        verify(engine, times(1)).create(any());
    }

    @Test
    void interruptionBeforeResultPersistenceLeavesCommittedReservation() {
        var experiment = ready();
        doThrow(new AssertionError("simulated abrupt interruption")).when(engine).create(any());
        assertThatThrownBy(() -> service.start(experiment.getId(), "key")).isInstanceOf(AssertionError.class);
        assertPreparingAndReplayOnly(experiment);
    }

    @Test
    void resultPersistenceFailureCannotRollBackIntentOrRetryCreate() {
        var experiment = ready();
        doThrow(new IllegalStateException("simulated write failure")).when(executions).update(any());
        assertThatThrownBy(() -> service.start(experiment.getId(), "key"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class)
                .hasMessageContaining("simulated write failure");
        assertPreparingAndReplayOnly(experiment);
    }

    @Test
    void intentPersistenceFailureNeverCallsEngine() {
        var experiment = ready();
        doThrow(new IllegalStateException("intent unavailable")).when(executions).insert(any());
        assertThatThrownBy(() -> service.start(experiment.getId(), "key"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        verify(engine, never()).create(any());
        assertThat(executions.findByExperimentIdAndIdempotencyKey(experiment.getId(), "key")).isEmpty();
    }

    @Test
    void callerRollbackCannotEraseAlreadyDispatchedExecution() {
        var experiment = ready();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            service.start(experiment.getId(), "key");
            status.setRollbackOnly();
        });
        assertThat(executions.findByExperimentIdAndIdempotencyKey(experiment.getId(), "key").orElseThrow().getStatus())
                .isEqualTo(ExperimentExecutionStatus.RUNNING);
    }

    private void assertPreparingAndReplayOnly(Experiment experiment) {
        ExperimentExecution stored = executions.findByExperimentIdAndIdempotencyKey(experiment.getId(), "key").orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(ExperimentExecutionStatus.PREPARING);
        var replay = service.start(experiment.getId(), "key");
        assertThat(replay.created()).isFalse();
        assertThat(replay.execution().id()).isEqualTo(stored.getId());
        verify(engine, times(1)).create(any());
    }

    private EngineCreateResult running(ReadyExperimentRequest request) {
        return new EngineCreateResult(new EngineExperimentId("fake-" + request.executionId()), EngineStatus.RUNNING);
    }

    private Experiment ready() {
        UUID targetId = UUID.randomUUID();
        targets.save(Target.register(targetId, "transaction-" + targetId, TargetType.JAVA_APPLICATION,
                TargetEnvironment.CHAOS_LAB));
        return experiments.insert(Experiment.create(UUID.randomUUID(), "transaction test", "available",
                targetId, UUID.fromString("00000000-0000-0000-0000-000000000101"), 30,
                "{\"percent\":40}").validate().ready());
    }
}
