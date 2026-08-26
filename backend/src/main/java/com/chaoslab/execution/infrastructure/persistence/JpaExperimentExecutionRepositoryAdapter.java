package com.chaoslab.execution.infrastructure.persistence;

import com.chaoslab.execution.application.port.ExperimentExecutionRepository;
import com.chaoslab.execution.domain.ExperimentExecution;
import com.chaoslab.execution.domain.ExperimentExecutionStatus;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Repository
@Transactional(readOnly = true)
public class JpaExperimentExecutionRepositoryAdapter
        implements ExperimentExecutionRepository {

    private final SpringDataExperimentExecutionJpaRepository repository;
    private final EntityManager entityManager;

    public JpaExperimentExecutionRepositoryAdapter(
            SpringDataExperimentExecutionJpaRepository repository,
            EntityManager entityManager
    ) {
        this.repository = repository;
        this.entityManager = entityManager;
    }

    @Override
    @Transactional
    public ExperimentExecution insert(ExperimentExecution execution) {
        Objects.requireNonNull(execution, "execution must not be null");
        ExperimentExecutionJpaEntity entity = ExperimentExecutionJpaEntity.from(execution);
        entityManager.persist(entity);
        entityManager.flush();
        return entity.toDomain();
    }

    @Override
    @Transactional
    public ExperimentExecution update(ExperimentExecution execution) {
        Objects.requireNonNull(execution, "execution must not be null");
        ExperimentExecutionJpaEntity entity = repository.saveAndFlush(
                ExperimentExecutionJpaEntity.from(execution)
        );
        return entity.toDomain();
    }

    @Override
    public Optional<ExperimentExecution> findById(UUID id) {
        Objects.requireNonNull(id, "id must not be null");
        return repository.findById(id.toString())
                .map(ExperimentExecutionJpaEntity::toDomain);
    }

    @Override
    public Optional<ExperimentExecution> findByExperimentIdAndIdempotencyKey(
            UUID experimentId,
            String idempotencyKey
    ) {
        Objects.requireNonNull(experimentId, "experimentId must not be null");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        return repository.findByExperimentIdAndIdempotencyKey(
                        experimentId.toString(),
                        idempotencyKey
                )
                .map(ExperimentExecutionJpaEntity::toDomain);
    }

    @Override
    public Optional<ExperimentExecution> findLatestByExperimentId(UUID experimentId) {
        Objects.requireNonNull(experimentId, "experimentId must not be null");
        return repository.findTopByExperimentIdOrderByAttemptDesc(experimentId.toString())
                .map(ExperimentExecutionJpaEntity::toDomain);
    }

    @Override
    public List<ExperimentExecution> findAllByStatus(
            ExperimentExecutionStatus status
    ) {
        Objects.requireNonNull(status, "status must not be null");
        return repository.findAllByStatusOrderByStartedAtAsc(status).stream()
                .map(ExperimentExecutionJpaEntity::toDomain)
                .toList();
    }}
