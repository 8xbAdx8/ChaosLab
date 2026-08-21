package com.chaoslab.scenario.domain;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FaultScenarioTests {

    @Test
    void shouldRehydrateScenario() {
        UUID id = UUID.randomUUID();

        FaultScenario scenario = FaultScenario.rehydrate(
                id,
                "CPU_LOAD",
                "CPU Load",
                "Consumes bounded CPU.",
                "{\"type\":\"object\"}",
                true
        );

        assertThat(scenario.getId()).isEqualTo(id);
        assertThat(scenario.getCode()).isEqualTo("CPU_LOAD");
        assertThat(scenario.isEnabled()).isTrue();
    }

    @Test
    void shouldRejectInvalidCode() {
        assertThatThrownBy(() -> FaultScenario.rehydrate(
                UUID.randomUUID(),
                "cpu-load",
                "CPU Load",
                "Consumes bounded CPU.",
                "{\"type\":\"object\"}",
                true
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("code must use uppercase letters, digits, and underscores");
    }

    @Test
    void shouldRejectBlankParameterSchema() {
        assertThatThrownBy(() -> FaultScenario.rehydrate(
                UUID.randomUUID(),
                "CPU_LOAD",
                "CPU Load",
                "Consumes bounded CPU.",
                " ",
                true
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("parameterSchema must not be blank");
    }
}
