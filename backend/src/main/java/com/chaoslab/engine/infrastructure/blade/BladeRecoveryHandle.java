package com.chaoslab.engine.infrastructure.blade;

import com.chaoslab.safety.application.model.VerifiedDockerTarget;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Persist via the Blade journal before acknowledging a real execution as running. */
public record BladeRecoveryHandle(
        UUID executionId, String executorInstanceId, VerifiedDockerTarget target, String uid, String format
) {
    public BladeRecoveryHandle(UUID executionId, String executorInstanceId, VerifiedDockerTarget target, String uid) {
        this(executionId, executorInstanceId, target, uid, BladeExecutionSnapshot.DOCKER_CPU_V1);
    }
    public BladeRecoveryHandle {
        if (!BladeExecutionSnapshot.DOCKER_CPU_V1.equals(format) && !BladeExecutionSnapshot.CRI_CPU_V1.equals(format))
            throw new IllegalArgumentException("unknown recovery format");
        Objects.requireNonNull(executionId);
        Objects.requireNonNull(target);
        if (executorInstanceId == null || !executorInstanceId.matches("[a-z0-9][a-z0-9-]{0,63}")) {
            throw new IllegalArgumentException("executor instance identity is required");
        }
        // Intentionally narrower than an arbitrary engine ID; verify the selected
        // tool release against this contract before enabling real execution.
        if (uid == null || !uid.matches(BladeExecutionSnapshot.CRI_CPU_V1.equals(format)
                ? "[0-9a-f]{16}" : "[0-9a-f]{16,64}")) {
            throw new IllegalArgumentException("Blade UID must be 16 to 64 lowercase hexadecimal characters");
        }
    }

    public List<String> statusArguments() {
        return statusArguments(DockerCpuCommandPlan.DEFAULT_DEPLOYMENT);
    }

    public List<String> statusArguments(DockerCpuCommandPlan.Deployment deployment) {
        return BladeExecutionSnapshot.CRI_CPU_V1.equals(format)
                ? List.of(deployment.executable().toString(), "status", uid, "--type", "create")
                : List.of(DockerCpuCommandPlan.EXECUTABLE, "status", uid);
    }

    public List<String> destroyArguments() {
        return destroyArguments(DockerCpuCommandPlan.DEFAULT_DEPLOYMENT);
    }

    public List<String> destroyArguments(DockerCpuCommandPlan.Deployment deployment) {
        return List.of(BladeExecutionSnapshot.CRI_CPU_V1.equals(format)
                ? deployment.executable().toString() : DockerCpuCommandPlan.EXECUTABLE, "destroy", uid);
    }
}
