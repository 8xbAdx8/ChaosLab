package com.chaoslab.safety.application;

import com.chaoslab.execution.application.AuditedExperimentExecutionApplicationService;
import com.chaoslab.execution.application.dto.ExperimentExecutionDetails;
import com.chaoslab.execution.application.port.ExperimentExecutionRepository;
import com.chaoslab.execution.domain.ExperimentExecution;
import com.chaoslab.execution.domain.ExperimentExecutionStatus;
import com.chaoslab.safety.application.dto.EmergencyStopExecutionResult;
import com.chaoslab.safety.application.dto.EmergencyStopOutcome;
import com.chaoslab.safety.application.dto.EmergencyStopResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Service
public class EmergencyStopApplicationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(
            EmergencyStopApplicationService.class
    );
    private static final List<ExperimentExecutionStatus> RECOVERABLE_STATUSES =
            List.of(
                    ExperimentExecutionStatus.RUNNING,
                    ExperimentExecutionStatus.ROLLBACK_FAILED,
                    ExperimentExecutionStatus.CREATE_UNCERTAIN
            );

    private final ExperimentExecutionRepository executionRepository;
    private final AuditedExperimentExecutionApplicationService executionService;
    private final Clock clock;

    public EmergencyStopApplicationService(
            ExperimentExecutionRepository executionRepository,
            AuditedExperimentExecutionApplicationService executionService,
            Clock clock
    ) {
        this.executionRepository = Objects.requireNonNull(
                executionRepository,
                "executionRepository must not be null"
        );
        this.executionService = Objects.requireNonNull(
                executionService,
                "executionService must not be null"
        );
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public EmergencyStopResult activate() {
        Instant requestedAt = clock.instant();
        List<ExperimentExecution> candidates =
                executionRepository.findAllByStatuses(RECOVERABLE_STATUSES);
        LOGGER.atWarn()
                .addKeyValue("candidateCount", candidates.size())
                .log("emergency stop activated");

        List<EmergencyStopExecutionResult> results = new ArrayList<>();
        for (ExperimentExecution candidate : candidates) {
            results.add(recover(candidate));
        }
        return new EmergencyStopResult(requestedAt, results);
    }

    private EmergencyStopExecutionResult recover(
            ExperimentExecution candidate
    ) {
        if (candidate.getStatus() == ExperimentExecutionStatus.CREATE_UNCERTAIN) {
            logFailure(candidate, EmergencyStopOutcome.MANUAL_INTERVENTION.name());
            return result(candidate, EmergencyStopOutcome.MANUAL_INTERVENTION);
        }
        try {
            ExperimentExecutionDetails result = executionService.recoverForEmergency(
                    candidate.getExperimentId(),
                    candidate.getId()
            );
            EmergencyStopOutcome outcome = outcome(result.status());
            if (outcome != EmergencyStopOutcome.RECOVERED) {
                logFailure(candidate, outcome.name());
            }
            return result(candidate, outcome);
        } catch (RuntimeException exception) {
            logFailure(candidate, exception.getClass().getSimpleName());
            return result(candidate, EmergencyStopOutcome.PROCESSING_FAILED);
        }
    }

    private EmergencyStopOutcome outcome(ExperimentExecutionStatus status) {
        return switch (status) {
            case SUCCESS -> EmergencyStopOutcome.RECOVERED;
            case ROLLBACK_FAILED -> EmergencyStopOutcome.ROLLBACK_FAILED;
            default -> EmergencyStopOutcome.PROCESSING_FAILED;
        };
    }

    private EmergencyStopExecutionResult result(
            ExperimentExecution execution,
            EmergencyStopOutcome outcome
    ) {
        return new EmergencyStopExecutionResult(
                execution.getExperimentId(),
                execution.getId(),
                outcome
        );
    }

    private void logFailure(
            ExperimentExecution execution,
            String failureType
    ) {
        LOGGER.atWarn()
                .addKeyValue("experimentId", execution.getExperimentId())
                .addKeyValue("executionId", execution.getId())
                .addKeyValue("failureType", failureType)
                .log("emergency recovery attempt did not complete");
    }
}
