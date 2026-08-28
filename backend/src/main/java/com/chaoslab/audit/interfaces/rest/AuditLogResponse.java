package com.chaoslab.audit.interfaces.rest;

import com.chaoslab.audit.domain.AuditOperation;
import com.chaoslab.audit.domain.AuditResult;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

public record AuditLogResponse(
        UUID id,
        String actor,
        AuditOperation operation,
        UUID experimentId,
        UUID executionId,
        UUID targetId,
        String scenarioCode,
        JsonNode parameters,
        String sourceIp,
        AuditResult result,
        String failureCode,
        Instant occurredAt
) {
}