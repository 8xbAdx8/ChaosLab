package com.chaoslab.engine.application.model;

import com.chaoslab.safety.application.model.VerifiedDockerTarget;

import java.util.Objects;
import java.util.UUID;

public record ReadyExperimentRequest(
        UUID executionId,
        UUID experimentId,
        UUID targetId,
        String scenarioCode,
        int durationSeconds,
        String parameters,
        VerifiedDockerTarget verifiedTarget
) {

    public ReadyExperimentRequest(
            UUID executionId,
            UUID experimentId,
            UUID targetId,
            String scenarioCode,
            int durationSeconds,
            String parameters
    ) {
        this(executionId, experimentId, targetId, scenarioCode,
                durationSeconds, parameters, null);
    }

    public ReadyExperimentRequest {
        Objects.requireNonNull(executionId, "executionId must not be null");
        Objects.requireNonNull(experimentId, "experimentId must not be null");
        Objects.requireNonNull(targetId, "targetId must not be null");
        scenarioCode = requireText(scenarioCode, "scenarioCode");
        if (durationSeconds < 1) {
            throw new IllegalArgumentException("durationSeconds must be positive");
        }
        parameters = requireText(parameters, "parameters");
        if (verifiedTarget != null && !verifiedTarget.targetId().equals(targetId)) {
            throw new IllegalArgumentException("verified target does not match targetId");
        }
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }
}
