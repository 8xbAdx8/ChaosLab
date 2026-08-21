package com.chaoslab.experiment.application.dto;

import java.util.UUID;

public record CreateExperimentCommand(
        String name,
        String hypothesis,
        UUID targetId,
        UUID scenarioId,
        int durationSeconds,
        String parameters
) {
}
