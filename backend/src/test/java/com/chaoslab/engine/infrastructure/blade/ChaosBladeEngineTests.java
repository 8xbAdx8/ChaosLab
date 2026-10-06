package com.chaoslab.engine.infrastructure.blade;

import com.chaoslab.engine.application.EngineCreateUncertainException;
import com.chaoslab.engine.application.model.*;
import com.chaoslab.safety.application.model.*;
import com.chaoslab.safety.application.port.TargetIdentityVerifier;
import com.chaoslab.target.application.port.TargetRepository;
import com.chaoslab.target.domain.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ChaosBladeEngineTests {
    private final TargetRepository targets = mock(TargetRepository.class);
    private final TargetIdentityVerifier verifier = mock(TargetIdentityVerifier.class);
    private final BladeLocalIdentityVerifier local = mock(BladeLocalIdentityVerifier.class);
    private final JdbcBladeExecutionJournal journal = mock(JdbcBladeExecutionJournal.class);
    private final BladeProcessChannel channel = mock(BladeProcessChannel.class);
    private final DockerCpuCommandPlan.Deployment deployment = new DockerCpuCommandPlan.Deployment(Path.of("unused-blade").toAbsolutePath());
    private final Target target = Target.register(UUID.randomUUID(), "order-service", TargetType.DOCKER_CONTAINER, TargetEnvironment.CHAOS_LAB);
    private final VerifiedDockerTarget identity = new VerifiedDockerTarget(target.getId(), "a".repeat(64), "sha256:" + "b".repeat(64), "order-service");
    private final ReadyExperimentRequest request = new ReadyExperimentRequest(UUID.randomUUID(), UUID.randomUUID(), target.getId(), "CPU_LOAD", 10, "{\"percent\":10}", identity);
    private final String uid = "0123456789abcdef";
    private final EngineExperimentId reference = new EngineExperimentId("blade-" + request.executionId());
    private final ChaosBladeEngine engine = new ChaosBladeEngine(deployment, "node-1", "state-1", "api3", "c".repeat(64),
            targets, verifier, local, journal, channel, Clock.systemUTC());
    private BladeExecutionSnapshot saved;

    @BeforeEach void fixtures() {
        when(targets.findById(target.getId())).thenReturn(Optional.of(target));
        when(verifier.verify(target)).thenReturn(TargetIdentityVerification.verified(identity));
        when(local.verifyBeforeCreate(any(), any())).thenReturn(BladeLocalIdentityVerifier.Result.LOCAL_IDENTITY_MATCHED_NEEDS_TARGET_CHECK);
        when(local.verify(any())).thenReturn(BladeLocalIdentityVerifier.Result.LOCAL_IDENTITY_MATCHED_NEEDS_TARGET_CHECK);
        var plan = DockerCpuCommandPlan.from(request, identity, deployment);
        var intent = BladeExecutionSnapshot.intent(plan, "node-1", "state-1", "api3", "c".repeat(64), Instant.now());
        saved = new BladeExecutionSnapshot(intent.executionId(), identity, "node-1", "state-1", "api3", "c".repeat(64),
                10, 10, intent.recordedAt(), intent.recoveryDeadline(), uid, "CRI_CPU_V1");
        when(journal.findByExecutionId(request.executionId())).thenReturn(Optional.of(saved));
        when(channel.create(any(), anyString(), any())).thenAnswer(invocation ->
                handoff("{\"code\":200,\"success\":true,\"result\":\"" + invocation.getArgument(1) + "\"}"));
        when(channel.status(any(), any())).thenReturn(status(uid, "Destroyed"));
        when(channel.destroy(any(), any())).thenReturn(strict("{\"code\":200,\"success\":true,\"result\":\"command: cri cpu fullload --cpu-count=1, destroy time: now\"}"));
    }
    static ProcessRunResult handoff(String text) { return new ProcessRunResult(ProcessRunResult.Outcome.HANDOFF, 0, text, "", false); }
    static ProcessRunResult strict(String text) { return new ProcessRunResult(ProcessRunResult.Outcome.EXITED, 0, text, "", true); }
    static ProcessRunResult status(String uid, String state) {
        return strict("{\"code\":200,\"success\":true,\"result\":{\"Uid\":\""+uid+"\",\"Command\":\"cri\",\"SubCommand\":\"cpu fullload\","
                + "\"Flag\":\"\",\"Status\":\""+state+"\",\"Error\":\"\",\"CreateTime\":\"\",\"UpdateTime\":\"\"}}");
    }
    @Test void createOrdersFreshIntentIdentityHandoffAndUidPersistence() {
        assertThat(engine.create(request).status()).isEqualTo(EngineStatus.RUNNING);
        var order = inOrder(verifier, journal, local, channel);
        order.verify(verifier).verify(target);
        var persisted = org.mockito.ArgumentCaptor.forClass(BladeExecutionSnapshot.class);
        order.verify(journal).recordIntent(persisted.capture());
        assertThat(persisted.getValue().uid()).matches("[0-9a-f]{16}");
        order.verify(verifier).verify(target);
        order.verify(local).verifyBeforeCreate(any(), any());
        order.verify(channel).create(any(), eq(persisted.getValue().uid()), any());
        verify(journal, never()).recordUid(any(), anyString());
    }
    @Test void intentFailureNeverDispatches() {
        doThrow(new IllegalStateException()).when(journal).recordIntent(any());
        assertThatThrownBy(() -> engine.create(request)).isNotInstanceOf(EngineCreateUncertainException.class);
        verifyNoInteractions(channel);
    }
    @Test void identityFailureNeverDispatches() {
        when(local.verifyBeforeCreate(any(), any())).thenReturn(BladeLocalIdentityVerifier.Result.TOOL_CONTENT_MISMATCH);
        assertThatThrownBy(() -> engine.create(request)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(channel);
    }
    @Test void changedTargetNeverDispatches() {
        when(verifier.verify(target)).thenReturn(TargetIdentityVerification.verified(new VerifiedDockerTarget(target.getId(), "d".repeat(64), identity.imageId(), "order-service")));
        assertThatThrownBy(() -> engine.create(request)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(channel, journal);
    }
    @Test void malformedJsonTimeoutAndUidWriteFailureAreUncertain() {
        for (var result : List.of(handoff("invalid"), handoff("{\"code\":200,\"success\":true,\"result\":\"bad-uid\"}"),
                new ProcessRunResult(ProcessRunResult.Outcome.TIMED_OUT, null, "", "", true))) {
            when(channel.create(any(), anyString(), any())).thenReturn(result);
            assertThatThrownBy(() -> engine.create(request)).isInstanceOf(EngineCreateUncertainException.class);
        }
        when(channel.create(any(), anyString(), any())).thenAnswer(invocation -> {
            String allocated = invocation.getArgument(1);
            String different = allocated.equals(uid) ? "fedcba9876543210" : uid;
            return handoff("{\"code\":200,\"success\":true,\"result\":\""+different+"\"}");
        });
        assertThatThrownBy(() -> engine.create(request)).isInstanceOf(EngineCreateUncertainException.class);
    }
    @Test void statusUsesPersistedUidAndRejectsMismatch() {
        assertThat(engine.status(reference).status()).isEqualTo(EngineStatus.DESTROYED);
        verify(channel).status(argThat(h -> h.uid().equals(uid)), any());
        when(channel.status(any(), any())).thenReturn(status("fedcba9876543210", "Destroyed"));
        assertThatThrownBy(() -> engine.status(reference)).isInstanceOf(IllegalStateException.class);
    }
    @Test void destroyRequiresSameUidAndDestroyedStatusAndOnlyConfirmsEngine() {
        when(channel.status(any(), any())).thenReturn(status(uid, "Success"));
        assertThatThrownBy(() -> engine.destroy(reference)).isInstanceOf(IllegalStateException.class);
        when(channel.status(any(), any())).thenReturn(status(uid, "Destroyed"));
        assertThat(engine.destroy(reference).status()).isEqualTo(EngineStatus.ENGINE_RECOVERED);
        verify(channel, times(2)).destroy(argThat(h -> h.uid().equals(uid)), any());
    }
    @Test void missingUidAndLocalMismatchNeverDestroy() {
        var missing = new BladeExecutionSnapshot(saved.executionId(), identity, "node-1", "state-1", "api3", saved.toolSha256(), 10, 10,
                saved.recordedAt(), saved.recoveryDeadline(), null, "CRI_CPU_V1");
        when(journal.findByExecutionId(request.executionId())).thenReturn(Optional.of(missing));
        assertThatThrownBy(() -> engine.destroy(reference)).isInstanceOf(IllegalStateException.class);
        when(journal.findByExecutionId(request.executionId())).thenReturn(Optional.of(saved));
        when(local.verify(saved)).thenReturn(BladeLocalIdentityVerifier.Result.NODE_MISMATCH);
        assertThatThrownBy(() -> engine.destroy(reference)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(channel);
    }
    @Test void targetChangedAndLegacySnapshotNeverDestroy() {
        when(verifier.verify(target)).thenReturn(TargetIdentityVerification.verified(new VerifiedDockerTarget(target.getId(),
                "d".repeat(64), identity.imageId(), "order-service")));
        assertThatThrownBy(() -> engine.destroy(reference)).isInstanceOf(IllegalStateException.class);
        var legacy = new BladeExecutionSnapshot(saved.executionId(), identity, "node-1", "state-1", "api3", saved.toolSha256(), 10, 10,
                saved.recordedAt(), saved.recoveryDeadline(), uid);
        when(journal.findByExecutionId(request.executionId())).thenReturn(Optional.of(legacy));
        assertThatThrownBy(() -> engine.destroy(reference)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(channel);
    }
    @Test void differentSavedExecutorOrStateNeverReachesChannel() {
        for (String[] ids : List.of(new String[]{"other-node", "state-1"}, new String[]{"node-1", "other-state"})) {
            var other = new BladeExecutionSnapshot(saved.executionId(), identity, ids[0], ids[1], "api3", saved.toolSha256(), 10, 10,
                    saved.recordedAt(), saved.recoveryDeadline(), uid, "CRI_CPU_V1");
            when(journal.findByExecutionId(request.executionId())).thenReturn(Optional.of(other));
            assertThatThrownBy(() -> engine.destroy(reference)).isInstanceOf(IllegalStateException.class);
        }
        verifyNoInteractions(channel);
    }
}
