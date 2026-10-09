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
import com.chaoslab.report.application.port.ReportObservationCollector;
import com.chaoslab.report.domain.ExperimentReport;
import com.chaoslab.report.domain.ReportExecutionMode;
import com.chaoslab.report.domain.ReportBindingStatus;
import com.chaoslab.report.domain.ReportConclusionStatus;
import com.chaoslab.report.domain.ReportMetricsStatus;
import com.chaoslab.report.domain.ReportWindow;
import com.chaoslab.safety.application.model.TargetIdentityVerification;
import com.chaoslab.safety.application.model.VerifiedDockerTarget;
import com.chaoslab.safety.application.port.TargetIdentityVerifier;
import com.chaoslab.scenario.application.port.FaultScenarioRepository;
import com.chaoslab.scenario.domain.FaultScenario;
import com.chaoslab.target.application.port.TargetRepository;
import com.chaoslab.target.domain.Target;
import com.chaoslab.target.domain.TargetEnvironment;
import com.chaoslab.target.domain.TargetType;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
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
    private final TargetRepository targets = mock(TargetRepository.class);
    private final TargetIdentityVerifier bindingVerifier = mock(TargetIdentityVerifier.class);
    private final ReportObservationCollector observations = mock(ReportObservationCollector.class);
    private final ExperimentReportApplicationService service =
            new ExperimentReportApplicationService(
                    reports, executions, experiments, scenarios, audits,
                    targets, bindingVerifier, observations,
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
        verifyNoInteractions(reports, executions, experiments, audits,
                targets, bindingVerifier, observations);
    }

    @Test
    void observedDemoMetricsRemainSimulationOnlyAndUseHistoricalBinding() {
        ExperimentExecution execution = givenCompleteExecution();
        given(execution.getEngineExperimentId()).willReturn("fake-" + EXECUTION_ID);
        givenDockerTarget();
        given(audits.findByExecution(EXPERIMENT_ID, EXECUTION_ID)).willReturn(List.of(
                audit(AuditOperation.START_EXPERIMENT, TARGET_ID, START),
                audit(AuditOperation.DESTROY_EXPERIMENT, TARGET_ID, START.plusSeconds(30))
        ));
        String containerId = "a".repeat(64);
        String imageId = "sha256:" + "b".repeat(64);
        given(bindingVerifier.verifyForWindow(any(), any())).willReturn(
                TargetIdentityVerification.verified(new VerifiedDockerTarget(
                        TARGET_ID, containerId, imageId, "order-service")));
        given(observations.collect(any())).willAnswer(invocation ->
                ((List<ReportWindow>) invocation.getArgument(0)).stream()
                        .map(window -> new ReportWindow(window.phase(), window.start(),
                                window.end(), ReportMetricsStatus.OBSERVED,
                                6.0, 10.0, 1.0, 0.1, 0.2, null))
                        .toList());
        when(reports.insert(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ExperimentReport report = service.create(EXPERIMENT_ID, EXECUTION_ID, "observed").report();

        assertThat(report.bindingStatus()).isEqualTo(ReportBindingStatus.VERIFIED_LOCAL_DEMO);
        assertThat(report.containerId()).isEqualTo(containerId);
        assertThat(report.metricsStatus()).isEqualTo(ReportMetricsStatus.OBSERVED);
        assertThat(report.conclusionStatus()).isEqualTo(ReportConclusionStatus.SIMULATED_ONLY);
        assertThat(report.windows()).hasSize(3);
        verify(bindingVerifier).verifyForWindow(any(), org.mockito.ArgumentMatchers.eq(START.minusSeconds(30)));
    }

    @Test
    void rejectedHistoricalBindingDoesNotQueryPrometheus() {
        givenCompleteExecution();
        givenDockerTarget();
        given(audits.findByExecution(EXPERIMENT_ID, EXECUTION_ID)).willReturn(List.of(
                audit(AuditOperation.START_EXPERIMENT, TARGET_ID, START),
                audit(AuditOperation.DESTROY_EXPERIMENT, TARGET_ID, START.plusSeconds(30))
        ));
        given(bindingVerifier.verifyForWindow(any(), any())).willReturn(
                TargetIdentityVerification.rejected("container was recreated"));
        when(reports.insert(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ExperimentReport report = service.create(EXPERIMENT_ID, EXECUTION_ID, "unbound").report();

        assertThat(report.bindingStatus()).isEqualTo(ReportBindingStatus.NOT_VERIFIED);
        assertThat(report.metricsStatus()).isEqualTo(ReportMetricsStatus.NOT_COLLECTED);
        verifyNoInteractions(observations);
    }

    private void givenDockerTarget() {
        given(targets.findById(TARGET_ID)).willReturn(Optional.of(Target.register(
                TARGET_ID, "demo-order-service", TargetType.DOCKER_CONTAINER,
                TargetEnvironment.CHAOS_LAB)));
    }

    @Test void activeRecoveryUnconfirmedExecutionCannotGenerateRecoveredReport() {
        ExperimentExecution execution = mock(ExperimentExecution.class);
        given(execution.getExperimentId()).willReturn(EXPERIMENT_ID);
        given(execution.getStatus()).willReturn(ExperimentExecutionStatus.ROLLBACK_FAILED);
        given(executions.findById(EXECUTION_ID)).willReturn(Optional.of(execution));
        assertThatThrownBy(() -> service.create(EXPERIMENT_ID, EXECUTION_ID, "unconfirmed"))
                .isInstanceOf(ReportCreationRejectedException.class).extracting("code").isEqualTo("EXECUTION_NOT_RECOVERED");
        verify(reports, never()).insert(any());
        verifyNoInteractions(observations, audits);
    }

    private ExperimentExecution givenCompleteExecution() {
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
        Target target = Target.register(TARGET_ID, "unit-target",
                TargetType.JAVA_APPLICATION, TargetEnvironment.CHAOS_LAB);
        given(targets.findById(TARGET_ID)).willReturn(Optional.of(target));
        return execution;
    }

    @Test void bladeCoreSuccessDoesNotVerifyCauseOrAbsentReportMetrics() {
        var execution = givenCompleteExecution();
        given(execution.getEngineExperimentId()).willReturn("blade-"+EXECUTION_ID);
        given(audits.findByExecution(EXPERIMENT_ID, EXECUTION_ID)).willReturn(List.of(
                audit(AuditOperation.START_EXPERIMENT, TARGET_ID, START),
                audit(AuditOperation.DESTROY_EXPERIMENT, TARGET_ID, START.plusSeconds(30))));
        when(reports.insert(any())).thenAnswer(call -> call.getArgument(0));
        var report = service.create(EXPERIMENT_ID, EXECUTION_ID, "core-not-cause").report();
        assertThat(report.executionMode()).isEqualTo(ReportExecutionMode.UNVERIFIED);
        assertThat(report.metricsStatus()).isEqualTo(ReportMetricsStatus.NOT_COLLECTED);
        assertThat(report.conclusionStatus()).isEqualTo(ReportConclusionStatus.INSUFFICIENT_DATA);
        assertThat(report.reason()).contains("does not verify recovery cause");
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
