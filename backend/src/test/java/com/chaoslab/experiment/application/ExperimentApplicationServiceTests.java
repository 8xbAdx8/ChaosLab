package com.chaoslab.experiment.application;

import com.chaoslab.experiment.application.dto.CreateExperimentCommand;
import com.chaoslab.experiment.application.dto.ExperimentDetails;
import com.chaoslab.experiment.application.port.ExperimentRepository;
import com.chaoslab.experiment.domain.Experiment;
import com.chaoslab.experiment.domain.ExperimentStatus;
import com.chaoslab.scenario.application.FaultScenarioNotFoundException;
import com.chaoslab.scenario.application.port.FaultScenarioRepository;
import com.chaoslab.scenario.domain.FaultScenario;
import com.chaoslab.target.application.TargetNotFoundException;
import com.chaoslab.target.application.port.TargetRepository;
import com.chaoslab.target.domain.Target;
import com.chaoslab.target.domain.TargetEnvironment;
import com.chaoslab.target.domain.TargetType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class ExperimentApplicationServiceTests {

    private final ExperimentRepository experimentRepository = mock(ExperimentRepository.class);
    private final TargetRepository targetRepository = mock(TargetRepository.class);
    private final FaultScenarioRepository scenarioRepository = mock(FaultScenarioRepository.class);
    private final ExperimentApplicationService service = new ExperimentApplicationService(
            experimentRepository,
            targetRepository,
            scenarioRepository
    );

    @Test
    void shouldCreateExperimentForEnabledReferences() {
        Target target = target(true);
        FaultScenario scenario = scenario(true);
        CreateExperimentCommand command = command(target.getId(), scenario.getId());
        given(targetRepository.findById(target.getId())).willReturn(Optional.of(target));
        given(scenarioRepository.findById(scenario.getId())).willReturn(Optional.of(scenario));
        given(experimentRepository.insert(any(Experiment.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        ExperimentDetails details = service.create(command);

        assertThat(details.targetId()).isEqualTo(target.getId());
        assertThat(details.scenarioId()).isEqualTo(scenario.getId());
        assertThat(details.status()).isEqualTo(ExperimentStatus.CREATED);
        assertThat(details.version()).isZero();
    }

    @Test
    void shouldRejectUnknownTarget() {
        UUID targetId = UUID.randomUUID();
        CreateExperimentCommand command = command(targetId, UUID.randomUUID());
        given(targetRepository.findById(targetId)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(command))
                .isInstanceOf(TargetNotFoundException.class)
                .hasMessage("target not found: " + targetId);
    }

    @Test
    void shouldRejectDisabledTarget() {
        Target target = target(false);
        CreateExperimentCommand command = command(target.getId(), UUID.randomUUID());
        given(targetRepository.findById(target.getId())).willReturn(Optional.of(target));

        assertThatThrownBy(() -> service.create(command))
                .isInstanceOf(ExperimentCreationRejectedException.class)
                .hasMessage("target is disabled: " + target.getId())
                .extracting("code")
                .isEqualTo("TARGET_DISABLED");
    }

    @Test
    void shouldRejectUnknownScenario() {
        Target target = target(true);
        UUID scenarioId = UUID.randomUUID();
        CreateExperimentCommand command = command(target.getId(), scenarioId);
        given(targetRepository.findById(target.getId())).willReturn(Optional.of(target));
        given(scenarioRepository.findById(scenarioId)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(command))
                .isInstanceOf(FaultScenarioNotFoundException.class)
                .hasMessage("fault scenario not found: " + scenarioId);
    }

    @Test
    void shouldRejectDisabledScenario() {
        Target target = target(true);
        FaultScenario scenario = scenario(false);
        CreateExperimentCommand command = command(target.getId(), scenario.getId());
        given(targetRepository.findById(target.getId())).willReturn(Optional.of(target));
        given(scenarioRepository.findById(scenario.getId())).willReturn(Optional.of(scenario));

        assertThatThrownBy(() -> service.create(command))
                .isInstanceOf(ExperimentCreationRejectedException.class)
                .hasMessage("fault scenario is disabled: " + scenario.getId())
                .extracting("code")
                .isEqualTo("SCENARIO_DISABLED");
    }

    @Test
    void shouldReturnAllExperiments() {
        Experiment experiment = experiment();
        given(experimentRepository.findAll()).willReturn(List.of(experiment));

        List<ExperimentDetails> experiments = service.findAll();

        assertThat(experiments)
                .extracting(ExperimentDetails::id)
                .containsExactly(experiment.getId());
    }

    private CreateExperimentCommand command(UUID targetId, UUID scenarioId) {
        return new CreateExperimentCommand(
                "payment CPU experiment",
                "Service remains available.",
                targetId,
                scenarioId,
                30,
                "{\"percent\":40}"
        );
    }

    private Target target(boolean enabled) {
        Target target = Target.register(
                UUID.randomUUID(),
                "payment-service",
                TargetType.JAVA_APPLICATION,
                TargetEnvironment.CHAOS_LAB
        );
        if (!enabled) {
            target.disable();
        }
        return target;
    }

    private FaultScenario scenario(boolean enabled) {
        return FaultScenario.rehydrate(
                UUID.randomUUID(),
                "CPU_LOAD",
                "CPU Load",
                "Consumes bounded CPU.",
                "{\"type\":\"object\"}",
                enabled
        );
    }

    private Experiment experiment() {
        return Experiment.create(
                UUID.randomUUID(),
                "payment CPU experiment",
                "Service remains available.",
                UUID.randomUUID(),
                UUID.randomUUID(),
                30,
                "{\"percent\":40}"
        );
    }
}
