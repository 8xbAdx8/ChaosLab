package com.chaoslab.report.application.port;

import com.chaoslab.report.domain.ExperimentReport;

import java.util.Optional;
import java.util.UUID;

public interface ExperimentReportRepository {
    void lockExecution(UUID executionId);

    ExperimentReport insert(ExperimentReport report);

    Optional<ExperimentReport> findById(UUID id);

    Optional<ExperimentReport> findByExecutionIdAndGenerationKey(
            UUID executionId, String generationKey
    );
}
