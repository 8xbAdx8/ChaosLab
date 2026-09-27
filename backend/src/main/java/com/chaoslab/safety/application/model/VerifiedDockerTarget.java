package com.chaoslab.safety.application.model;

import java.util.Objects;
import java.util.UUID;

public record VerifiedDockerTarget(
        UUID targetId,
        String containerId,
        String imageId,
        String metricsJob
) {
    public VerifiedDockerTarget {
        Objects.requireNonNull(targetId, "targetId must not be null");
        if (containerId == null || !containerId.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("containerId must be a full Docker ID");
        }
        if (imageId == null || !imageId.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException("imageId must be a Docker SHA-256 ID");
        }
        if (!"order-service".equals(metricsJob)) {
            throw new IllegalArgumentException("metricsJob must be order-service");
        }
    }
}
