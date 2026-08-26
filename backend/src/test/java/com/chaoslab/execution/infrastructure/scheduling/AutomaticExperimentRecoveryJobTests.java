package com.chaoslab.execution.infrastructure.scheduling;

import com.chaoslab.execution.application.ExperimentExecutionApplicationService;
import com.chaoslab.execution.application.dto.ExpiredExperimentExecution;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AutomaticExperimentRecoveryJobTests {

    private static final Instant NOW = Instant.parse("2026-08-26T00:01:00Z");

    private final ExperimentExecutionApplicationService executionService =
            mock(ExperimentExecutionApplicationService.class);
    private final AutomaticExperimentRecoveryJob job =
            new AutomaticExperimentRecoveryJob(
                    executionService,
                    Clock.fixed(NOW, ZoneOffset.UTC)
            );

    @Test
    void shouldDestroyEveryExpiredExecution() {
        ExpiredExperimentExecution first = expiredExecution();
        ExpiredExperimentExecution second = expiredExecution();
        given(executionService.findExpired(NOW))
                .willReturn(List.of(first, second));

        job.recoverExpiredExecutions();

        verify(executionService).destroy(
                first.experimentId(),
                first.executionId()
        );
        verify(executionService).destroy(
                second.experimentId(),
                second.executionId()
        );
    }

    @Test
    void shouldContinueWhenOneRecoveryAttemptThrows() {
        ExpiredExperimentExecution first = expiredExecution();
        ExpiredExperimentExecution second = expiredExecution();
        given(executionService.findExpired(NOW))
                .willReturn(List.of(first, second));
        doThrow(new IllegalStateException("first failed"))
                .when(executionService)
                .destroy(first.experimentId(), first.executionId());

        job.recoverExpiredExecutions();

        verify(executionService).destroy(
                second.experimentId(),
                second.executionId()
        );
    }

    private ExpiredExperimentExecution expiredExecution() {
        return new ExpiredExperimentExecution(
                UUID.randomUUID(),
                UUID.randomUUID()
        );
    }
}