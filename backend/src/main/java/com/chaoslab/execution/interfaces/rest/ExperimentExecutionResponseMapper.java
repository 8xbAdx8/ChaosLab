package com.chaoslab.execution.interfaces.rest;

import com.chaoslab.execution.application.dto.ExperimentExecutionDetails;
import org.springframework.stereotype.Component;

@Component
class ExperimentExecutionResponseMapper {

    ExperimentExecutionResponse from(ExperimentExecutionDetails details) {
        return new ExperimentExecutionResponse(
                details.id(),
                details.experimentId(),
                details.attempt(),
                details.idempotencyKey(),
                details.status(),
                details.engineExperimentId(),
                details.errorMessage(),
                details.createdAt(),
                details.startedAt(),
                details.finishedAt(),
                details.version()
        );
    }
}
