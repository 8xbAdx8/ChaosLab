package com.chaoslab.target.infrastructure.persistence;

import com.chaoslab.target.application.port.TargetRepository;
import com.chaoslab.target.domain.Target;
import com.chaoslab.target.domain.TargetEnvironment;
import com.chaoslab.target.domain.TargetType;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class JpaTargetRepositoryAdapterTests {

    @Autowired
    private TargetRepository targetRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    @Test
    void shouldMigrateEmptyDatabase() {
        Integer migrationCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '1' AND success = TRUE",
                Integer.class
        );

        assertThat(migrationCount).isEqualTo(1);
    }

    @Test
    void shouldSaveAndRehydrateTarget() {
        UUID targetId = UUID.randomUUID();
        Target target = Target.register(
                targetId,
                "payment-service",
                TargetType.JAVA_APPLICATION,
                TargetEnvironment.CHAOS_LAB
        );
        target.disable();

        targetRepository.save(target);
        entityManager.flush();
        entityManager.clear();

        Target restored = targetRepository.findById(targetId).orElseThrow();
        assertThat(restored.getId()).isEqualTo(targetId);
        assertThat(restored.getName()).isEqualTo("payment-service");
        assertThat(restored.getType()).isEqualTo(TargetType.JAVA_APPLICATION);
        assertThat(restored.getEnvironment()).isEqualTo(TargetEnvironment.CHAOS_LAB);
        assertThat(restored.isEnabled()).isFalse();
    }

    @Test
    void shouldReturnTargetsInStableOrder() {
        Target second = Target.register(
                UUID.randomUUID(),
                "payment-service",
                TargetType.JAVA_APPLICATION,
                TargetEnvironment.TEST
        );
        Target first = Target.register(
                UUID.randomUUID(),
                "order-service",
                TargetType.JAVA_APPLICATION,
                TargetEnvironment.TEST
        );
        targetRepository.save(second);
        targetRepository.save(first);

        List<String> names = targetRepository.findAll().stream()
                .map(Target::getName)
                .toList();

        assertThat(names).containsExactly("order-service", "payment-service");
    }
}
