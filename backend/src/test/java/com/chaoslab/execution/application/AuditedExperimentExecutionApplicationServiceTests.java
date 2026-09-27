package com.chaoslab.execution.application;

import com.chaoslab.audit.application.DangerousOperationAuditor;
import com.chaoslab.audit.application.model.AuditContext;
import com.chaoslab.audit.application.model.AuditIntent;
import com.chaoslab.audit.application.model.AuditSubject;
import com.chaoslab.audit.domain.AuditOperation;
import com.chaoslab.audit.domain.AuditResult;
import com.chaoslab.execution.application.dto.ExperimentExecutionDetails;
import com.chaoslab.execution.application.dto.StartExperimentExecutionResult;
import com.chaoslab.execution.application.port.ExecutionOperationMetrics;
import com.chaoslab.execution.domain.ExperimentExecutionStatus;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AuditedExperimentExecutionApplicationServiceTests {

    private static final UUID EXPERIMENT_ID = UUID.randomUUID();
    private static final UUID EXECUTION_ID = UUID.randomUUID();
    private static final AuditSubject SUBJECT = new AuditSubject(
            EXPERIMENT_ID,
            null,
            UUID.randomUUID(),
            "CPU_LOAD",
            "{\"percent\":40}"
    );
    private static final AuditContext CONTEXT = new AuditContext(
            "ANONYMOUS",
            "127.0.0.1"
    );

    private final ExperimentExecutionApplicationService delegate =
            mock(ExperimentExecutionApplicationService.class);
    private final DangerousOperationAuditor auditor =
            mock(DangerousOperationAuditor.class);
    private final ExecutionOperationMetrics metrics = mock(ExecutionOperationMetrics.class);
    private final AuditedExperimentExecutionApplicationService service =
            new AuditedExperimentExecutionApplicationService(delegate, auditor, metrics);

    @Test
    void shouldAuditCommittedStartWithCreatedExecutionId() {
        AuditIntent intent = intent(AuditOperation.START_EXPERIMENT);
        StartExperimentExecutionResult result = new StartExperimentExecutionResult(
                details(ExperimentExecutionStatus.RUNNING),
                true
        );
        given(auditor.prepare(
                AuditOperation.START_EXPERIMENT,
                EXPERIMENT_ID,
                null
        )).willReturn(intent);
        given(delegate.start(EXPERIMENT_ID, "request-1")).willReturn(result);

        assertThat(service.start(EXPERIMENT_ID, "request-1")).isSameAs(result);

        ArgumentCaptor<AuditIntent> intentCaptor =
                ArgumentCaptor.forClass(AuditIntent.class);
        verify(auditor).complete(
                intentCaptor.capture(),
                org.mockito.ArgumentMatchers.eq(AuditResult.SUCCESS),
                org.mockito.ArgumentMatchers.isNull()
        );
        assertThat(intentCaptor.getValue().subject().executionId())
                .isEqualTo(EXECUTION_ID);
        verify(metrics).record(
                AuditOperation.START_EXPERIMENT,
                ExecutionOperationMetrics.Result.SUCCESS
        );
    }

    @Test
    void shouldAuditRejectedStartAndRethrowOriginalException() {
        AuditIntent intent = intent(AuditOperation.START_EXPERIMENT);
        ExperimentExecutionStartRejectedException rejection =
                new ExperimentExecutionStartRejectedException(
                        "SAFETY_CHECK_REJECTED",
                        "unsafe",
                        List.of()
                );
        given(auditor.prepare(
                AuditOperation.START_EXPERIMENT,
                EXPERIMENT_ID,
                null
        )).willReturn(intent);
        given(delegate.start(EXPERIMENT_ID, "request-2")).willThrow(rejection);

        assertThatThrownBy(() -> service.start(EXPERIMENT_ID, "request-2"))
                .isSameAs(rejection);
        verify(auditor).complete(
                intent,
                AuditResult.REJECTED,
                "SAFETY_CHECK_REJECTED"
        );
        verify(metrics).record(
                AuditOperation.START_EXPERIMENT,
                ExecutionOperationMetrics.Result.REJECTED
        );
    }

    @Test
    void shouldDistinguishAutomaticRecoveryFromManualDestroy() {
        AuditIntent intent = intent(AuditOperation.AUTOMATIC_RECOVERY);
        ExperimentExecutionDetails result = details(
                ExperimentExecutionStatus.SUCCESS
        );
        given(auditor.prepare(
                AuditOperation.AUTOMATIC_RECOVERY,
                EXPERIMENT_ID,
                EXECUTION_ID
        )).willReturn(intent);
        given(delegate.destroy(EXPERIMENT_ID, EXECUTION_ID)).willReturn(result);

        assertThat(service.recoverAutomatically(EXPERIMENT_ID, EXECUTION_ID))
                .isSameAs(result);
        verify(auditor).complete(intent, AuditResult.SUCCESS, null);
        verify(metrics).record(
                AuditOperation.AUTOMATIC_RECOVERY,
                ExecutionOperationMetrics.Result.SUCCESS
        );
    }

    @Test
    void shouldPreserveOperationFailureWhenFailureAuditAlsoFails() {
        AuditIntent intent = intent(AuditOperation.DESTROY_EXPERIMENT);
        IllegalStateException operationFailure =
                new IllegalStateException("database unavailable");
        IllegalStateException auditFailure =
                new IllegalStateException("audit unavailable");
        given(auditor.prepare(
                AuditOperation.DESTROY_EXPERIMENT,
                EXPERIMENT_ID,
                EXECUTION_ID
        )).willReturn(intent);
        given(delegate.destroy(EXPERIMENT_ID, EXECUTION_ID))
                .willThrow(operationFailure);
        doThrow(auditFailure).when(auditor).complete(
                intent,
                AuditResult.FAILED,
                "IllegalStateException"
        );

        assertThatThrownBy(() -> service.destroy(EXPERIMENT_ID, EXECUTION_ID))
                .isSameAs(operationFailure);
        assertThat(operationFailure.getSuppressed()).containsExactly(auditFailure);
        verify(metrics).record(
                AuditOperation.DESTROY_EXPERIMENT,
                ExecutionOperationMetrics.Result.FAILED
        );
    }

    @Test
    void shouldCountIdempotentStartAsReplay() {
        AuditIntent intent = intent(AuditOperation.START_EXPERIMENT);
        StartExperimentExecutionResult result = new StartExperimentExecutionResult(
                details(ExperimentExecutionStatus.RUNNING),
                false
        );
        given(auditor.prepare(AuditOperation.START_EXPERIMENT, EXPERIMENT_ID, null))
                .willReturn(intent);
        given(delegate.start(EXPERIMENT_ID, "request-1")).willReturn(result);

        assertThat(service.start(EXPERIMENT_ID, "request-1")).isSameAs(result);
        verify(metrics).record(
                AuditOperation.START_EXPERIMENT,
                ExecutionOperationMetrics.Result.REPLAYED
        );
    }

    private AuditIntent intent(AuditOperation operation) {
        return new AuditIntent(CONTEXT, operation, SUBJECT);
    }

    private ExperimentExecutionDetails details(
            ExperimentExecutionStatus status
    ) {
        Instant now = Instant.parse("2026-08-28T00:00:00Z");
        return new ExperimentExecutionDetails(
                EXECUTION_ID,
                EXPERIMENT_ID,
                1,
                "request",
                status,
                "fake-1",
                null,
                now,
                now,
                status == ExperimentExecutionStatus.SUCCESS ? now : null,
                1
        );
    }
}
