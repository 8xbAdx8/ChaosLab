package com.chaoslab.safety.interfaces.rest;

import com.chaoslab.safety.application.dto.EmergencyStopOutcome;

import java.util.UUID;

public record EmergencyStopExecutionResponse(
        UUID experimentId,
        UUID executionId,
        EmergencyStopOutcome outcome
) {
}