package com.chaoslab.execution.application;

import com.chaoslab.engine.application.model.EngineCreateResult;
import com.chaoslab.engine.application.model.EngineExperimentId;
import com.chaoslab.engine.application.model.EngineStatus;
import com.chaoslab.engine.application.model.ReadyExperimentRequest;
import com.chaoslab.engine.application.port.ChaosEngine;
import com.chaoslab.execution.application.dto.StartExperimentExecutionResult;
import com.chaoslab.execution.application.port.ExperimentExecutionRepository;
import com.chaoslab.execution.domain.ExperimentExecution;
import com.chaoslab.execution.domain.ExperimentExecutionStatus;
import com.chaoslab.experiment.application.port.ExperimentRepository;
import com.chaoslab.experiment.domain.Experiment;
import com.chaoslab.experiment.domain.ExperimentStatus;
import com.chaoslab.safety.infrastructure.policy.DefaultSafetyGuard;
import com.chaoslab.scenario.application.port.FaultScenarioRepository;
import com.chaoslab.scenario.domain.FaultScenario;
import com.chaoslab.target.application.port.TargetRepository;
import com.chaoslab.target.domain.Target;
import com.chaoslab.target.domain.TargetEnvironment;
import com.chaoslab.target.domain.TargetType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
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

class ExperimentExecutionApplicationServiceTests {

    private final ExperimentExecutionRepository executionRepository =
            mock(ExperimentExecutionRepository.class);
    private final ExperimentRepository experimentRepository =
            mock(ExperimentRepository.class);
    private final TargetRepository targetRepository = mock(TargetRepository.class);
    private final FaultScenarioRepository scenarioRepository =
            mock(FaultScenarioRepository.class);
    private final ChaosEngine chaosEngine = mock(ChaosEngine.class);
    private final ExperimentExecutionApplicationService service =
            new ExperimentExecutionApplicationService(
                    executionRepository,
                    experimentRepository,
                    targetRepository,
                    scenarioRepository,
                    new DefaultSafetyGuard(),
                    chaosEngine
            );

    @Test
    void shouldStartReadyExperimentWithFakeCompatibleRequest() {
        Target target = target(true);
        FaultScenario scenario = scenario(true);
        Experiment ready = readyExperiment(target, scenario);
        stubReadyExperiment(ready, target, scenario);
        given(executionRepository.findLatestByExperimentId(ready.getId()))
                .willReturn(Optional.empty());
        given(executionRepository.insert(any(ExperimentExecution.class)))
                .willAnswer(invocation -> invocation.getArgument(0));
        given(executionRepository.update(any(ExperimentExecution.class)))
                .willAnswer(invocation -> invocation.getArgument(0));
        given(experimentRepository.update(any(Experiment.class)))
                .willAnswer(invocation -> invocation.getArgument(0));
        given(chaosEngine.create(any(ReadyExperimentRequest.class)))
                .willAnswer(invocation -> {
                    ReadyExperimentRequest request = invocation.getArgument(0);
                    return new EngineCreateResult(
                            new EngineExperimentId("fake-" + request.executionId()),
                            EngineStatus.RUNNING
                    );
                });

        StartExperimentExecutionResult result = service.start(
                ready.getId(),
                " request-001 "
        );

        assertThat(result.created()).isTrue();
        assertThat(result.execution().attempt()).isEqualTo(1);
        assertThat(result.execution().idempotencyKey()).isEqualTo("request-001");
        assertThat(result.execution().status())
                .isEqualTo(ExperimentExecutionStatus.RUNNING);
        assertThat(result.execution().engineExperimentId()).startsWith("fake-");

        ArgumentCaptor<ReadyExperimentRequest> requestCaptor =
                ArgumentCaptor.forClass(ReadyExperimentRequest.class);
        verify(chaosEngine).create(requestCaptor.capture());
        assertThat(requestCaptor.getValue().experimentId()).isEqualTo(ready.getId());
        assertThat(requestCaptor.getValue().targetId()).isEqualTo(target.getId());
        assertThat(requestCaptor.getValue().scenarioCode()).isEqualTo("CPU_LOAD");

        ArgumentCaptor<Experiment> experimentCaptor =
                ArgumentCaptor.forClass(Experiment.class);
        verify(experimentRepository).update(experimentCaptor.capture());
        assertThat(experimentCaptor.getValue().getStatus())
                .isEqualTo(ExperimentStatus.RUNNING);
    }

