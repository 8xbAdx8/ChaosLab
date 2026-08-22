package com.chaoslab.execution.infrastructure.persistence;

import com.chaoslab.execution.domain.ExperimentExecution;
import com.chaoslab.execution.domain.ExperimentExecutionStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "experiment_executions")
class ExperimentExecutionJpaEntity {

    @Id
    @Column(name = "id", nullable = false, length = 36, columnDefinition = "char(36)")
    private String id;

    @Column(
            name = "experiment_id",
            nullable = false,
            length = 36,
            columnDefinition = "char(36)"
    )
    private String experimentId;

    @Column(name = "attempt", nullable = false)
    private int attempt;

    @Column(
            name = "idempotency_key",
            nullable = false,
            length = ExperimentExecution.MAX_IDEMPOTENCY_KEY_LENGTH
    )
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private ExperimentExecutionStatus status;

    @Column(
            name = "engine_experiment_id",
            length = ExperimentExecution.MAX_ENGINE_EXPERIMENT_ID_LENGTH
    )
    private String engineExperimentId;

    @Column(
            name = "error_message",
            length = ExperimentExecution.MAX_ERROR_MESSAGE_LENGTH
    )
    private String errorMessage;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected ExperimentExecutionJpaEntity() {
    }

    private ExperimentExecutionJpaEntity(
            String id,
            String experimentId,
            int attempt,
            String idempotencyKey,
            ExperimentExecutionStatus status,
            String engineExperimentId,
            String errorMessage,
            Instant createdAt,
            Instant startedAt,
            long version
    ) {
        this.id = id;
        this.experimentId = experimentId;
        this.attempt = attempt;
        this.idempotencyKey = idempotencyKey;
        this.status = status;
        this.engineExperimentId = engineExperimentId;
        this.errorMessage = errorMessage;
        this.createdAt = createdAt;
        this.startedAt = startedAt;
        this.version = version;
    }

    static ExperimentExecutionJpaEntity from(ExperimentExecution execution) {
        return new ExperimentExecutionJpaEntity(
                execution.getId().toString(),
                execution.getExperimentId().toString(),
                execution.getAttempt(),
                execution.getIdempotencyKey(),
                execution.getStatus(),
                execution.getEngineExperimentId(),
                execution.getErrorMessage(),
                execution.getCreatedAt(),
                execution.getStartedAt(),
                execution.getVersion()
        );
    }

    ExperimentExecution toDomain() {
        return ExperimentExecution.rehydrate(
                UUID.fromString(id),
                UUID.fromString(experimentId),
                attempt,
                idempotencyKey,
                status,
                engineExperimentId,
                errorMessage,
                createdAt,
                startedAt,
                version
        );
    }
}
