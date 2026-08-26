package com.chaoslab.execution.application.dto;

import java.util.Objects;

public record StartExperimentExecutionResult(
        ExperimentExecutionDetails execution,
        boolean created
) {

    public StartExperimentExecutionResult {
        Objects.requireNonNull(execution, "execution must not be null");
    }
}
