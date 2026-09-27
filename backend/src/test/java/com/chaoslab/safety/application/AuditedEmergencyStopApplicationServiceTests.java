package com.chaoslab.safety.application;

import com.chaoslab.audit.application.DangerousOperationAuditor;
import com.chaoslab.audit.application.model.AuditContext;
import com.chaoslab.audit.application.model.AuditIntent;
import com.chaoslab.audit.application.model.AuditSubject;
import com.chaoslab.audit.domain.AuditOperation;
import com.chaoslab.audit.domain.AuditResult;
import com.chaoslab.execution.application.port.ExecutionOperationMetrics;
import com.chaoslab.safety.application.dto.EmergencyStopExecutionResult;
import com.chaoslab.safety.application.dto.EmergencyStopOutcome;
import com.chaoslab.safety.application.dto.EmergencyStopResult;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AuditedEmergencyStopApplicationServiceTests {

    private static final AuditIntent INTENT = new AuditIntent(
            new AuditContext("ANONYMOUS", "127.0.0.1"),
            AuditOperation.EMERGENCY_STOP,
            new AuditSubject(null, null, null, null, null)
    );

    private final EmergencyStopApplicationService delegate =
            mock(EmergencyStopApplicationService.class);
    private final DangerousOperationAuditor auditor =
            mock(DangerousOperationAuditor.class);
    private final ExecutionOperationMetrics metrics = mock(ExecutionOperationMetrics.class);
    private final AuditedEmergencyStopApplicationService service =
            new AuditedEmergencyStopApplicationService(delegate, auditor, metrics);

    @Test
    void shouldAuditSuccessfulEmergencyStop() {
        EmergencyStopResult result = result(EmergencyStopOutcome.RECOVERED);
        given(auditor.prepareGlobal(AuditOperation.EMERGENCY_STOP))
                .willReturn(INTENT);
        given(delegate.activate()).willReturn(result);

        assertThat(service.activate()).isSameAs(result);
        verify(auditor).complete(INTENT, AuditResult.SUCCESS, null);
        verify(metrics).record(
                AuditOperation.EMERGENCY_STOP,
                ExecutionOperationMetrics.Result.SUCCESS
        );
    }

    @Test
    void shouldAuditIncompleteEmergencyStopAsFailed() {
        EmergencyStopResult result = result(
                EmergencyStopOutcome.ROLLBACK_FAILED
        );
        given(auditor.prepareGlobal(AuditOperation.EMERGENCY_STOP))
                .willReturn(INTENT);
        given(delegate.activate()).willReturn(result);

        assertThat(service.activate()).isSameAs(result);
        verify(auditor).complete(
                INTENT,
                AuditResult.FAILED,
                "RECOVERY_INCOMPLETE"
        );
        verify(metrics).record(
                AuditOperation.EMERGENCY_STOP,
                ExecutionOperationMetrics.Result.FAILED
        );
    }

    @Test
    void shouldAuditUnexpectedEmergencyStopFailureAndRethrowIt() {
        IllegalStateException failure = new IllegalStateException("failed");
        given(auditor.prepareGlobal(AuditOperation.EMERGENCY_STOP))
                .willReturn(INTENT);
        given(delegate.activate()).willThrow(failure);

        assertThatThrownBy(service::activate).isSameAs(failure);
        verify(auditor).complete(
                INTENT,
                AuditResult.FAILED,
                "IllegalStateException"
        );
        verify(metrics).record(
                AuditOperation.EMERGENCY_STOP,
                ExecutionOperationMetrics.Result.FAILED
        );
    }

    private EmergencyStopResult result(EmergencyStopOutcome outcome) {
        return new EmergencyStopResult(
                Instant.parse("2026-08-28T00:00:00Z"),
                List.of(new EmergencyStopExecutionResult(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        outcome
                ))
        );
    }
}
