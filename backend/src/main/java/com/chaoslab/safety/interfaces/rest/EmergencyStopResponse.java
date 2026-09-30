package com.chaoslab.safety.interfaces.rest;

import java.time.Instant;
import java.util.List;

public record EmergencyStopResponse(
        Instant requestedAt,
        int candidateCount,
        int recoveredCount,
        int rollbackFailedCount,
        int processingFailedCount,
        int manualInterventionCount,
        List<EmergencyStopExecutionResponse> executions
) {
}
