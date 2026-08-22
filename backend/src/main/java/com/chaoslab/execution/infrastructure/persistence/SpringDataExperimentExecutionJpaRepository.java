package com.chaoslab.execution.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

interface SpringDataExperimentExecutionJpaRepository
        extends JpaRepository<ExperimentExecutionJpaEntity, String> {

    Optional<ExperimentExecutionJpaEntity> findByExperimentIdAndIdempotencyKey(
            String experimentId,
            String idempotencyKey
    );

    Optional<ExperimentExecutionJpaEntity> findTopByExperimentIdOrderByAttemptDesc(
            String experimentId
    );
}
