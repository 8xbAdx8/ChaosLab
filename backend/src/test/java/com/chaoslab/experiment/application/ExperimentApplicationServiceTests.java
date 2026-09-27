package com.chaoslab.experiment.application;

import com.chaoslab.experiment.application.dto.CreateExperimentCommand;
import com.chaoslab.experiment.application.dto.ExperimentDetails;
import com.chaoslab.experiment.application.dto.ExperimentDryRunDetails;
import com.chaoslab.experiment.application.port.ExperimentParameterValidator;
import com.chaoslab.experiment.application.port.ExperimentRepository;
import com.chaoslab.experiment.application.validation.ParameterViolation;
import com.chaoslab.experiment.domain.Experiment;
import com.chaoslab.experiment.domain.ExperimentStatus;
import com.chaoslab.safety.infrastructure.policy.DefaultSafetyGuard;
import com.chaoslab.safety.application.model.TargetIdentityVerification;
import com.chaoslab.engine.infrastructure.fake.FakeChaosEngine;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class ExperimentApplicationServiceTests {

    private final ExperimentRepository experimentRepository = mock(ExperimentRepository.class);
    private final TargetRepository targetRepository = mock(TargetRepository.class);
    private final FaultScenarioRepository scenarioRepository = mock(FaultScenarioRepository.class);
    private final ExperimentParameterValidator parameterValidator =
            mock(ExperimentParameterValidator.class);
    private final ExperimentApplicationService service = new ExperimentApplicationService(
            experimentRepository,
            targetRepository,
            scenarioRepository,
            parameterValidator,
            new DefaultSafetyGuard(
                    target -> TargetIdentityVerification.rejected("not configured"),
                    new FakeChaosEngine()
            )
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

    @Test
    void shouldValidateExperimentWhenParametersMatchScenarioSchema() {
        Target target = target(true);
        FaultScenario scenario = scenario(true);
        Experiment experiment = experiment(target.getId(), scenario.getId());
        given(experimentRepository.findById(experiment.getId()))
                .willReturn(Optional.of(experiment));
        given(targetRepository.findById(experiment.getTargetId()))
                .willReturn(Optional.of(target));
        given(scenarioRepository.findById(experiment.getScenarioId()))
                .willReturn(Optional.of(scenario));
        given(parameterValidator.validate(
                scenario.getParameterSchema(),
                experiment.getParameters()
        )).willReturn(List.of());
        given(experimentRepository.update(any(Experiment.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        ExperimentDetails details = service.validate(experiment.getId());

        assertThat(details.status()).isEqualTo(ExperimentStatus.VALIDATED);
        verify(experimentRepository).update(any(Experiment.class));
    }

    @Test
    void shouldRejectParametersThatDoNotMatchScenarioSchema() {
        Target target = target(true);
        FaultScenario scenario = scenario(true);
        Experiment experiment = experiment(target.getId(), scenario.getId());
        ParameterViolation violation = new ParameterViolation(
                "/percent",
                "maximum",
                "must have a maximum value of 80"
        );
        given(experimentRepository.findById(experiment.getId()))
                .willReturn(Optional.of(experiment));
        given(targetRepository.findById(experiment.getTargetId()))
                .willReturn(Optional.of(target));
        given(scenarioRepository.findById(experiment.getScenarioId()))
                .willReturn(Optional.of(scenario));
        given(parameterValidator.validate(
                scenario.getParameterSchema(),
                experiment.getParameters()
        )).willReturn(List.of(violation));

        assertThatThrownBy(() -> service.validate(experiment.getId()))
                .isInstanceOf(ExperimentParametersInvalidException.class)
                .hasMessage("experiment parameters do not match the fault scenario schema")
                .extracting("violations")
                .isEqualTo(List.of(violation));
        verify(experimentRepository, never()).update(any(Experiment.class));
    }

    @Test
    void shouldTreatValidationOfValidatedExperimentAsIdempotent() {
        Experiment validated = experiment().validate();
        given(experimentRepository.findById(validated.getId()))
                .willReturn(Optional.of(validated));

        ExperimentDetails details = service.validate(validated.getId());

        assertThat(details.status()).isEqualTo(ExperimentStatus.VALIDATED);
        verify(experimentRepository, never()).update(any(Experiment.class));
        verifyNoInteractions(targetRepository, scenarioRepository, parameterValidator);
    }

    @Test
    void shouldTreatValidationOfReadyExperimentAsIdempotent() {
        Experiment ready = experiment().validate().ready();
        given(experimentRepository.findById(ready.getId())).willReturn(Optional.of(ready));

        ExperimentDetails details = service.validate(ready.getId());

        assertThat(details.status()).isEqualTo(ExperimentStatus.READY);
        verify(experimentRepository, never()).update(any(Experiment.class));
        verifyNoInteractions(targetRepository, scenarioRepository, parameterValidator);
    }
    @Test
    void shouldRejectValidationWhenTargetWasDisabledAfterCreation() {
        Target disabledTarget = target(false);
        FaultScenario scenario = scenario(true);
        Experiment experiment = experiment(disabledTarget.getId(), scenario.getId());
        given(experimentRepository.findById(experiment.getId()))
                .willReturn(Optional.of(experiment));
        given(targetRepository.findById(disabledTarget.getId()))
                .willReturn(Optional.of(disabledTarget));

        assertThatThrownBy(() -> service.validate(experiment.getId()))
                .isInstanceOf(ExperimentValidationRejectedException.class)
                .hasMessage("target is disabled: " + disabledTarget.getId())
                .extracting("code")
                .isEqualTo("TARGET_DISABLED");
        verifyNoInteractions(scenarioRepository, parameterValidator);
        verify(experimentRepository, never()).update(any(Experiment.class));
    }

    @Test
    void shouldMoveValidatedExperimentToReadyWhenDryRunIsAccepted() {
        Target target = target(true);
        FaultScenario scenario = scenario(true);
        Experiment validated = experiment(target.getId(), scenario.getId()).validate();
        given(experimentRepository.findById(validated.getId()))
                .willReturn(Optional.of(validated));
        given(targetRepository.findById(target.getId())).willReturn(Optional.of(target));
        given(scenarioRepository.findById(scenario.getId()))
                .willReturn(Optional.of(scenario));
        given(experimentRepository.update(any(Experiment.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        ExperimentDryRunDetails details = service.dryRun(validated.getId());

        assertThat(details.decision().accepted()).isTrue();
        assertThat(details.experiment().status()).isEqualTo(ExperimentStatus.READY);
        assertThat(details.decision().plan().targetCount()).isEqualTo(1);
        assertThat(details.decision().plan().recoveryWithinSeconds()).isEqualTo(30);
        verify(experimentRepository).update(any(Experiment.class));
        verifyNoInteractions(parameterValidator);
    }

    @Test
    void shouldKeepValidatedStatusWhenDryRunRejectsProductionTarget() {
        Target target = target(TargetEnvironment.PRODUCTION, true);
        FaultScenario scenario = scenario(true);
        Experiment validated = experiment(target.getId(), scenario.getId()).validate();
        given(experimentRepository.findById(validated.getId()))
                .willReturn(Optional.of(validated));
        given(targetRepository.findById(target.getId())).willReturn(Optional.of(target));
        given(scenarioRepository.findById(scenario.getId()))
                .willReturn(Optional.of(scenario));

        ExperimentDryRunDetails details = service.dryRun(validated.getId());

        assertThat(details.decision().accepted()).isFalse();
        assertThat(details.experiment().status()).isEqualTo(ExperimentStatus.VALIDATED);
        assertThat(details.decision().checks())
                .filteredOn(check -> check.code().equals("ENVIRONMENT_ALLOWED"))
                .singleElement()
                .extracting("passed")
                .isEqualTo(false);
        verify(experimentRepository, never()).update(any(Experiment.class));
    }

    @Test
    void shouldKeepValidatedStatusWhenDurationExceedsSafetyLimit() {
        Target target = target(true);
        FaultScenario scenario = scenario(true);
        Experiment validated = experiment(target.getId(), scenario.getId(), 60).validate();
        given(experimentRepository.findById(validated.getId()))
                .willReturn(Optional.of(validated));
        given(targetRepository.findById(target.getId())).willReturn(Optional.of(target));
        given(scenarioRepository.findById(scenario.getId()))
                .willReturn(Optional.of(scenario));

        ExperimentDryRunDetails details = service.dryRun(validated.getId());

        assertThat(details.decision().accepted()).isFalse();
        assertThat(details.experiment().status()).isEqualTo(ExperimentStatus.VALIDATED);
        assertThat(details.decision().checks())
                .filteredOn(check -> check.code().equals("DURATION_WITHIN_LIMIT"))
                .singleElement()
                .extracting("passed")
                .isEqualTo(false);
        verify(experimentRepository, never()).update(any(Experiment.class));
    }

    @Test
    void shouldTreatRepeatedAcceptedDryRunAsIdempotent() {
        Target target = target(true);
        FaultScenario scenario = scenario(true);
        Experiment ready = experiment(target.getId(), scenario.getId())
                .validate()
                .ready();
        given(experimentRepository.findById(ready.getId())).willReturn(Optional.of(ready));
        given(targetRepository.findById(target.getId())).willReturn(Optional.of(target));
        given(scenarioRepository.findById(scenario.getId()))
                .willReturn(Optional.of(scenario));

        ExperimentDryRunDetails details = service.dryRun(ready.getId());

        assertThat(details.decision().accepted()).isTrue();
        assertThat(details.experiment().status()).isEqualTo(ExperimentStatus.READY);
        verify(experimentRepository, never()).update(any(Experiment.class));
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
        return target(TargetEnvironment.CHAOS_LAB, enabled);
    }

    private Target target(TargetEnvironment environment, boolean enabled) {
        Target target = Target.register(
                UUID.randomUUID(),
                "payment-service",
                TargetType.JAVA_APPLICATION,
                environment
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
        return experiment(UUID.randomUUID(), UUID.randomUUID());
    }

    private Experiment experiment(UUID targetId, UUID scenarioId) {
        return Experiment.create(
                UUID.randomUUID(),
                "payment CPU experiment",
                "Service remains available.",
                targetId,
                scenarioId,
                30,
                "{\"percent\":40}"
        );
    }

    private Experiment experiment(
            UUID targetId,
            UUID scenarioId,
            int durationSeconds
    ) {
        return Experiment.create(
                UUID.randomUUID(),
                "payment CPU experiment",
                "Service remains available.",
                targetId,
                scenarioId,
                durationSeconds,
                "{\"percent\":40}"
        );
    }
}
