package com.chaoslab.execution.interfaces.rest;

import com.chaoslab.execution.domain.ExperimentExecutionStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExperimentExecutionResponse(
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
}
