package com.chaoslab.scenario.application;

import com.chaoslab.scenario.application.dto.FaultScenarioDetails;
import com.chaoslab.scenario.application.port.FaultScenarioRepository;
import com.chaoslab.scenario.domain.FaultScenario;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class FaultScenarioApplicationServiceTests {

    private final FaultScenarioRepository repository = mock(FaultScenarioRepository.class);
    private final FaultScenarioApplicationService service =
            new FaultScenarioApplicationService(repository);

    @Test
    void shouldReturnScenarioById() {
        FaultScenario scenario = scenario("CPU_LOAD");
        given(repository.findById(scenario.getId())).willReturn(Optional.of(scenario));

        FaultScenarioDetails details = service.findById(scenario.getId());

        assertThat(details.code()).isEqualTo("CPU_LOAD");
    }

    @Test
    void shouldRejectUnknownScenarioId() {
        UUID scenarioId = UUID.randomUUID();
        given(repository.findById(scenarioId)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.findById(scenarioId))
                .isInstanceOf(FaultScenarioNotFoundException.class)
                .hasMessage("fault scenario not found: " + scenarioId);
    }

    @Test
    void shouldReturnAllScenarios() {
        given(repository.findAll()).willReturn(List.of(
                scenario("CPU_LOAD"),
                scenario("NETWORK_DELAY")
        ));

        List<FaultScenarioDetails> scenarios = service.findAll();

        assertThat(scenarios)
                .extracting(FaultScenarioDetails::code)
                .containsExactly("CPU_LOAD", "NETWORK_DELAY");
    }

    private FaultScenario scenario(String code) {
        return FaultScenario.rehydrate(
                UUID.randomUUID(),
                code,
                code,
                "Bounded fault scenario.",
                "{\"type\":\"object\"}",
                true
        );
    }
}
