package com.chaoslab.report.application;

import com.chaoslab.audit.application.AuditLogApplicationService;
import com.chaoslab.audit.application.dto.AuditLogDetails;
import com.chaoslab.audit.domain.AuditOperation;
import com.chaoslab.audit.domain.AuditResult;
import com.chaoslab.execution.application.port.ExperimentExecutionRepository;
import com.chaoslab.execution.domain.ExperimentExecution;
import com.chaoslab.execution.domain.ExperimentExecutionStatus;
import com.chaoslab.experiment.application.port.ExperimentRepository;
import com.chaoslab.experiment.domain.Experiment;
import com.chaoslab.report.application.port.ExperimentReportRepository;
import com.chaoslab.scenario.application.port.FaultScenarioRepository;
import com.chaoslab.scenario.domain.FaultScenario;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class ExperimentReportApplicationServiceTests {

    private static final UUID EXPERIMENT_ID = UUID.randomUUID();
    private static final UUID EXECUTION_ID = UUID.randomUUID();
    private static final UUID TARGET_ID = UUID.randomUUID();
    private static final UUID SCENARIO_ID = UUID.randomUUID();
    private static final Instant START = Instant.parse("2026-09-27T08:00:00Z");

    private final ExperimentReportRepository reports = mock(ExperimentReportRepository.class);
    private final ExperimentExecutionRepository executions = mock(ExperimentExecutionRepository.class);
    private final ExperimentRepository experiments = mock(ExperimentRepository.class);
    private final FaultScenarioRepository scenarios = mock(FaultScenarioRepository.class);
    private final AuditLogApplicationService audits = mock(AuditLogApplicationService.class);
    private final ExperimentReportApplicationService service =
            new ExperimentReportApplicationService(
                    reports, executions, experiments, scenarios, audits,
                    Clock.fixed(START.plusSeconds(90), ZoneOffset.UTC)
            );

    @Test
    void rejectsMissingRecoveryAuditWithoutSavingReport() {
        givenCompleteExecution();
        given(audits.findByExecution(EXPERIMENT_ID, EXECUTION_ID))
                .willReturn(List.of(audit(AuditOperation.START_EXPERIMENT, TARGET_ID, START)));

        assertThatThrownBy(() -> service.create(EXPERIMENT_ID, EXECUTION_ID, "first"))
                .isInstanceOf(ReportCreationRejectedException.class)
                .extracting("code")
                .isEqualTo("AUDIT_EVIDENCE_MISSING");
        verify(reports, never()).insert(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectsDifferentRecoveryTargetWithoutSavingReport() {
        givenCompleteExecution();
        given(audits.findByExecution(EXPERIMENT_ID, EXECUTION_ID))
                .willReturn(List.of(
                        audit(AuditOperation.START_EXPERIMENT, TARGET_ID, START),
                        audit(AuditOperation.DESTROY_EXPERIMENT,
                                UUID.randomUUID(), START.plusSeconds(30))
                ));

        assertThatThrownBy(() -> service.create(EXPERIMENT_ID, EXECUTION_ID, "first"))
                .isInstanceOf(ReportCreationRejectedException.class)
                .extracting("code")
                .isEqualTo("AUDIT_EVIDENCE_MISMATCH");
        verify(reports, never()).insert(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void invalidKeyIsRejectedBeforeDatabaseAccess() {
        assertThatThrownBy(() -> service.create(EXPERIMENT_ID, EXECUTION_ID, " "))
                .isInstanceOf(ReportCreationRejectedException.class)
                .extracting("code")
                .isEqualTo("INVALID_REPORT_GENERATION_KEY");
        verifyNoInteractions(reports, executions, experiments, audits);
    }

    private void givenCompleteExecution() {
        ExperimentExecution execution = mock(ExperimentExecution.class);
        given(execution.getExperimentId()).willReturn(EXPERIMENT_ID);
        given(execution.getStatus()).willReturn(ExperimentExecutionStatus.SUCCESS);
        given(execution.getStartedAt()).willReturn(START);
        given(execution.getFinishedAt()).willReturn(START.plusSeconds(30));
        given(executions.findById(EXECUTION_ID)).willReturn(Optional.of(execution));
        Experiment experiment = mock(Experiment.class);
        given(experiment.getTargetId()).willReturn(TARGET_ID);
        given(experiment.getScenarioId()).willReturn(SCENARIO_ID);
        given(experiments.findById(EXPERIMENT_ID)).willReturn(Optional.of(experiment));
        FaultScenario scenario = mock(FaultScenario.class);
        given(scenario.getCode()).willReturn("CPU_LOAD");
        given(scenarios.findById(SCENARIO_ID)).willReturn(Optional.of(scenario));
    }

    private AuditLogDetails audit(
            AuditOperation operation, UUID targetId, Instant occurredAt
    ) {
        return new AuditLogDetails(
                UUID.randomUUID(), "ANONYMOUS", operation,
                EXPERIMENT_ID, EXECUTION_ID, targetId,
                "CPU_LOAD", "{}", "127.0.0.1",
                AuditResult.SUCCESS, null, occurredAt
        );
    }
}
