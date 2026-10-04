package com.chaoslab.execution.infrastructure.scheduling;

import com.chaoslab.execution.application.AuditedExperimentExecutionApplicationService;
import com.chaoslab.execution.application.dto.ExpiredExperimentExecution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;

@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "chaoslab.engine", havingValue = "fake", matchIfMissing = true)
public class AutomaticExperimentRecoveryJob {

    private static final Logger LOGGER = LoggerFactory.getLogger(
            AutomaticExperimentRecoveryJob.class
    );

    private final AuditedExperimentExecutionApplicationService executionService;
    private final Clock clock;

    public AutomaticExperimentRecoveryJob(
            AuditedExperimentExecutionApplicationService executionService,
            Clock clock
    ) {
        this.executionService = executionService;
        this.clock = clock;
    }

    @Scheduled(
            fixedDelayString =
                    "${chaoslab.execution.recovery-scan-interval-ms:1000}"
    )
    public void recoverExpiredExecutions() {
        for (ExpiredExperimentExecution expired
                : executionService.findExpired(clock.instant())) {
            recover(expired);
        }
    }

    private void recover(ExpiredExperimentExecution expired) {
        try {
            executionService.recoverAutomatically(
                    expired.experimentId(),
                    expired.executionId()
            );
        } catch (RuntimeException exception) {
            LOGGER.atWarn()
                    .addKeyValue("experimentId", expired.experimentId())
                    .addKeyValue("executionId", expired.executionId())
                    .addKeyValue("failureType", exception.getClass().getSimpleName())
                    .log("automatic experiment recovery attempt failed");
        }
    }
}
