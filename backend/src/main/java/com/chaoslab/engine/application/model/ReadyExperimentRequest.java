package com.chaoslab.engine.application.model;

import java.util.Objects;
import java.util.UUID;

public record ReadyExperimentRequest(
        UUID executionId,
        UUID experimentId,
        UUID targetId,
        String scenarioCode,
        int durationSeconds,
        String parameters
) {

    public ReadyExperimentRequest {
        Objects.requireNonNull(executionId, "executionId must not be null");
        Objects.requireNonNull(experimentId, "experimentId must not be null");
        Objects.requireNonNull(targetId, "targetId must not be null");
        scenarioCode = requireText(scenarioCode, "scenarioCode");
        if (durationSeconds < 1) {
            throw new IllegalArgumentException("durationSeconds must be positive");
        }
        parameters = requireText(parameters, "parameters");
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
