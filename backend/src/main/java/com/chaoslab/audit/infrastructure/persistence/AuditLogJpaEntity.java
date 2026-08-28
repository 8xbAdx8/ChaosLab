package com.chaoslab.audit.infrastructure.persistence;

import com.chaoslab.audit.domain.AuditLog;
import com.chaoslab.audit.domain.AuditOperation;
import com.chaoslab.audit.domain.AuditResult;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Immutable
@Table(name = "audit_logs")
class AuditLogJpaEntity {

    @Id
    @Column(name = "id", nullable = false, length = 36, columnDefinition = "char(36)")
    private String id;

    @Column(name = "actor", nullable = false, length = AuditLog.MAX_ACTOR_LENGTH)
    private String actor;

    @Enumerated(EnumType.STRING)
    @Column(name = "operation", nullable = false, length = 64)
    private AuditOperation operation;

    @Column(name = "experiment_id", length = 36, columnDefinition = "char(36)")
    private String experimentId;

    @Column(name = "execution_id", length = 36, columnDefinition = "char(36)")
    private String executionId;

    @Column(name = "target_id", length = 36, columnDefinition = "char(36)")
    private String targetId;

    @Column(name = "scenario_code", length = AuditLog.MAX_SCENARIO_CODE_LENGTH)
    private String scenarioCode;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "parameters", columnDefinition = "json")
    private String parameters;

    @Column(name = "source_ip", length = AuditLog.MAX_SOURCE_IP_LENGTH)
    private String sourceIp;

    @Enumerated(EnumType.STRING)
    @Column(name = "result", nullable = false, length = 32)
    private AuditResult result;

    @Column(name = "failure_code", length = AuditLog.MAX_FAILURE_CODE_LENGTH)
    private String failureCode;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected AuditLogJpaEntity() {
    }

    private AuditLogJpaEntity(
            String id,
            String actor,
            AuditOperation operation,
            String experimentId,
            String executionId,
            String targetId,
            String scenarioCode,
            String parameters,
            String sourceIp,
            AuditResult result,
            String failureCode,
            Instant occurredAt
    ) {
        this.id = id;
        this.actor = actor;
        this.operation = operation;
        this.experimentId = experimentId;
        this.executionId = executionId;
        this.targetId = targetId;
        this.scenarioCode = scenarioCode;
        this.parameters = parameters;
        this.sourceIp = sourceIp;
        this.result = result;
        this.failureCode = failureCode;
        this.occurredAt = occurredAt;
    }

    static AuditLogJpaEntity from(AuditLog auditLog) {
        return new AuditLogJpaEntity(
                auditLog.id().toString(),
                auditLog.actor(),
                auditLog.operation(),
                text(auditLog.experimentId()),
                text(auditLog.executionId()),
                text(auditLog.targetId()),
                auditLog.scenarioCode(),
                auditLog.parameters(),
                auditLog.sourceIp(),
                auditLog.result(),
                auditLog.failureCode(),
                auditLog.occurredAt()
        );
    }

    AuditLog toDomain() {
        return new AuditLog(
                UUID.fromString(id),
                actor,
                operation,
                uuid(experimentId),
                uuid(executionId),
                uuid(targetId),
                scenarioCode,
                parameters,
                sourceIp,
                result,
                failureCode,
                occurredAt
        );
    }

    private static String text(UUID value) {
        return value == null ? null : value.toString();
    }

    private static UUID uuid(String value) {
        return value == null ? null : UUID.fromString(value);
    }
}
