package com.chaoslab.execution.application.dto;

import com.chaoslab.execution.domain.ExperimentExecution;
import com.chaoslab.execution.domain.ExperimentExecutionStatus;

import java.time.Instant;
import java.util.UUID;

public record ExperimentExecutionDetails(
        UUID id,
        UUID experimentId,
        int attempt,
        String idempotencyKey,
        ExperimentExecutionStatus status,
        String engineExperimentId,
        String errorMessage,
        Instant createdAt,
        Instant startedAt,
        long version
) {

    public static ExperimentExecutionDetails from(ExperimentExecution execution) {
        return new ExperimentExecutionDetails(
                execution.getId(),
                execution.getExperimentId(),
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
}
