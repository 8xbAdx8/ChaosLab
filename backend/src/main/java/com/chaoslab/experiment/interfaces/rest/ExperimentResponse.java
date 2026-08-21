package com.chaoslab.experiment.interfaces.rest;

import com.chaoslab.experiment.domain.ExperimentStatus;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

public record ExperimentResponse(
        UUID id,
        String name,
        String hypothesis,
        UUID targetId,
        UUID scenarioId,
        int durationSeconds,
        JsonNode parameters,
        ExperimentStatus status,
        long version
) {
}
