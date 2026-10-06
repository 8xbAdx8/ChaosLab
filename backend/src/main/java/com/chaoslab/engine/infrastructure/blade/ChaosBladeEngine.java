package com.chaoslab.engine.infrastructure.blade;

import com.chaoslab.engine.application.EngineCreateUncertainException;
import com.chaoslab.engine.application.model.*;
import com.chaoslab.engine.application.port.ChaosEngine;
import com.chaoslab.safety.application.model.VerifiedDockerTarget;
import com.chaoslab.safety.application.port.TargetIdentityVerifier;
import com.chaoslab.target.application.port.TargetRepository;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.Clock;
import java.util.UUID;

/** Opt-in adapter. Engine IDs are journal references, never invented native UIDs. */
public final class ChaosBladeEngine implements ChaosEngine {
    private static final java.security.SecureRandom UID_RANDOM = new java.security.SecureRandom();
    private final DockerCpuCommandPlan.Deployment deployment;
    private final String nodeId, stateId, version, sha;
    private final TargetRepository targets;
    private final TargetIdentityVerifier targetVerifier;
    private final BladeLocalIdentityVerifier localVerifier;
    private final JdbcBladeExecutionJournal journal;
    private final BladeProcessChannel channel;
    private final Clock clock;
    private final BladeResponseDecoder decoder = new BladeResponseDecoder(BladeExecutionSnapshot.CRI_CPU_V1);

    public ChaosBladeEngine(DockerCpuCommandPlan.Deployment deployment, String nodeId, String stateId,
                            String version, String sha, TargetRepository targets, TargetIdentityVerifier targetVerifier,
                            BladeLocalIdentityVerifier localVerifier, JdbcBladeExecutionJournal journal,
                            BladeProcessChannel channel, Clock clock) {
        this.deployment = deployment;
        this.nodeId = nodeId;
        this.stateId = stateId;
        this.version = version;
        this.sha = sha;
        this.targets = targets;
        this.targetVerifier = targetVerifier;
        this.localVerifier = localVerifier;
        this.journal = journal;
        this.channel = channel;
        this.clock = clock;
    }

    @Override public EngineCreateResult create(ReadyExperimentRequest request) {
        outsideTransaction();
        var plan = DockerCpuCommandPlan.from(request, fresh(request.targetId()), deployment);
        byte[] randomUid = new byte[8];
        UID_RANDOM.nextBytes(randomUid);
        String nativeUid = java.util.HexFormat.of().formatHex(randomUid);
        var intent = BladeExecutionSnapshot.intent(plan, nodeId, stateId, version, sha, clock.instant())
                .withPreallocatedUid(nativeUid);
        // Spring journal REQUIRES_NEW verifies committed PREPARING and commits before returning.
        // Duplicate intent fails closed: even a direct adapter replay cannot dispatch again.
        journal.recordIntent(intent);
        if (!plan.target().equals(fresh(request.targetId())))
            throw new IllegalStateException("target changed after UID commit");
        if (localVerifier.verifyBeforeCreate(intent, plan)
                != BladeLocalIdentityVerifier.Result.LOCAL_IDENTITY_MATCHED_NEEDS_TARGET_CHECK)
            throw new IllegalStateException("local create identity rejected before dispatch");
        try {
            var response = channel.create(plan, nativeUid, () -> Thread.currentThread().isInterrupted());
            var uid = decoder.decodeCreate(response).uid();
            if (!nativeUid.equals(uid)) throw new IllegalStateException("create UID mismatch");
            return new EngineCreateResult(reference(request.executionId()), EngineStatus.RUNNING);
        } catch (RuntimeException uncertain) {
            // Do not expose raw stdout, paths or native UID in API/log exception messages.
            // UID was already committed before dispatch. Never overwrite it or retry create.
            throw new EngineCreateUncertainException(reference(request.executionId()));
        }
    }

    @Override public EngineStatusResult status(EngineExperimentId reference) {
        outsideTransaction();
        var saved = load(reference);
        var fresh = verifyRecovery(saved);
        var observation = decoder.decodeStatus(channel.status(saved.recoveryHandle().orElseThrow(), () -> false));
        var decision = decision(saved, fresh, observation);
        return new EngineStatusResult(reference, switch (decision) {
            case CONFIRMED_RECOVERED -> EngineStatus.DESTROYED;
            case DESTROY_REQUIRED -> EngineStatus.RUNNING;
            default -> throw new IllegalStateException("unsafe engine status; manual intervention required");
        });
    }

