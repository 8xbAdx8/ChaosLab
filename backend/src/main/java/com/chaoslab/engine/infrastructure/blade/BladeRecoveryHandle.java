package com.chaoslab.engine.infrastructure.blade;

import com.chaoslab.safety.application.model.VerifiedDockerTarget;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Must eventually be persisted before acknowledging a real execution as running. */
public record BladeRecoveryHandle(
        UUID executionId, String executorInstanceId, VerifiedDockerTarget target, String uid
) {
    public BladeRecoveryHandle {
        Objects.requireNonNull(executionId);
        Objects.requireNonNull(target);
        if (executorInstanceId == null || !executorInstanceId.matches("[a-z0-9][a-z0-9-]{0,63}")) {
            throw new IllegalArgumentException("executor instance identity is required");
        }
        // Intentionally narrower than an arbitrary engine ID; verify the selected
        // tool release against this contract before enabling real execution.
        if (uid == null || !uid.matches("[0-9a-f]{16,64}")) {
            throw new IllegalArgumentException("Blade UID must be 16 to 64 lowercase hexadecimal characters");
        }
    }

    public List<String> statusArguments() {
        return List.of(DockerCpuCommandPlan.EXECUTABLE, "status", uid);
    }

    public List<String> destroyArguments() {
        return List.of(DockerCpuCommandPlan.EXECUTABLE, "destroy", uid);
    }
}
