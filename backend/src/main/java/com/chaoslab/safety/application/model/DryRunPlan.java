package com.chaoslab.safety.application.model;

import com.chaoslab.target.domain.TargetEnvironment;

import java.util.Objects;
import java.util.UUID;

public record DryRunPlan(
        UUID experimentId,
        UUID targetId,
        String targetName,
        TargetEnvironment environment,
        String scenarioCode,
        int targetCount,
        int durationSeconds,
        int recoveryWithinSeconds,
        String parameters
) {

    public DryRunPlan {
        Objects.requireNonNull(experimentId, "experimentId must not be null");
        Objects.requireNonNull(targetId, "targetId must not be null");
        targetName = requireText(targetName, "targetName");
        Objects.requireNonNull(environment, "environment must not be null");
        scenarioCode = requireText(scenarioCode, "scenarioCode");
        if (targetCount != 1) {
            throw new IllegalArgumentException("targetCount must be exactly 1");
        }
        if (durationSeconds < 1) {
            throw new IllegalArgumentException("durationSeconds must be positive");
        }
        if (recoveryWithinSeconds != durationSeconds) {
            throw new IllegalArgumentException(
                    "recoveryWithinSeconds must equal durationSeconds"
            );
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
