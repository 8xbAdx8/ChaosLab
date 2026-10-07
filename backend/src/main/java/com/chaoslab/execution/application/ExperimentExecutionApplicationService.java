package com.chaoslab.execution.application;

import com.chaoslab.engine.application.model.EngineCreateResult;
import com.chaoslab.engine.application.EngineCreateUncertainException;
import com.chaoslab.engine.application.model.EngineDestroyResult;
import com.chaoslab.engine.application.model.EngineExperimentId;
import com.chaoslab.engine.application.model.EngineStatus;
import com.chaoslab.engine.application.model.ReadyExperimentRequest;
import com.chaoslab.engine.application.port.ChaosEngine;
import com.chaoslab.execution.application.dto.ExperimentExecutionDetails;
import com.chaoslab.execution.application.dto.ExpiredExperimentExecution;
import com.chaoslab.execution.application.dto.StartExperimentExecutionResult;
import com.chaoslab.execution.application.port.ExperimentExecutionRepository;
import com.chaoslab.execution.application.port.GlobalExecutionMutex;
import com.chaoslab.execution.application.port.TargetExecutionMutex;
import com.chaoslab.execution.domain.ExperimentExecution;
import com.chaoslab.execution.domain.ExperimentExecutionStatus;
import com.chaoslab.experiment.application.ExperimentNotFoundException;
import com.chaoslab.experiment.application.port.ExperimentRepository;
import com.chaoslab.experiment.domain.Experiment;
import com.chaoslab.experiment.domain.ExperimentStatus;
import com.chaoslab.safety.application.model.SafetyCheck;
import com.chaoslab.safety.application.model.SafetyDecision;
import com.chaoslab.safety.application.model.VerifiedDockerTarget;
import com.chaoslab.safety.application.port.SafetyGuard;
import com.chaoslab.scenario.application.FaultScenarioNotFoundException;
import com.chaoslab.scenario.application.port.FaultScenarioRepository;
import com.chaoslab.scenario.domain.FaultScenario;
import com.chaoslab.target.application.TargetNotFoundException;
import com.chaoslab.target.application.port.TargetRepository;
import com.chaoslab.target.domain.Target;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class ExperimentExecutionApplicationService {

    private static final Set<ExperimentExecutionStatus> ACTIVE_STATUSES = Set.of(
            ExperimentExecutionStatus.PREPARING,
            ExperimentExecutionStatus.CREATE_UNCERTAIN,
            ExperimentExecutionStatus.RUNNING,
            ExperimentExecutionStatus.DESTROYING,
            ExperimentExecutionStatus.ROLLBACK_FAILED
    );

    private final ExperimentExecutionRepository executionRepository;
    private final GlobalExecutionMutex globalExecutionMutex;
    private final TargetExecutionMutex targetExecutionMutex;
    private final ExperimentRepository experimentRepository;
    private final TargetRepository targetRepository;
    private final FaultScenarioRepository scenarioRepository;
    private final SafetyGuard safetyGuard;
    private final ChaosEngine chaosEngine;
    private final Clock clock;
    private final int maxActiveExecutions;
    private final TransactionTemplate startTransaction;

    public ExperimentExecutionApplicationService(
            ExperimentExecutionRepository executionRepository,
            GlobalExecutionMutex globalExecutionMutex,
            TargetExecutionMutex targetExecutionMutex,
            ExperimentRepository experimentRepository,
            TargetRepository targetRepository,
            FaultScenarioRepository scenarioRepository,
            SafetyGuard safetyGuard,
            ChaosEngine chaosEngine,
            Clock clock,
            @Value("${chaoslab.execution.max-active-executions:3}")
            int maxActiveExecutions,
            PlatformTransactionManager transactionManager
    ) {
        this.executionRepository = Objects.requireNonNull(
                executionRepository,
                "executionRepository must not be null"
        );
        this.globalExecutionMutex = Objects.requireNonNull(
                globalExecutionMutex,
                "globalExecutionMutex must not be null"
        );
        this.targetExecutionMutex = Objects.requireNonNull(
                targetExecutionMutex,
                "targetExecutionMutex must not be null"
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
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        if (maxActiveExecutions < 1) {
            throw new IllegalArgumentException(
                    "maxActiveExecutions must be at least 1"
            );
        }
        this.maxActiveExecutions = maxActiveExecutions;
        this.startTransaction = new TransactionTemplate(Objects.requireNonNull(transactionManager));
        this.startTransaction.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public StartExperimentExecutionResult start(
            UUID experimentId,
            String rawIdempotencyKey
    ) {
        Objects.requireNonNull(experimentId, "experimentId must not be null");
        String idempotencyKey = normalizeIdempotencyKey(rawIdempotencyKey);
        StartIntent intent = startTransaction.execute(status -> prepareStart(experimentId, idempotencyKey));
        if (intent.request() == null) {
            return new StartExperimentExecutionResult(ExperimentExecutionDetails.from(intent.execution()), false);
        }
        // The reservation is committed before calling the engine. Never retry this call here.
        EngineCreateResult result;
        try {
            result = chaosEngine.create(intent.request());
        } catch (EngineCreateUncertainException exception) {
            return startTransaction.execute(status -> created(
                    executionRepository.update(intent.execution().markCreateUncertain(
                            exception.recoveryReference().map(EngineExperimentId::value).orElse(null)))));
        } catch (RuntimeException exception) {
            return startTransaction.execute(status -> created(executionRepository.update(
                    intent.execution().markFailed(engineFailureMessage(exception)))));
        }
        try {
            return startTransaction.execute(status -> finishStart(intent, result));
        } catch (RuntimeException resultPersistenceFailure) {
            // If this write also fails, committed PREPARING remains occupied. Never redispatch.
            return startTransaction.execute(status -> {
                var current = findOwnedExecution(experimentId, intent.execution().getId());
                if (current.getStatus() == ExperimentExecutionStatus.PREPARING)
                    return created(executionRepository.update(current.markCreateUncertain(
                            ("blade-"+current.getId()).equals(result.engineExperimentId().value())
                                    ? result.engineExperimentId().value() : null)));
                return created(current);
            });
        }
    }

    private StartIntent prepareStart(UUID experimentId, String idempotencyKey) {
        globalExecutionMutex.lock();
        targetExecutionMutex.lockForExperiment(experimentId);

        return executionRepository.findByExperimentIdAndIdempotencyKey(
                        experimentId,
                        idempotencyKey
                )
                .map(execution -> new StartIntent(execution, null, null))
                .orElseGet(() -> prepareNew(experimentId, idempotencyKey));
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

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ExperimentExecutionDetails destroy(UUID experimentId, UUID executionId) {
        Objects.requireNonNull(experimentId);
        Objects.requireNonNull(executionId);
        DestroyIntent intent = startTransaction.execute(tx -> {
            var execution = findOwnedExecution(experimentId, executionId);
            if (execution.getStatus() == ExperimentExecutionStatus.SUCCESS) return new DestroyIntent(execution, null);
            var experiment = experimentRepository.findById(experimentId)
                    .orElseThrow(() -> new ExperimentNotFoundException(experimentId));
            requireDestroyable(execution, experiment);
            if (execution.getStatus() == ExperimentExecutionStatus.CREATE_UNCERTAIN) {
                Instant attemptAt = clock.instant();
                if (attemptAt.isBefore(execution.getCreatedAt())) attemptAt = execution.getCreatedAt();
                return new DestroyIntent(executionRepository.update(execution.beginUncertainDestroy(attemptAt)),
                        experimentRepository.update(experiment.beginUncertainDestroy()));
            }
            return new DestroyIntent(executionRepository.update(execution.beginDestroy()),
                    experimentRepository.update(experiment.beginDestroy()));
        });
        if (intent.experiment() == null) return ExperimentExecutionDetails.from(intent.execution());
        // Both DESTROYING records committed; no transaction/lock waits on an external process.
        EngineDestroyResult result;
        try {
            result = chaosEngine.destroy(new EngineExperimentId(intent.execution().getEngineExperimentId()));
        } catch (RuntimeException exception) {
            return startTransaction.execute(tx -> recordRollbackFailure(intent.execution(), intent.experiment(),
                    rollbackFailureMessage(exception)));
        }
        return startTransaction.execute(tx -> finishDestroy(intent, result));
    }

    private record DestroyIntent(ExperimentExecution execution, Experiment experiment) { }

    private ExperimentExecutionDetails finishDestroy(DestroyIntent intent, EngineDestroyResult result) {
        // Versions from transaction 1 use the existing optimistic repository; stale results conflict.
        var destroying = intent.execution();
        var experiment = intent.experiment();
        if (!destroying.getEngineExperimentId().equals(result.engineExperimentId().value()))
            return recordRollbackFailure(destroying, experiment, "engine recovery reference mismatch");
        if (result.status() == EngineStatus.ENGINE_RECOVERED)
            return recordRollbackFailure(destroying, experiment,
                    "ENGINE_DESTROYED_RECOVERY_UNVERIFIED: residual and health evidence required");
        if (result.status() != EngineStatus.DESTROYED)
            return recordRollbackFailure(destroying, experiment, "engine destroy returned unexpected status " + result.status());
        Instant finishedAt = clock.instant();
        if (finishedAt.isBefore(destroying.getStartedAt())) finishedAt = destroying.getStartedAt();
        var successful = executionRepository.update(destroying.markSuccess(finishedAt));
        experimentRepository.update(experiment.complete());
        return ExperimentExecutionDetails.from(successful);
    }

    public List<ExpiredExperimentExecution> findExpired(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        return executionRepository.findAllByStatus(
                        ExperimentExecutionStatus.RUNNING
                ).stream()
                .filter(execution -> hasReachedDeadline(execution, now))
                .map(execution -> new ExpiredExperimentExecution(
                        execution.getExperimentId(),
                        execution.getId()
                ))
                .toList();
    }

    private StartIntent prepareNew(
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
        VerifiedDockerTarget verifiedTarget = requireSafe(
                safetyGuard.evaluate(experiment, target, scenario)
        );
        requireTargetAvailable(target.getId());
        requireGlobalCapacity();

        int attempt = executionRepository.findLatestByExperimentId(experimentId)
                .map(ExperimentExecution::getAttempt)
                .map(previous -> Math.addExact(previous, 1))
                .orElse(1);
        Instant createdAt = clock.instant();
        ExperimentExecution execution = executionRepository.insert(
                ExperimentExecution.prepare(
                        UUID.randomUUID(),
                        experimentId,
                        attempt,
                        idempotencyKey,
                        createdAt
                )
        );

        return new StartIntent(execution, experiment, new ReadyExperimentRequest(
                    execution.getId(),
                    experiment.getId(),
                    target.getId(),
                    scenario.getCode(),
                    experiment.getDurationSeconds(),
                    experiment.getParameters(),
                    verifiedTarget
            ));
    }

    private record StartIntent(ExperimentExecution execution, Experiment experiment, ReadyExperimentRequest request) { }

    private StartExperimentExecutionResult finishStart(StartIntent intent, EngineCreateResult engineResult) {
        ExperimentExecution execution = intent.execution();
        Experiment experiment = intent.experiment();
        Instant createdAt = execution.getCreatedAt();

        if (engineResult.status() != EngineStatus.RUNNING) {
            ExperimentExecution failed = executionRepository.update(
                    execution.markFailed(
                            "engine create returned unexpected status "
                                    + engineResult.status()
                    )
            );
            return created(failed);
        }

        Instant startedAt = clock.instant();
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

    private ExperimentExecution findOwnedExecution(
            UUID experimentId,
            UUID executionId
    ) {
        return executionRepository.findById(executionId)
                .filter(found -> found.getExperimentId().equals(experimentId))
                .orElseThrow(() -> new ExperimentExecutionNotFoundException(executionId));
    }

    private boolean hasReachedDeadline(
            ExperimentExecution execution,
            Instant now
    ) {
        Experiment experiment = experimentRepository.findById(
                execution.getExperimentId()
        ).orElseThrow(() -> new ExperimentNotFoundException(
                execution.getExperimentId()
        ));
        Instant deadline = execution.getStartedAt().plusSeconds(
                experiment.getDurationSeconds()
        );
        return !deadline.isAfter(now);
    }

    private void requireDestroyable(
            ExperimentExecution execution,
            Experiment experiment
    ) {
        boolean running = execution.getStatus() == ExperimentExecutionStatus.RUNNING
                && experiment.getStatus() == ExperimentStatus.RUNNING;
        boolean retrying = execution.getStatus()
                == ExperimentExecutionStatus.ROLLBACK_FAILED
                && experiment.getStatus() == ExperimentStatus.ROLLBACK_FAILED;
        boolean uncertainWithJournal = execution.getStatus() == ExperimentExecutionStatus.CREATE_UNCERTAIN
                && ("blade-"+execution.getId()).equals(execution.getEngineExperimentId())
                && experiment.getStatus() == ExperimentStatus.READY;
        if (!running && !retrying && !uncertainWithJournal) {
            throw new ExperimentExecutionDestroyRejectedException(
                    "EXPERIMENT_EXECUTION_NOT_DESTROYABLE",
                    "execution cannot be destroyed from status "
                            + execution.getStatus()
                            + " while experiment is "
                            + experiment.getStatus()
            );
        }
    }

    private ExperimentExecutionDetails recordRollbackFailure(
            ExperimentExecution destroying,
            Experiment destroyingExperiment,
            String errorMessage
    ) {
        ExperimentExecution failed = executionRepository.update(
                destroying.markRollbackFailed(errorMessage)
        );
        experimentRepository.update(destroyingExperiment.markRollbackFailed());
        return ExperimentExecutionDetails.from(failed);
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

    private VerifiedDockerTarget requireSafe(SafetyDecision decision) {
        if (decision.accepted()) {
            return decision.verifiedTarget();
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

    private void requireTargetAvailable(UUID targetId) {
        if (!executionRepository.existsByTargetIdAndStatuses(
                targetId,
                ACTIVE_STATUSES
        )) {
            return;
        }
        throw new ExperimentExecutionStartRejectedException(
                "TARGET_EXECUTION_ALREADY_ACTIVE",
                "target already has an active experiment execution: " + targetId,
                List.of()
        );
    }

    private void requireGlobalCapacity() {
        long activeExecutions = executionRepository.countByStatuses(
                ACTIVE_STATUSES
        );
        if (activeExecutions < maxActiveExecutions) {
            return;
        }
        throw new ExperimentExecutionStartRejectedException(
                "GLOBAL_EXECUTION_LIMIT_REACHED",
                "active experiment execution limit reached: "
                        + maxActiveExecutions,
                List.of()
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
        return "engine create failed: " + exceptionType(exception);
    }

    private String rollbackFailureMessage(RuntimeException exception) {
        if (exception instanceof com.chaoslab.engine.application.EngineRecoveryException recovery)
            return recovery.reason().name();
        return "engine destroy failed: " + exceptionType(exception);
    }

    private String exceptionType(RuntimeException exception) {
        String type = exception.getClass().getSimpleName();
        return type.isBlank() ? RuntimeException.class.getSimpleName() : type;
    }
}
