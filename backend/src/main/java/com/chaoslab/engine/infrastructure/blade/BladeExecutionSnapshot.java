package com.chaoslab.engine.infrastructure.blade;

import com.chaoslab.safety.application.model.VerifiedDockerTarget;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Versioned immutable CPU intent; stored provenance is not proof of live target/tool identity. */
public record BladeExecutionSnapshot(
        UUID executionId, VerifiedDockerTarget target, String executorInstanceId,
        String stateDirectoryId, String toolVersion, String toolSha256,
        int cpuPercent, int durationSeconds, Instant recordedAt, Instant recoveryDeadline, String uid, String format
) {
    public static final String DOCKER_CPU_V1 = "DOCKER_CPU_V1";
    public static final String CRI_CPU_V1 = "CRI_CPU_V1";
    /** Historical constructor retains historical interpretation. */
    public BladeExecutionSnapshot(UUID executionId, VerifiedDockerTarget target, String executorInstanceId,
            String stateDirectoryId, String toolVersion, String toolSha256, int cpuPercent, int durationSeconds,
            Instant recordedAt, Instant recoveryDeadline, String uid) {
        this(executionId, target, executorInstanceId, stateDirectoryId, toolVersion, toolSha256,
                cpuPercent, durationSeconds, recordedAt, recoveryDeadline, uid, DOCKER_CPU_V1);
    }
    public BladeExecutionSnapshot {
        if (!DOCKER_CPU_V1.equals(format) && !CRI_CPU_V1.equals(format))
            throw new IllegalArgumentException("unknown snapshot format");
        Objects.requireNonNull(executionId);
        Objects.requireNonNull(target);
        require(executorInstanceId, "[a-z0-9][a-z0-9-]{0,63}");
        require(stateDirectoryId, "[a-z0-9][a-z0-9-]{0,63}");
        require(toolVersion, "[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}");
        require(toolSha256, "[0-9a-f]{64}");
        if (cpuPercent < 10 || cpuPercent > 40 || durationSeconds < 1 || durationSeconds > 30) {
            throw new IllegalArgumentException("invalid Docker CPU snapshot limits");
        }
        Objects.requireNonNull(recordedAt);
        Objects.requireNonNull(recoveryDeadline);
        if (!recordedAt.plusSeconds(durationSeconds).equals(recoveryDeadline)) {
            throw new IllegalArgumentException("recovery deadline must match the intent duration");
        }
        if (uid != null) new BladeRecoveryHandle(executionId, executorInstanceId, target, uid, format);
    }

    public static BladeExecutionSnapshot intent(DockerCpuCommandPlan plan, String node, String stateDirectoryId,
                                                String toolVersion, String sha256, Instant now) {
        Instant recorded = now.truncatedTo(ChronoUnit.MICROS);
        return new BladeExecutionSnapshot(plan.executionId(), plan.target(), node, stateDirectoryId,
                toolVersion, sha256, plan.percent(), plan.durationSeconds(), recorded,
                recorded.plusSeconds(plan.durationSeconds()), null, CRI_CPU_V1);
    }

    public List<String> createArguments() {
        return createArguments(DockerCpuCommandPlan.DEFAULT_DEPLOYMENT);
    }

    public BladeExecutionSnapshot withPreallocatedUid(String nativeUid) {
        if (!CRI_CPU_V1.equals(format) || uid != null)
            throw new IllegalStateException("preallocation requires a fresh CRI intent");
        return new BladeExecutionSnapshot(executionId, target, executorInstanceId, stateDirectoryId,
                toolVersion, toolSha256, cpuPercent, durationSeconds, recordedAt, recoveryDeadline,
                Objects.requireNonNull(nativeUid), format);
    }

    public List<String> createArguments(DockerCpuCommandPlan.Deployment deployment) {
        var arguments = CRI_CPU_V1.equals(format)
                ? DockerCpuCommandPlan.criArguments(deployment, target, cpuPercent, durationSeconds)
                : DockerCpuCommandPlan.cpuArguments(target, cpuPercent, durationSeconds);
        if (CRI_CPU_V1.equals(format) && uid != null) {
            var allocated = new java.util.ArrayList<>(arguments);
            allocated.add("--uid"); allocated.add(uid);
            return List.copyOf(allocated);
        }
        return arguments;
    }

    public Optional<BladeRecoveryHandle> recoveryHandle() {
        return uid == null ? Optional.empty()
                : Optional.of(new BladeRecoveryHandle(executionId, executorInstanceId, target, uid, format));
    }

    private static void require(String value, String pattern) {
        if (value == null || !value.matches(pattern)) throw new IllegalArgumentException("invalid execution provenance");
    }
}
