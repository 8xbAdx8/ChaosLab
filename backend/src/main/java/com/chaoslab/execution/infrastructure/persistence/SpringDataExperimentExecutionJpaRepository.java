package com.chaoslab.execution.infrastructure.persistence;

import com.chaoslab.execution.domain.ExperimentExecutionStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
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

    List<ExperimentExecutionJpaEntity> findAllByStatusOrderByStartedAtAsc(
            ExperimentExecutionStatus status
    );

    List<ExperimentExecutionJpaEntity> findAllByStatusInOrderByStartedAtAsc(
            Collection<ExperimentExecutionStatus> statuses
    );
}
