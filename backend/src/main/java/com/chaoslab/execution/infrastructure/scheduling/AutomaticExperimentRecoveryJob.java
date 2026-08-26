package com.chaoslab.execution.infrastructure.scheduling;

import com.chaoslab.execution.application.ExperimentExecutionApplicationService;
import com.chaoslab.execution.application.dto.ExpiredExperimentExecution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;

@Component
public class AutomaticExperimentRecoveryJob {

    private static final Logger LOGGER = LoggerFactory.getLogger(
            AutomaticExperimentRecoveryJob.class
    );

    private final ExperimentExecutionApplicationService executionService;
    private final Clock clock;

    public AutomaticExperimentRecoveryJob(
            ExperimentExecutionApplicationService executionService,
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
            executionService.destroy(
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