package com.chaoslab.execution.infrastructure.persistence;

import com.chaoslab.execution.application.port.ExperimentExecutionRepository;
import com.chaoslab.execution.domain.ExperimentExecution;
import com.chaoslab.execution.domain.ExperimentExecutionStatus;
import com.chaoslab.experiment.application.port.ExperimentRepository;
import com.chaoslab.experiment.domain.Experiment;
import com.chaoslab.target.application.port.TargetRepository;
import com.chaoslab.target.domain.Target;
import com.chaoslab.target.domain.TargetEnvironment;
import com.chaoslab.target.domain.TargetType;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class JpaExperimentExecutionRepositoryAdapterTests {

    private static final UUID CPU_LOAD_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000101"
    );
    private static final Instant CREATED_AT = Instant.parse("2026-08-22T00:00:00Z");

    @Autowired
    private ExperimentExecutionRepository executionRepository;

    @Autowired
    private ExperimentRepository experimentRepository;

    @Autowired
    private TargetRepository targetRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    private UUID targetId;
    private UUID experimentId;

    @BeforeEach
    void createValidatedExperiment() {
        targetId = UUID.randomUUID();
        targetRepository.save(Target.register(
                targetId,
                "execution-target-" + targetId,
                TargetType.JAVA_APPLICATION,
                TargetEnvironment.CHAOS_LAB
        ));
        Experiment experiment = Experiment.create(
                UUID.randomUUID(),
                "execution persistence experiment",
                "Service remains available.",
                targetId,
                CPU_LOAD_ID,
                30,
                "{\"percent\":40}"
        ).validate();
        experimentId = experimentRepository.insert(experiment).getId();
    }

    @Test
    void shouldApplyExecutionMigration() {
        Integer migrationCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '4' AND success = TRUE",
                Integer.class
        );

        assertThat(migrationCount).isEqualTo(1);
    }

    @Test
    void shouldApplyAutomaticRecoveryMigration() {
        Integer migrationCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history "
                        + "WHERE version = '5' AND success = TRUE",
                Integer.class
        );

        assertThat(migrationCount).isEqualTo(1);
    }
    @Test
    void shouldInsertAndReloadPreparingExecution() {
        ExperimentExecution inserted = executionRepository.insert(execution(
                1,
                "request-001"
        ));
        entityManager.clear();

        ExperimentExecution restored = executionRepository.findById(inserted.getId())
                .orElseThrow();

        assertThat(restored.getExperimentId()).isEqualTo(experimentId);
        assertThat(restored.getAttempt()).isEqualTo(1);
        assertThat(restored.getStatus()).isEqualTo(ExperimentExecutionStatus.PREPARING);
        assertThat(restored.getVersion()).isZero();
        assertThat(restored.getCreatedAt()).isEqualTo(CREATED_AT);
    }

    @Test
    void shouldPersistRunningResultAndIncrementVersion() {
        ExperimentExecution inserted = executionRepository.insert(execution(
                1,
                "request-001"
        ));
        ExperimentExecution updated = executionRepository.update(inserted.markRunning(
                "fake-" + inserted.getId(),
                CREATED_AT.plusSeconds(1)
        ));
        entityManager.clear();

        ExperimentExecution restored = executionRepository.findById(updated.getId())
                .orElseThrow();

        assertThat(restored.getStatus()).isEqualTo(ExperimentExecutionStatus.RUNNING);
        assertThat(restored.getEngineExperimentId()).isEqualTo("fake-" + inserted.getId());
        assertThat(restored.getVersion()).isEqualTo(1);
    }

    @Test
    void shouldFindIdempotentRequestAndLatestAttempt() {
        ExperimentExecution first = executionRepository.insert(execution(
                1,
                "request-001"
        ));
        ExperimentExecution second = executionRepository.insert(execution(
                2,
                "request-002"
        ));
        entityManager.clear();

        ExperimentExecution idempotent = executionRepository
                .findByExperimentIdAndIdempotencyKey(experimentId, "request-001")
                .orElseThrow();
        ExperimentExecution latest = executionRepository
                .findLatestByExperimentId(experimentId)
                .orElseThrow();

        assertThat(idempotent.getId()).isEqualTo(first.getId());
        assertThat(latest.getId()).isEqualTo(second.getId());
    }

    @Test
    void shouldFindRunningExecutionAndPersistSuccessfulRecovery() {
        ExperimentExecution inserted = executionRepository.insert(execution(
                1,
                "request-001"
        ));
        ExperimentExecution running = executionRepository.update(
                inserted.markRunning(
                        "fake-" + inserted.getId(),
                        CREATED_AT.plusSeconds(1)
                )
        );
        entityManager.clear();

        assertThat(executionRepository.findAllByStatus(
                ExperimentExecutionStatus.RUNNING
        )).extracting(ExperimentExecution::getId)
                .containsExactly(running.getId());
        assertThat(executionRepository.findAllByStatuses(java.util.List.of(
                ExperimentExecutionStatus.RUNNING,
                ExperimentExecutionStatus.ROLLBACK_FAILED
        ))).extracting(ExperimentExecution::getId)
                .containsExactly(running.getId());
        assertThat(executionRepository.existsByTargetIdAndStatuses(
                targetId,
                java.util.List.of(
                        ExperimentExecutionStatus.PREPARING,
                        ExperimentExecutionStatus.RUNNING,
                        ExperimentExecutionStatus.DESTROYING,
                        ExperimentExecutionStatus.ROLLBACK_FAILED
                )
        )).isTrue();

        ExperimentExecution destroying = executionRepository.update(
                running.beginDestroy()
        );
        ExperimentExecution successful = executionRepository.update(
                destroying.markSuccess(CREATED_AT.plusSeconds(31))
        );
        entityManager.clear();

        ExperimentExecution restored = executionRepository.findById(
                successful.getId()
        ).orElseThrow();
        assertThat(restored.getStatus())
                .isEqualTo(ExperimentExecutionStatus.SUCCESS);
        assertThat(restored.getFinishedAt())
                .isEqualTo(CREATED_AT.plusSeconds(31));
        assertThat(restored.getVersion()).isEqualTo(3);
        assertThat(executionRepository.existsByTargetIdAndStatuses(
                targetId,
                java.util.List.of(
                        ExperimentExecutionStatus.PREPARING,
                        ExperimentExecutionStatus.RUNNING,
                        ExperimentExecutionStatus.DESTROYING,
                        ExperimentExecutionStatus.ROLLBACK_FAILED
                )
        )).isFalse();
    }

    private ExperimentExecution execution(int attempt, String idempotencyKey) {
        return ExperimentExecution.prepare(
                UUID.randomUUID(),
                experimentId,
                attempt,
                idempotencyKey,
                CREATED_AT.plusSeconds(attempt - 1L)
        );
    }
}
