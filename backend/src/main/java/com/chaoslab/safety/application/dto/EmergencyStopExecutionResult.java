package com.chaoslab.safety.application.dto;

import java.util.Objects;
import java.util.UUID;

public record EmergencyStopExecutionResult(
        UUID experimentId,
        UUID executionId,
        EmergencyStopOutcome outcome
) {

    public EmergencyStopExecutionResult {
        Objects.requireNonNull(experimentId, "experimentId must not be null");
        Objects.requireNonNull(executionId, "executionId must not be null");
        Objects.requireNonNull(outcome, "outcome must not be null");
    }
}