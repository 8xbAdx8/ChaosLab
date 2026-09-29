package com.chaoslab.report.infrastructure.persistence;

import com.chaoslab.report.application.port.ExperimentReportRepository;
import com.chaoslab.report.domain.ExperimentReport;
import com.chaoslab.report.domain.ReportWindow;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Repository
@Transactional(readOnly = true)
public class JpaExperimentReportRepositoryAdapter implements ExperimentReportRepository {

    private final SpringDataExperimentReportJpaRepository repository;
    private final EntityManager entityManager;
    private final ObjectMapper mapper;

    public JpaExperimentReportRepositoryAdapter(
            SpringDataExperimentReportJpaRepository repository,
            EntityManager entityManager,
            ObjectMapper mapper
    ) {
        this.repository = Objects.requireNonNull(repository);
        this.entityManager = Objects.requireNonNull(entityManager);
        this.mapper = Objects.requireNonNull(mapper);
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
        ExperimentReportJpaEntity entity = ExperimentReportJpaEntity.from(
                report, serialize(report.observations())
        );
        entityManager.persist(entity);
        entityManager.flush();
        // Return the stored snapshot, including database timestamp/JSON precision,
        // so the initial response matches later reads and idempotent replays.
        entityManager.detach(entity);
        return repository.findById(report.id().toString())
                .map(this::toDomain)
                .orElseThrow(() -> new IllegalStateException("stored report is missing"));
    }

    @Override
    public Optional<ExperimentReport> findById(UUID id) {
        return repository.findById(id.toString())
                .map(this::toDomain);
    }

    @Override
    public Optional<ExperimentReport> findByExecutionIdAndGenerationKey(
            UUID executionId, String generationKey
    ) {
        return repository.findByExecutionIdAndGenerationKey(
                        executionId.toString(), generationKey
                )
                .map(this::toDomain);
    }

    private ExperimentReport toDomain(ExperimentReportJpaEntity entity) {
        String json = entity.observationsJson();
        if (json == null) {
            return entity.toDomain(null);
        }
        try {
            ReportWindow[] windows = mapper.readValue(json, ReportWindow[].class);
            return entity.toDomain(List.copyOf(Arrays.asList(windows)));
        } catch (JacksonException exception) {
            throw new IllegalStateException("stored report observations are invalid", exception);
        }
    }

    private String serialize(List<ReportWindow> windows) {
        if (windows == null) {
            return null;
        }
        try {
            return mapper.writeValueAsString(windows);
        } catch (JacksonException exception) {
            throw new IllegalStateException("report observations cannot be serialized", exception);
        }
    }
}