    @Test
    void shouldReturnExistingExecutionForRepeatedIdempotencyKey() {
        UUID experimentId = UUID.randomUUID();
        ExperimentExecution existing = runningExecution(
                experimentId,
                1,
                "request-001"
        );
        given(executionRepository.findByExperimentIdAndIdempotencyKey(
                experimentId,
                "request-001"
        )).willReturn(Optional.of(existing));

        StartExperimentExecutionResult result = service.start(
                experimentId,
                "request-001"
        );

        assertThat(result.created()).isFalse();
        assertThat(result.execution().id()).isEqualTo(existing.getId());
        verifyNoInteractions(
                experimentRepository,
                targetRepository,
                scenarioRepository,
                chaosEngine
        );
        verify(executionRepository, never()).insert(any(ExperimentExecution.class));
    }

    @Test
    void shouldRecordFailedExecutionWhenEngineCreateThrows() {
        Target target = target(true);
        FaultScenario scenario = scenario(true);
        Experiment ready = readyExperiment(target, scenario);
        stubReadyExperiment(ready, target, scenario);
        given(executionRepository.findLatestByExperimentId(ready.getId()))
                .willReturn(Optional.empty());
        given(executionRepository.insert(any(ExperimentExecution.class)))
                .willAnswer(invocation -> invocation.getArgument(0));
        given(executionRepository.update(any(ExperimentExecution.class)))
                .willAnswer(invocation -> invocation.getArgument(0));
        given(chaosEngine.create(any(ReadyExperimentRequest.class)))
                .willThrow(new IllegalStateException("sensitive engine detail"));

        StartExperimentExecutionResult result = service.start(
                ready.getId(),
                "request-failed"
        );

        assertThat(result.created()).isTrue();
        assertThat(result.execution().status())
                .isEqualTo(ExperimentExecutionStatus.FAILED);
        assertThat(result.execution().errorMessage())
                .isEqualTo("engine create failed: IllegalStateException");
        verify(experimentRepository, never()).update(any(Experiment.class));
    }

    @Test
    void shouldRejectExperimentThatIsNotReady() {
        Experiment validated = Experiment.create(
                UUID.randomUUID(),
                "experiment",
                "service remains available",
                UUID.randomUUID(),
                UUID.randomUUID(),
                30,
                "{}"
        ).validate();
        given(executionRepository.findByExperimentIdAndIdempotencyKey(
                validated.getId(),
                "request-001"
        )).willReturn(Optional.empty());
        given(experimentRepository.findById(validated.getId()))
                .willReturn(Optional.of(validated));

        assertThatThrownBy(() -> service.start(validated.getId(), "request-001"))
                .isInstanceOf(ExperimentExecutionStartRejectedException.class)
                .hasMessage(
                        "experiment must be READY before execution: "
                                + validated.getId()
                )
                .extracting("code")
                .isEqualTo("EXPERIMENT_NOT_READY");
        verifyNoInteractions(targetRepository, scenarioRepository, chaosEngine);
        verify(executionRepository, never()).insert(any(ExperimentExecution.class));
    }

    @Test
    void shouldRecheckSafetyBeforeCallingEngine() {
        Target target = target(true);
        FaultScenario scenario = scenario(true);
        Experiment ready = readyExperiment(target, scenario);
        target.disable();
        stubReadyExperiment(ready, target, scenario);

        assertThatThrownBy(() -> service.start(ready.getId(), "request-001"))
                .isInstanceOf(ExperimentExecutionStartRejectedException.class)
                .extracting("code")
                .isEqualTo("SAFETY_CHECK_REJECTED");
        verify(executionRepository, never()).insert(any(ExperimentExecution.class));
        verifyNoInteractions(chaosEngine);
    }

