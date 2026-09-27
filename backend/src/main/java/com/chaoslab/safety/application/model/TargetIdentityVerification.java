package com.chaoslab.safety.application.model;

import java.util.Objects;

public record TargetIdentityVerification(
        boolean verified,
        VerifiedDockerTarget identity,
        String reason
) {
    public TargetIdentityVerification {
        Objects.requireNonNull(reason, "reason must not be null");
        if (verified != (identity != null)) {
            throw new IllegalArgumentException("verified must match identity presence");
        }
    }

    public static TargetIdentityVerification verified(VerifiedDockerTarget identity) {
        return new TargetIdentityVerification(true, identity, "local Demo container identity verified");
    }

    public static TargetIdentityVerification rejected(String reason) {
        return new TargetIdentityVerification(false, null, reason);
    }
}
