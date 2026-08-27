package com.chaoslab.safety.application.dto;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record EmergencyStopResult(
        Instant requestedAt,
        List<EmergencyStopExecutionResult> executions
) {

    public EmergencyStopResult {
        Objects.requireNonNull(requestedAt, "requestedAt must not be null");
        Objects.requireNonNull(executions, "executions must not be null");
        executions = List.copyOf(executions);
    }
}