package com.chaoslab.experiment.application.dto;

import com.chaoslab.experiment.domain.Experiment;
import com.chaoslab.experiment.domain.ExperimentStatus;

import java.util.UUID;

public record ExperimentDetails(
        UUID id,
        String name,
        String hypothesis,
        UUID targetId,
        UUID scenarioId,
        int durationSeconds,
        String parameters,
        ExperimentStatus status,
        long version
) {

    public static ExperimentDetails from(Experiment experiment) {
        return new ExperimentDetails(
                experiment.getId(),
                experiment.getName(),
                experiment.getHypothesis(),
                experiment.getTargetId(),
                experiment.getScenarioId(),
                experiment.getDurationSeconds(),
                experiment.getParameters(),
                experiment.getStatus(),
                experiment.getVersion()
        );
    }
}
