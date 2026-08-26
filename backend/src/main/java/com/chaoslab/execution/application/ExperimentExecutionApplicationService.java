package com.chaoslab.execution.application;

import com.chaoslab.engine.application.model.EngineCreateResult;
import com.chaoslab.engine.application.model.EngineStatus;
import com.chaoslab.engine.application.model.ReadyExperimentRequest;
import com.chaoslab.engine.application.port.ChaosEngine;
import com.chaoslab.execution.application.dto.ExperimentExecutionDetails;
import com.chaoslab.execution.application.dto.StartExperimentExecutionResult;
import com.chaoslab.execution.application.port.ExperimentExecutionRepository;
import com.chaoslab.execution.domain.ExperimentExecution;
import com.chaoslab.experiment.application.ExperimentNotFoundException;
import com.chaoslab.experiment.application.port.ExperimentRepository;
import com.chaoslab.experiment.domain.Experiment;
import com.chaoslab.experiment.domain.ExperimentStatus;
import com.chaoslab.safety.application.model.SafetyCheck;
import com.chaoslab.safety.application.model.SafetyDecision;
import com.chaoslab.safety.application.port.SafetyGuard;
import com.chaoslab.scenario.application.FaultScenarioNotFoundException;
import com.chaoslab.scenario.application.port.FaultScenarioRepository;
import com.chaoslab.scenario.domain.FaultScenario;
import com.chaoslab.target.application.TargetNotFoundException;
import com.chaoslab.target.application.port.TargetRepository;
import com.chaoslab.target.domain.Target;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class ExperimentExecutionApplicationService {

    private final ExperimentExecutionRepository executionRepository;
    private final ExperimentRepository experimentRepository;
    private final TargetRepository targetRepository;
    private final FaultScenarioRepository scenarioRepository;
    private final SafetyGuard safetyGuard;
    private final ChaosEngine chaosEngine;

    public ExperimentExecutionApplicationService(
            ExperimentExecutionRepository executionRepository,
            ExperimentRepository experimentRepository,
            TargetRepository targetRepository,
            FaultScenarioRepository scenarioRepository,
            SafetyGuard safetyGuard,
            ChaosEngine chaosEngine
    ) {
        this.executionRepository = Objects.requireNonNull(
                executionRepository,
                "executionRepository must not be null"
        );
        this.experimentRepository = Objects.requireNonNull(
                experimentRepository,
                "experimentRepository must not be null"
        );
        this.targetRepository = Objects.requireNonNull(
                targetRepository,
                "targetRepository must not be null"
        );
        this.scenarioRepository = Objects.requireNonNull(
                scenarioRepository,
                "scenarioRepository must not be null"
        );
        this.safetyGuard = Objects.requireNonNull(
                safetyGuard,
                "safetyGuard must not be null"
        );
        this.chaosEngine = Objects.requireNonNull(
                chaosEngine,
                "chaosEngine must not be null"
        );
    }

    @Transactional
    public StartExperimentExecutionResult start(
            UUID experimentId,
            String rawIdempotencyKey
    ) {
        Objects.requireNonNull(experimentId, "experimentId must not be null");
        String idempotencyKey = normalizeIdempotencyKey(rawIdempotencyKey);

        return executionRepository.findByExperimentIdAndIdempotencyKey(
                        experimentId,
                        idempotencyKey
                )
                .map(execution -> new StartExperimentExecutionResult(
                        ExperimentExecutionDetails.from(execution),
                        false
                ))
                .orElseGet(() -> startNew(experimentId, idempotencyKey));
    }

    public ExperimentExecutionDetails findById(
            UUID experimentId,
            UUID executionId
    ) {
        Objects.requireNonNull(experimentId, "experimentId must not be null");
        Objects.requireNonNull(executionId, "executionId must not be null");
        ExperimentExecution execution = executionRepository.findById(executionId)
                .filter(found -> found.getExperimentId().equals(experimentId))
                .orElseThrow(() -> new ExperimentExecutionNotFoundException(executionId));
        return ExperimentExecutionDetails.from(execution);
    }

    private StartExperimentExecutionResult startNew(
            UUID experimentId,
            String idempotencyKey
    ) {
        Experiment experiment = experimentRepository.findById(experimentId)
                .orElseThrow(() -> new ExperimentNotFoundException(experimentId));
        requireReady(experiment);

        Target target = targetRepository.findById(experiment.getTargetId())
                .orElseThrow(() -> new TargetNotFoundException(experiment.getTargetId()));
        FaultScenario scenario = scenarioRepository.findById(experiment.getScenarioId())
                .orElseThrow(() -> new FaultScenarioNotFoundException(
                        experiment.getScenarioId()
                ));
        requireSafe(safetyGuard.evaluate(experiment, target, scenario));

        int attempt = executionRepository.findLatestByExperimentId(experimentId)
                .map(ExperimentExecution::getAttempt)
                .map(previous -> Math.addExact(previous, 1))
                .orElse(1);
        Instant createdAt = Instant.now();
        ExperimentExecution execution = executionRepository.insert(
                ExperimentExecution.prepare(
                        UUID.randomUUID(),
                        experimentId,
                        attempt,
                        idempotencyKey,
                        createdAt
                )
        );

        EngineCreateResult engineResult;
        try {
            engineResult = chaosEngine.create(new ReadyExperimentRequest(
                    execution.getId(),
                    experiment.getId(),
                    target.getId(),
                    scenario.getCode(),
                    experiment.getDurationSeconds(),
                    experiment.getParameters()
            ));
        } catch (RuntimeException exception) {
            ExperimentExecution failed = executionRepository.update(
                    execution.markFailed(engineFailureMessage(exception))
            );
            return created(failed);
        }

        if (engineResult.status() != EngineStatus.RUNNING) {
            ExperimentExecution failed = executionRepository.update(
                    execution.markFailed(
                            "engine create returned unexpected status "
                                    + engineResult.status()
                    )
            );
            return created(failed);
        }

        Instant startedAt = Instant.now();
        if (startedAt.isBefore(createdAt)) {
            startedAt = createdAt;
        }
        ExperimentExecution running = executionRepository.update(
                execution.markRunning(
                        engineResult.engineExperimentId().value(),
                        startedAt
                )
        );
        experimentRepository.update(experiment.start());
        return created(running);
    }

    private StartExperimentExecutionResult created(ExperimentExecution execution) {
        return new StartExperimentExecutionResult(
                ExperimentExecutionDetails.from(execution),
                true
        );
    }

    private void requireReady(Experiment experiment) {
        if (experiment.getStatus() != ExperimentStatus.READY) {
            throw new ExperimentExecutionStartRejectedException(
                    "EXPERIMENT_NOT_READY",
                    "experiment must be READY before execution: " + experiment.getId(),
                    List.of()
            );
        }
    }

    private void requireSafe(SafetyDecision decision) {
        if (decision.accepted()) {
            return;
        }
        List<SafetyCheck> failedChecks = decision.checks().stream()
                .filter(check -> !check.passed())
                .toList();
        throw new ExperimentExecutionStartRejectedException(
                "SAFETY_CHECK_REJECTED",
                "experiment no longer passes the execution safety checks",
                failedChecks
        );
    }

    private String normalizeIdempotencyKey(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new InvalidIdempotencyKeyException(
                    "Idempotency-Key must not be blank"
            );
        }
        String normalized = value.trim();
        if (normalized.length() > ExperimentExecution.MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new InvalidIdempotencyKeyException(
                    "Idempotency-Key must not exceed "
                            + ExperimentExecution.MAX_IDEMPOTENCY_KEY_LENGTH
                            + " characters"
            );
        }
        return normalized;
    }

    private String engineFailureMessage(RuntimeException exception) {
        String type = exception.getClass().getSimpleName();
        if (type.isBlank()) {
            type = RuntimeException.class.getSimpleName();
        }
        return "engine create failed: " + type;
    }
}
