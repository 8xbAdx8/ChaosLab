package com.chaoslab.report.infrastructure.persistence;

import com.chaoslab.report.application.port.ExperimentReportRepository;
import com.chaoslab.report.domain.ExperimentReport;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Repository
@Transactional(readOnly = true)
public class JpaExperimentReportRepositoryAdapter implements ExperimentReportRepository {

    private final SpringDataExperimentReportJpaRepository repository;
    private final EntityManager entityManager;

    public JpaExperimentReportRepositoryAdapter(
            SpringDataExperimentReportJpaRepository repository,
            EntityManager entityManager
    ) {
        this.repository = Objects.requireNonNull(repository);
        this.entityManager = Objects.requireNonNull(entityManager);
    }

    @Override
    @Transactional
    public void lockExecution(UUID executionId) {
        entityManager.createNativeQuery(
                        "SELECT id FROM experiment_executions WHERE id = :id FOR UPDATE"
                )
                .setParameter("id", executionId.toString())
                .getResultList();
    }

    @Override
    @Transactional
    public ExperimentReport insert(ExperimentReport report) {
        ExperimentReportJpaEntity entity = ExperimentReportJpaEntity.from(report);
        entityManager.persist(entity);
        entityManager.flush();
        return entity.toDomain();
    }

    @Override
    public Optional<ExperimentReport> findById(UUID id) {
        return repository.findById(id.toString())
                .map(ExperimentReportJpaEntity::toDomain);
    }

    @Override
    public Optional<ExperimentReport> findByExecutionIdAndGenerationKey(
            UUID executionId, String generationKey
    ) {
        return repository.findByExecutionIdAndGenerationKey(
                        executionId.toString(), generationKey
                )
                .map(ExperimentReportJpaEntity::toDomain);
    }
}
