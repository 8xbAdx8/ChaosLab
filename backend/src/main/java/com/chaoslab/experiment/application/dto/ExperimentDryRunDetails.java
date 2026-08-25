package com.chaoslab.experiment.application.dto;

import com.chaoslab.safety.application.model.SafetyDecision;

import java.util.Objects;

public record ExperimentDryRunDetails(
        ExperimentDetails experiment,
        SafetyDecision decision
) {

    public ExperimentDryRunDetails {
        Objects.requireNonNull(experiment, "experiment must not be null");
        Objects.requireNonNull(decision, "decision must not be null");
    }
}
