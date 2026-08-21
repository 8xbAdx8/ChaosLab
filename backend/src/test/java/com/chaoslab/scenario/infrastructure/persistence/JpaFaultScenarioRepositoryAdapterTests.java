package com.chaoslab.scenario.infrastructure.persistence;

import com.chaoslab.scenario.application.port.FaultScenarioRepository;
import com.chaoslab.scenario.domain.FaultScenario;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class JpaFaultScenarioRepositoryAdapterTests {

    private static final UUID CPU_LOAD_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000101"
    );

    @Autowired
    private FaultScenarioRepository faultScenarioRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldApplyScenarioMigration() {
        Integer migrationCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '2' AND success = TRUE",
                Integer.class
        );

        assertThat(migrationCount).isEqualTo(1);
    }

    @Test
    void shouldLoadSeededScenarioById() {
        FaultScenario scenario = faultScenarioRepository.findById(CPU_LOAD_ID).orElseThrow();

        assertThat(scenario.getCode()).isEqualTo("CPU_LOAD");
        assertThat(scenario.getParameterSchema()).contains("\"maximum\":80");
        assertThat(scenario.isEnabled()).isTrue();
    }

    @Test
    void shouldReturnSeededScenariosInCodeOrder() {
        List<String> codes = faultScenarioRepository.findAll().stream()
                .map(FaultScenario::getCode)
                .toList();

        assertThat(codes).containsExactly("CPU_LOAD", "NETWORK_DELAY");
    }
}
