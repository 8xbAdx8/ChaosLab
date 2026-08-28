package com.chaoslab.audit.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record AuditLog(
        UUID id,
        String actor,
        AuditOperation operation,
        UUID experimentId,
        UUID executionId,
        UUID targetId,
        String scenarioCode,
        String parameters,
        String sourceIp,
        AuditResult result,
        String failureCode,
        Instant occurredAt
) {

    public static final int MAX_ACTOR_LENGTH = 100;
    public static final int MAX_SCENARIO_CODE_LENGTH = 64;
    public static final int MAX_SOURCE_IP_LENGTH = 45;
    public static final int MAX_FAILURE_CODE_LENGTH = 100;

    public AuditLog {
        Objects.requireNonNull(id, "id must not be null");
        actor = requiredText(actor, "actor", MAX_ACTOR_LENGTH);
        Objects.requireNonNull(operation, "operation must not be null");
        scenarioCode = optionalText(
                scenarioCode,
                "scenarioCode",
                MAX_SCENARIO_CODE_LENGTH
        );
        parameters = optionalText(parameters, "parameters", Integer.MAX_VALUE);
        sourceIp = optionalText(sourceIp, "sourceIp", MAX_SOURCE_IP_LENGTH);
        Objects.requireNonNull(result, "result must not be null");
        failureCode = optionalText(
                failureCode,
                "failureCode",
                MAX_FAILURE_CODE_LENGTH
        );
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        if (result == AuditResult.SUCCESS && failureCode != null) {
            throw new IllegalArgumentException(
                    "successful audit log must not contain failureCode"
            );
        }
    }

    private static String requiredText(
            String value,
            String field,
            int maxLength
    ) {
        Objects.requireNonNull(value, field + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(
                    field + " must not exceed " + maxLength + " characters"
            );
        }
        return normalized;
    }

    private static String optionalText(
            String value,
            String field,
            int maxLength
    ) {
        if (value == null) {
            return null;
        }
        return requiredText(value, field, maxLength);
    }
}
