package com.chaoslab.audit.application;

import com.chaoslab.audit.application.model.AuditContext;
import com.chaoslab.audit.application.model.AuditIntent;
import com.chaoslab.audit.application.model.AuditSubject;
import com.chaoslab.audit.application.port.AuditContextProvider;
import com.chaoslab.audit.domain.AuditOperation;
import com.chaoslab.audit.domain.AuditResult;
import com.chaoslab.experiment.application.port.ExperimentRepository;
import com.chaoslab.experiment.domain.Experiment;
import com.chaoslab.scenario.application.port.FaultScenarioRepository;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.UUID;

@Service
public class DangerousOperationAuditor {

    private final AuditContextProvider contextProvider;
    private final AuditLogApplicationService auditLogService;
    private final ExperimentRepository experimentRepository;
    private final FaultScenarioRepository scenarioRepository;

    public DangerousOperationAuditor(
            AuditContextProvider contextProvider,
            AuditLogApplicationService auditLogService,
            ExperimentRepository experimentRepository,
            FaultScenarioRepository scenarioRepository
    ) {
        this.contextProvider = Objects.requireNonNull(
                contextProvider,
                "contextProvider must not be null"
        );
        this.auditLogService = Objects.requireNonNull(
                auditLogService,
                "auditLogService must not be null"
        );
        this.experimentRepository = Objects.requireNonNull(
                experimentRepository,
                "experimentRepository must not be null"
        );
        this.scenarioRepository = Objects.requireNonNull(
                scenarioRepository,
                "scenarioRepository must not be null"
        );
    }

    public AuditIntent prepare(
            AuditOperation operation,
            UUID experimentId,
            UUID executionId
    ) {
        Objects.requireNonNull(operation, "operation must not be null");
        Objects.requireNonNull(experimentId, "experimentId must not be null");
        AuditContext context = contextProvider.currentContext();
        AuditSubject subject = experimentRepository.findById(experimentId)
                .map(experiment -> subject(experiment, executionId))
                .orElseGet(() -> new AuditSubject(
                        experimentId,
                        executionId,
                        null,
                        null,
                        null
                ));
        return new AuditIntent(context, operation, subject);
    }

    public AuditIntent prepareGlobal(AuditOperation operation) {
        Objects.requireNonNull(operation, "operation must not be null");
        return new AuditIntent(
                contextProvider.currentContext(),
                operation,
                new AuditSubject(null, null, null, null, null)
        );
    }

    public void complete(
            AuditIntent intent,
            AuditResult result,
            String failureCode
    ) {
        auditLogService.append(intent, result, failureCode);
    }

    private AuditSubject subject(
            Experiment experiment,
            UUID executionId
    ) {
        String scenarioCode = scenarioRepository.findById(
                        experiment.getScenarioId()
                )
                .map(scenario -> scenario.getCode())
                .orElse(null);
        return new AuditSubject(
                experiment.getId(),
                executionId,
                experiment.getTargetId(),
                scenarioCode,
                experiment.getParameters()
        );
    }
}
