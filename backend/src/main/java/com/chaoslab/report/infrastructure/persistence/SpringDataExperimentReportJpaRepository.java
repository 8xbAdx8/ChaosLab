package com.chaoslab.report.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

interface SpringDataExperimentReportJpaRepository
        extends JpaRepository<ExperimentReportJpaEntity, String> {

    Optional<ExperimentReportJpaEntity> findByExecutionIdAndGenerationKey(
            String executionId, String generationKey
    );
}
