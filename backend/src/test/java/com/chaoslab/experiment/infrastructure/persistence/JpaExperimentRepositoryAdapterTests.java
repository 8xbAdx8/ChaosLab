package com.chaoslab.experiment.infrastructure.persistence;

import com.chaoslab.experiment.application.port.ExperimentRepository;
import com.chaoslab.experiment.domain.Experiment;
import com.chaoslab.experiment.domain.ExperimentStatus;
import com.chaoslab.target.application.port.TargetRepository;
import com.chaoslab.target.domain.Target;
import com.chaoslab.target.domain.TargetEnvironment;
import com.chaoslab.target.domain.TargetType;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class JpaExperimentRepositoryAdapterTests {

    private static final UUID CPU_LOAD_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000101"
    );

    @Autowired
    private ExperimentRepository experimentRepository;

    @Autowired
    private TargetRepository targetRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    private UUID targetId;

    @BeforeEach
    void registerTarget() {
        targetId = UUID.randomUUID();
        targetRepository.save(Target.register(
                targetId,
                "payment-service-" + targetId,
                TargetType.JAVA_APPLICATION,
                TargetEnvironment.CHAOS_LAB
        ));
    }

    @Test
    void shouldApplyExperimentMigration() {
        Integer migrationCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '3' AND success = TRUE",
                Integer.class
        );

        assertThat(migrationCount).isEqualTo(1);
    }

    @Test
    void shouldInsertAndReloadExperiment() {
        Experiment experiment = experiment("payment CPU experiment");

        Experiment inserted = experimentRepository.insert(experiment);
        entityManager.clear();
        Experiment restored = experimentRepository.findById(inserted.getId()).orElseThrow();

        assertThat(restored.getTargetId()).isEqualTo(targetId);
        assertThat(restored.getScenarioId()).isEqualTo(CPU_LOAD_ID);
        assertThat(restored.getStatus()).isEqualTo(ExperimentStatus.CREATED);
        assertThat(restored.getVersion()).isZero();
        assertThat(restored.getParameters()).contains("\"percent\":40");
    }

    @Test
    void shouldReturnExperimentsInStableOrder() {
        experimentRepository.insert(experiment("payment experiment"));
        experimentRepository.insert(experiment("order experiment"));

        List<String> names = experimentRepository.findAll().stream()
                .map(Experiment::getName)
                .toList();

        assertThat(names).containsExactly("order experiment", "payment experiment");
    }

    @Test
    void shouldUpdateStatusWithOptimisticVersionIncrement() {
        Experiment inserted = experimentRepository.insert(
                experiment("payment validation experiment")
        );

        Experiment updated = experimentRepository.update(inserted.validate());
        entityManager.clear();
        Experiment restored = experimentRepository.findById(updated.getId()).orElseThrow();

        assertThat(restored.getStatus()).isEqualTo(ExperimentStatus.VALIDATED);
        assertThat(restored.getVersion()).isEqualTo(1);
    }

    @Test
    void shouldRejectUpdateUsingStaleVersion() {
        Experiment inserted = experimentRepository.insert(
                experiment("concurrent validation experiment")
        );
        entityManager.clear();
        Experiment firstRequest = experimentRepository.findById(inserted.getId())
                .orElseThrow();
        entityManager.clear();
        Experiment staleRequest = experimentRepository.findById(inserted.getId())
                .orElseThrow();
        entityManager.clear();
        experimentRepository.update(firstRequest.validate());
        entityManager.clear();

        assertThatThrownBy(() -> experimentRepository.update(staleRequest.validate()))
                .isInstanceOf(OptimisticLockingFailureException.class);
    }

    private Experiment experiment(String name) {
        return Experiment.create(
                UUID.randomUUID(),
                name,
                "Service remains available.",
                targetId,
                CPU_LOAD_ID,
                30,
                "{\"percent\":40}"
        );
    }
}