    @Test
    void shouldRejectInvalidIdempotencyKeyBeforeRepositoryAccess() {
        assertThatThrownBy(() -> service.start(UUID.randomUUID(), "   "))
                .isInstanceOf(InvalidIdempotencyKeyException.class)
                .hasMessage("Idempotency-Key must not be blank");
        assertThatThrownBy(() -> service.start(
                UUID.randomUUID(),
                "x".repeat(129)
        ))
                .isInstanceOf(InvalidIdempotencyKeyException.class)
                .hasMessage("Idempotency-Key must not exceed 128 characters");
        verifyNoInteractions(executionRepository);
    }

    @Test
    void shouldAllocateNextAttemptNumber() {
        Target target = target(true);
        FaultScenario scenario = scenario(true);
        Experiment ready = readyExperiment(target, scenario);
        stubReadyExperiment(ready, target, scenario);
        given(executionRepository.findLatestByExperimentId(ready.getId()))
                .willReturn(Optional.of(failedExecution(
                        ready.getId(),
                        1,
                        "request-old"
                )));
        given(executionRepository.insert(any(ExperimentExecution.class)))
                .willAnswer(invocation -> invocation.getArgument(0));
        given(executionRepository.update(any(ExperimentExecution.class)))
                .willAnswer(invocation -> invocation.getArgument(0));
        given(experimentRepository.update(any(Experiment.class)))
                .willAnswer(invocation -> invocation.getArgument(0));
        given(chaosEngine.create(any(ReadyExperimentRequest.class)))
                .willAnswer(invocation -> {
                    ReadyExperimentRequest request = invocation.getArgument(0);
                    return new EngineCreateResult(
                            new EngineExperimentId("fake-" + request.executionId()),
                            EngineStatus.RUNNING
                    );
                });

        StartExperimentExecutionResult result = service.start(
                ready.getId(),
                "request-new"
        );

        assertThat(result.execution().attempt()).isEqualTo(2);
    }

    private void stubReadyExperiment(
            Experiment experiment,
            Target target,
            FaultScenario scenario
    ) {
        given(executionRepository.findByExperimentIdAndIdempotencyKey(
                experiment.getId(),
                "request-001"
        )).willReturn(Optional.empty());
        given(executionRepository.findByExperimentIdAndIdempotencyKey(
                experiment.getId(),
                "request-failed"
        )).willReturn(Optional.empty());
        given(executionRepository.findByExperimentIdAndIdempotencyKey(
                experiment.getId(),
                "request-new"
        )).willReturn(Optional.empty());
        given(experimentRepository.findById(experiment.getId()))
                .willReturn(Optional.of(experiment));
        given(targetRepository.findById(target.getId())).willReturn(Optional.of(target));
        given(scenarioRepository.findById(scenario.getId()))
                .willReturn(Optional.of(scenario));
    }

    private Experiment readyExperiment(Target target, FaultScenario scenario) {
        return Experiment.create(
                UUID.randomUUID(),
                "experiment",
                "service remains available",
                target.getId(),
                scenario.getId(),
                30,
                "{}"
        ).validate().ready();
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
                "{}",
                enabled
        );
    }

    private ExperimentExecution runningExecution(
            UUID experimentId,
            int attempt,
            String idempotencyKey
    ) {
        Instant createdAt = Instant.parse("2026-08-26T00:00:00Z");
        return ExperimentExecution.prepare(
                UUID.randomUUID(),
                experimentId,
                attempt,
                idempotencyKey,
                createdAt
        ).markRunning("fake-existing", createdAt.plusSeconds(1));
    }

    private ExperimentExecution failedExecution(
            UUID experimentId,
            int attempt,
            String idempotencyKey
    ) {
        return ExperimentExecution.prepare(
                UUID.randomUUID(),
                experimentId,
                attempt,
                idempotencyKey,
                Instant.parse("2026-08-26T00:00:00Z")
        ).markFailed("engine create failed: IllegalStateException");
    }
}
