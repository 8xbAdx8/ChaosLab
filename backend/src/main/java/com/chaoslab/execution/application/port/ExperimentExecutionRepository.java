package com.chaoslab.execution.application.port;

import com.chaoslab.execution.domain.ExperimentExecution;
import com.chaoslab.execution.domain.ExperimentExecutionStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ExperimentExecutionRepository {

    ExperimentExecution insert(ExperimentExecution execution);

    ExperimentExecution update(ExperimentExecution execution);

    Optional<ExperimentExecution> findById(UUID id);

    Optional<ExperimentExecution> findByExperimentIdAndIdempotencyKey(
            UUID experimentId,
            String idempotencyKey
    );

    Optional<ExperimentExecution> findLatestByExperimentId(UUID experimentId);

    List<ExperimentExecution> findAllByStatus(ExperimentExecutionStatus status);
}
