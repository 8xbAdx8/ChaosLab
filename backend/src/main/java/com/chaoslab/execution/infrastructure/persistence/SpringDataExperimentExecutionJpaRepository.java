package com.chaoslab.execution.infrastructure.persistence;

import com.chaoslab.execution.domain.ExperimentExecutionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    @Query(value = """
            SELECT COUNT(*)
            FROM experiment_executions ee
            INNER JOIN experiments e ON e.id = ee.experiment_id
            WHERE e.target_id = :targetId
              AND ee.status IN (:statuses)
            """, nativeQuery = true)
    long countByTargetIdAndStatuses(
            @Param("targetId") String targetId,
            @Param("statuses") Collection<String> statuses
    );

    long countByStatusIn(Collection<ExperimentExecutionStatus> statuses);
}
