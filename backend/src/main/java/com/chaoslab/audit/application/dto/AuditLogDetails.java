package com.chaoslab.audit.application.dto;

import com.chaoslab.audit.domain.AuditLog;
import com.chaoslab.audit.domain.AuditOperation;
import com.chaoslab.audit.domain.AuditResult;

import java.time.Instant;
import java.util.UUID;

public record AuditLogDetails(
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

    public static AuditLogDetails from(AuditLog auditLog) {
        return new AuditLogDetails(
                auditLog.id(),
                auditLog.actor(),
                auditLog.operation(),
                auditLog.experimentId(),
                auditLog.executionId(),
                auditLog.targetId(),
                auditLog.scenarioCode(),
                auditLog.parameters(),
                auditLog.sourceIp(),
                auditLog.result(),
                auditLog.failureCode(),
                auditLog.occurredAt()
        );
    }
}
