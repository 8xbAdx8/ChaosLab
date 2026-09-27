package com.chaoslab.safety.application;

import com.chaoslab.audit.application.DangerousOperationAuditor;
import com.chaoslab.audit.application.model.AuditIntent;
import com.chaoslab.audit.domain.AuditOperation;
import com.chaoslab.audit.domain.AuditResult;
import com.chaoslab.execution.application.port.ExecutionOperationMetrics;
import com.chaoslab.safety.application.dto.EmergencyStopOutcome;
import com.chaoslab.safety.application.dto.EmergencyStopResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Objects;

@Service
public class AuditedEmergencyStopApplicationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(
            AuditedEmergencyStopApplicationService.class
    );

    private final EmergencyStopApplicationService delegate;
    private final DangerousOperationAuditor auditor;
    private final ExecutionOperationMetrics metrics;

    public AuditedEmergencyStopApplicationService(
            EmergencyStopApplicationService delegate,
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

    public EmergencyStopResult activate() {
        AuditIntent intent = auditor.prepareGlobal(AuditOperation.EMERGENCY_STOP);
        EmergencyStopResult result;
        try {
            result = delegate.activate();
        } catch (RuntimeException operationException) {
            metrics.record(AuditOperation.EMERGENCY_STOP, ExecutionOperationMetrics.Result.FAILED);
            try {
                auditor.complete(
                        intent,
                        AuditResult.FAILED,
                        failureCode(operationException)
                );
            } catch (RuntimeException auditException) {
                operationException.addSuppressed(auditException);
                LOGGER.atError()
                        .addKeyValue(
                                "failureType",
                                auditException.getClass().getSimpleName()
                        )
                        .log("failed to persist emergency-stop audit log");
            }
            throw operationException;
        }
        boolean incomplete = result.executions().stream()
                .anyMatch(execution -> execution.outcome()
                        != EmergencyStopOutcome.RECOVERED);
        metrics.record(AuditOperation.EMERGENCY_STOP, incomplete
                ? ExecutionOperationMetrics.Result.FAILED
                : ExecutionOperationMetrics.Result.SUCCESS);
        auditor.complete(
                intent,
                incomplete ? AuditResult.FAILED : AuditResult.SUCCESS,
                incomplete ? "RECOVERY_INCOMPLETE" : null
        );
        return result;
    }

    private String failureCode(RuntimeException exception) {
        String type = exception.getClass().getSimpleName();
        return type.isBlank() ? RuntimeException.class.getSimpleName() : type;
    }
}
