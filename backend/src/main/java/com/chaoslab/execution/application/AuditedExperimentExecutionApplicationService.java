package com.chaoslab.execution.application;

import com.chaoslab.audit.application.DangerousOperationAuditor;
import com.chaoslab.audit.application.model.AuditIntent;
import com.chaoslab.audit.application.model.AuditSubject;
import com.chaoslab.audit.domain.AuditOperation;
import com.chaoslab.audit.domain.AuditResult;
import com.chaoslab.execution.application.dto.ExperimentExecutionDetails;
import com.chaoslab.execution.application.dto.ExpiredExperimentExecution;
import com.chaoslab.execution.application.dto.StartExperimentExecutionResult;
import com.chaoslab.execution.domain.ExperimentExecutionStatus;
import com.chaoslab.execution.application.port.ExecutionOperationMetrics;
import com.chaoslab.experiment.application.ExperimentNotFoundException;
import com.chaoslab.scenario.application.FaultScenarioNotFoundException;
import com.chaoslab.target.application.TargetNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class AuditedExperimentExecutionApplicationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(
            AuditedExperimentExecutionApplicationService.class
    );

    private final ExperimentExecutionApplicationService delegate;
    private final DangerousOperationAuditor auditor;
    private final ExecutionOperationMetrics metrics;

    public AuditedExperimentExecutionApplicationService(
            ExperimentExecutionApplicationService delegate,
            DangerousOperationAuditor auditor,
            ExecutionOperationMetrics metrics
    ) {
        this.delegate = Objects.requireNonNull(
                delegate,
                "delegate must not be null"
        );
        this.auditor = Objects.requireNonNull(auditor, "auditor must not be null");
        this.metrics = Objects.requireNonNull(metrics, "metrics must not be null");
    }

    public StartExperimentExecutionResult start(
            UUID experimentId,
            String idempotencyKey
    ) {
        AuditIntent intent = auditor.prepare(
                AuditOperation.START_EXPERIMENT,
                experimentId,
                null
        );
        StartExperimentExecutionResult result;
        try {
            result = delegate.start(
                    experimentId,
                    idempotencyKey
            );
        } catch (RuntimeException exception) {
            metrics.record(AuditOperation.START_EXPERIMENT, isRejected(exception)
                    ? ExecutionOperationMetrics.Result.REJECTED
                    : ExecutionOperationMetrics.Result.FAILED);
            auditException(intent, exception);
            throw exception;
        }
        metrics.record(AuditOperation.START_EXPERIMENT, !result.created()
                ? ExecutionOperationMetrics.Result.REPLAYED
                : result.execution().status() == ExperimentExecutionStatus.RUNNING
                        ? ExecutionOperationMetrics.Result.SUCCESS
                        : ExecutionOperationMetrics.Result.FAILED);
        completeForStart(intent, result.execution());
        return result;
    }

    public ExperimentExecutionDetails destroy(
            UUID experimentId,
            UUID executionId
    ) {
        return destroy(
                AuditOperation.DESTROY_EXPERIMENT,
                experimentId,
                executionId
        );
    }

    public ExperimentExecutionDetails recoverAutomatically(
            UUID experimentId,
            UUID executionId
    ) {
        return destroy(
                AuditOperation.AUTOMATIC_RECOVERY,
                experimentId,
                executionId
        );
    }

    public ExperimentExecutionDetails recoverForEmergency(
            UUID experimentId,
            UUID executionId
    ) {
        return destroy(
                AuditOperation.EMERGENCY_RECOVERY,
                experimentId,
                executionId
        );
    }

    public ExperimentExecutionDetails findById(
            UUID experimentId,
            UUID executionId
    ) {
        return delegate.findById(experimentId, executionId);
    }

    public List<ExpiredExperimentExecution> findExpired(Instant now) {
        return delegate.findExpired(now);
    }

    private ExperimentExecutionDetails destroy(
            AuditOperation operation,
            UUID experimentId,
            UUID executionId
    ) {
        AuditIntent intent = auditor.prepare(operation, experimentId, executionId);
        ExperimentExecutionDetails result;
        try {
            result = delegate.destroy(
                    experimentId,
                    executionId
            );
        } catch (RuntimeException exception) {
            metrics.record(operation, isRejected(exception)
                    ? ExecutionOperationMetrics.Result.REJECTED
                    : ExecutionOperationMetrics.Result.FAILED);
            auditException(intent, exception);
            throw exception;
        }
        metrics.record(operation, result.status() == ExperimentExecutionStatus.SUCCESS
                ? ExecutionOperationMetrics.Result.SUCCESS
                : ExecutionOperationMetrics.Result.FAILED);
        completeForDestroy(intent, result);
        return result;
    }

    private void completeForStart(
            AuditIntent intent,
            ExperimentExecutionDetails execution
    ) {
        if (execution.status() == ExperimentExecutionStatus.RUNNING) {
            auditor.complete(intentWithExecution(intent, execution.id()), AuditResult.SUCCESS, null);
            return;
        }
        auditor.complete(
                intentWithExecution(intent, execution.id()),
                AuditResult.FAILED,
                "EXECUTION_" + execution.status().name()
        );
    }

    private void completeForDestroy(
            AuditIntent intent,
            ExperimentExecutionDetails execution
    ) {
        if (execution.status() == ExperimentExecutionStatus.SUCCESS) {
            auditor.complete(intent, AuditResult.SUCCESS, null);
            return;
        }
        auditor.complete(
                intent,
                AuditResult.FAILED,
                "EXECUTION_" + execution.status().name()
        );
    }

    private AuditIntent intentWithExecution(
            AuditIntent intent,
            UUID executionId
    ) {
        return new AuditIntent(
                intent.context(),
                intent.operation(),
                new AuditSubject(
                        intent.subject().experimentId(),
                        executionId,
                        intent.subject().targetId(),
                        intent.subject().scenarioCode(),
                        intent.subject().parameters()
                )
        );
    }

    private void auditException(
            AuditIntent intent,
            RuntimeException operationException
    ) {
        try {
            auditor.complete(
                    intent,
                    isRejected(operationException)
                            ? AuditResult.REJECTED
                            : AuditResult.FAILED,
                    failureCode(operationException)
            );
        } catch (RuntimeException auditException) {
            operationException.addSuppressed(auditException);
            LOGGER.atError()
                    .addKeyValue("operation", intent.operation())
                    .addKeyValue("failureType", auditException.getClass().getSimpleName())
                    .log("failed to persist dangerous-operation audit log");
        }
    }

    private boolean isRejected(RuntimeException exception) {
        return exception instanceof ExperimentExecutionStartRejectedException
                || exception instanceof ExperimentExecutionDestroyRejectedException
                || exception instanceof InvalidIdempotencyKeyException
                || exception instanceof ExperimentExecutionNotFoundException
                || exception instanceof ExperimentNotFoundException
                || exception instanceof FaultScenarioNotFoundException
                || exception instanceof TargetNotFoundException;
    }

    private String failureCode(RuntimeException exception) {
        if (exception instanceof ExperimentExecutionStartRejectedException rejected) {
            return rejected.getCode();
        }
        if (exception instanceof ExperimentExecutionDestroyRejectedException rejected) {
            return rejected.getCode();
        }
        if (exception instanceof InvalidIdempotencyKeyException) {
            return "INVALID_IDEMPOTENCY_KEY";
        }
        if (exception instanceof ExperimentExecutionNotFoundException) {
            return "EXPERIMENT_EXECUTION_NOT_FOUND";
        }
        if (exception instanceof ExperimentNotFoundException) {
            return "EXPERIMENT_NOT_FOUND";
        }
        if (exception instanceof FaultScenarioNotFoundException) {
            return "SCENARIO_NOT_FOUND";
        }
        if (exception instanceof TargetNotFoundException) {
            return "TARGET_NOT_FOUND";
        }
        String type = exception.getClass().getSimpleName();
        return type.isBlank() ? RuntimeException.class.getSimpleName() : type;
    }
}
