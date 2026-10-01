package com.chaoslab.safety.application;

import com.chaoslab.execution.application.AuditedExperimentExecutionApplicationService;
import com.chaoslab.execution.application.dto.ExperimentExecutionDetails;
import com.chaoslab.execution.application.port.ExperimentExecutionRepository;
import com.chaoslab.execution.domain.ExperimentExecution;
import com.chaoslab.execution.domain.ExperimentExecutionStatus;
import com.chaoslab.safety.application.dto.EmergencyStopOutcome;
import com.chaoslab.safety.application.dto.EmergencyStopResult;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class EmergencyStopApplicationServiceTests {

    private static final Instant NOW = Instant.parse("2026-08-26T00:01:00Z");
    private static final List<ExperimentExecutionStatus> RECOVERABLE_STATUSES =
            List.of(
                    ExperimentExecutionStatus.RUNNING,
                    ExperimentExecutionStatus.ROLLBACK_FAILED,
                    ExperimentExecutionStatus.CREATE_UNCERTAIN,
                    ExperimentExecutionStatus.PREPARING
            );

    private final ExperimentExecutionRepository executionRepository =
            mock(ExperimentExecutionRepository.class);
    private final AuditedExperimentExecutionApplicationService executionService =
            mock(AuditedExperimentExecutionApplicationService.class);
    private final EmergencyStopApplicationService emergencyStopService =
            new EmergencyStopApplicationService(
                    executionRepository,
                    executionService,
                    Clock.fixed(NOW, ZoneOffset.UTC)
            );

    @Test
    void shouldRecoverRunningAndRetryRollbackFailedExecutions() {
        ExperimentExecution running = runningExecution("request-running");
        ExperimentExecution rollbackFailed = runningExecution("request-retry")
                .beginDestroy()
                .markRollbackFailed("engine destroy failed");
        given(executionRepository.findAllByStatuses(RECOVERABLE_STATUSES))
                .willReturn(List.of(running, rollbackFailed));
        given(executionService.recoverForEmergency(
                running.getExperimentId(),
                running.getId()
        )).willReturn(successfulDetails(running));
        given(executionService.recoverForEmergency(
                rollbackFailed.getExperimentId(),
                rollbackFailed.getId()
        )).willReturn(ExperimentExecutionDetails.from(rollbackFailed));

        EmergencyStopResult result = emergencyStopService.activate();

        assertThat(result.requestedAt()).isEqualTo(NOW);
        assertThat(result.executions())
                .extracting(execution -> execution.outcome())
                .containsExactly(
                        EmergencyStopOutcome.RECOVERED,
                        EmergencyStopOutcome.ROLLBACK_FAILED
                );
        verify(executionRepository).findAllByStatuses(RECOVERABLE_STATUSES);
    }

    @Test
    void shouldContinueAfterOneExecutionThrows() {
        ExperimentExecution first = runningExecution("request-first");
        ExperimentExecution second = runningExecution("request-second");
        given(executionRepository.findAllByStatuses(RECOVERABLE_STATUSES))
                .willReturn(List.of(first, second));
        doThrow(new IllegalStateException("persistence failure"))
                .when(executionService)
                .recoverForEmergency(first.getExperimentId(), first.getId());
        given(executionService.recoverForEmergency(
                second.getExperimentId(),
                second.getId()
        )).willReturn(successfulDetails(second));

        EmergencyStopResult result = emergencyStopService.activate();

        assertThat(result.executions())
                .extracting(execution -> execution.outcome())
                .containsExactly(
                        EmergencyStopOutcome.PROCESSING_FAILED,
                        EmergencyStopOutcome.RECOVERED
                );
        verify(executionService).recoverForEmergency(
                second.getExperimentId(),
                second.getId()
        );
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = ExperimentExecutionStatus.class,
            names = {"PREPARING", "CREATE_UNCERTAIN"})
    void uncertainCreationRequiresManualInterventionWithoutCallingDestroy(ExperimentExecutionStatus state) {
        var uncertain = ExperimentExecution.prepare(UUID.randomUUID(), UUID.randomUUID(),
                1, "uncertain", NOW);
        if (state == ExperimentExecutionStatus.CREATE_UNCERTAIN) uncertain = uncertain.markCreateUncertain();
        given(executionRepository.findAllByStatuses(RECOVERABLE_STATUSES)).willReturn(List.of(uncertain));
        var result = emergencyStopService.activate();
        assertThat(result.executions()).extracting(execution -> execution.outcome())
                .containsExactly(EmergencyStopOutcome.MANUAL_INTERVENTION);
        org.mockito.Mockito.verifyNoInteractions(executionService);
    }

    private ExperimentExecutionDetails successfulDetails(
            ExperimentExecution execution
    ) {
        ExperimentExecution successful = execution.beginDestroy()
                .markSuccess(NOW);
        return ExperimentExecutionDetails.from(successful);
    }

    private ExperimentExecution runningExecution(String idempotencyKey) {
        return ExperimentExecution.prepare(
                UUID.randomUUID(),
                UUID.randomUUID(),
                1,
                idempotencyKey,
                NOW.minusSeconds(40)
        ).markRunning(
                "fake-" + UUID.randomUUID(),
                NOW.minusSeconds(30)
        );
    }
}
