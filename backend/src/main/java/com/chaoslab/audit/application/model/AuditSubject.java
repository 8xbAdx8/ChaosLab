package com.chaoslab.audit.application.model;

import java.util.UUID;

public record AuditSubject(
        UUID experimentId,
        UUID executionId,
        UUID targetId,
        String scenarioCode,
        String parameters
) {
}
