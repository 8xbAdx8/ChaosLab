package com.chaoslab.experiment.interfaces.rest;

import com.chaoslab.target.domain.TargetEnvironment;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

public record DryRunPlanResponse(
        UUID experimentId,
        UUID targetId,
        String targetName,
        TargetEnvironment environment,
        String scenarioCode,
        int targetCount,
        int durationSeconds,
        int recoveryWithinSeconds,
        JsonNode parameters
) {
}
