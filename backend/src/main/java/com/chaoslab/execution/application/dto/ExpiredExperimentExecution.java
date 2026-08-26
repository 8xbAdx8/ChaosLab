package com.chaoslab.execution.application.dto;

import java.util.Objects;
import java.util.UUID;

public record ExpiredExperimentExecution(
        UUID experimentId,
        UUID executionId
) {

    public ExpiredExperimentExecution {
        Objects.requireNonNull(experimentId, "experimentId must not be null");
        Objects.requireNonNull(executionId, "executionId must not be null");
    }
}