    @Override public EngineDestroyResult destroy(EngineExperimentId reference) {
        outsideTransaction();
        var recoveryStarted = clock.instant();
        var saved = load(reference);
        verifyRecovery(saved);
        var handle = saved.recoveryHandle().orElseThrow();
        decoder.decodeDestroy(channel.destroy(handle, () -> false));
        // Reverify and query the same persisted UID; acknowledgement alone proves nothing.
        var fresh = verifyRecovery(saved);
        var observation = decoder.decodeStatus(channel.status(handle, () -> false));
        if (decision(saved, fresh, observation) != BladeRecoveryContract.Decision.CONFIRMED_RECOVERED)
            throw new IllegalStateException("engine recovery not confirmed");
        var confirmedAt = clock.instant();
        BladeProcessChannel.RecoveryObservation evidence;
        try { evidence = channel.observe(handle); }
        catch (RuntimeException unknown) { evidence = null; }
        var assessment = BladeRecoveryEvidenceValidator.assess(handle, recoveryStarted, clock.instant(), java.time.Duration.ofSeconds(10),
                new BladeRecoveryEvidenceValidator.Observation<>(handle, confirmedAt, BladeRecoveryContract.Decision.CONFIRMED_RECOVERED),
                evidence == null ? null : new BladeRecoveryEvidenceValidator.Observation<>(evidence.subject(), evidence.observedAt(), evidence.residual()),
                evidence == null ? null : new BladeRecoveryEvidenceValidator.Observation<>(evidence.subject(), evidence.observedAt(), evidence.health()));
        if (assessment == BladeRecoveryEvidenceGate.Outcome.MANUAL_INTERVENTION)
            throw new IllegalStateException("recovery evidence requires manual intervention");
        return new EngineDestroyResult(reference, assessment == BladeRecoveryEvidenceGate.Outcome.VERIFIED
                ? EngineStatus.DESTROYED : EngineStatus.ENGINE_RECOVERED);
    }

    private BladeRecoveryContract.Decision decision(BladeExecutionSnapshot saved, VerifiedDockerTarget fresh,
                                                    BladeResponseDecoder.StatusObservation observed) {
        return BladeRecoveryContract.decide(saved.recoveryHandle().orElseThrow(), nodeId, fresh,
                observed.uid(), observed.status());
    }

    private VerifiedDockerTarget verifyRecovery(BladeExecutionSnapshot saved) {
        if (saved.recoveryHandle().isEmpty() || !nodeId.equals(saved.executorInstanceId())
                || !stateId.equals(saved.stateDirectoryId())
                || localVerifier.verify(saved) != BladeLocalIdentityVerifier.Result.LOCAL_IDENTITY_MATCHED_NEEDS_TARGET_CHECK)
            throw new IllegalStateException("recovery identity unavailable; manual intervention required");
        var fresh = fresh(saved.target().targetId());
        if (!fresh.equals(saved.target())) throw new IllegalStateException("recovery target changed");
        return fresh;
    }

    private VerifiedDockerTarget fresh(UUID targetId) {
        var target = targets.findById(targetId).orElseThrow(() -> new IllegalStateException("target unavailable"));
        var verification = targetVerifier.verify(target);
        if (!verification.verified() || !targetId.equals(verification.identity().targetId()))
            throw new IllegalStateException("fresh target verification rejected");
        return verification.identity();
    }

    private BladeExecutionSnapshot load(EngineExperimentId reference) {
        if (!reference.value().matches("blade-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
            throw new IllegalArgumentException("not a Blade journal reference");
        var saved = journal.findByExecutionId(UUID.fromString(reference.value().substring(6)))
                .orElseThrow(() -> new IllegalStateException("persisted intent missing"));
        if (!BladeExecutionSnapshot.CRI_CPU_V1.equals(saved.format()))
            throw new IllegalStateException("adapter only supports CRI_CPU_V1");
        return saved;
    }

    private static EngineExperimentId reference(UUID executionId) { return new EngineExperimentId("blade-" + executionId); }
    private static void outsideTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("external engine calls require no ambient transaction");
    }
}
