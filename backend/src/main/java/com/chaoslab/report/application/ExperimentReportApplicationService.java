package com.chaoslab.report.application;

import com.chaoslab.audit.application.AuditLogApplicationService;
import com.chaoslab.audit.application.dto.AuditLogDetails;
import com.chaoslab.audit.domain.AuditOperation;
import com.chaoslab.audit.domain.AuditResult;
import com.chaoslab.execution.application.ExperimentExecutionNotFoundException;
import com.chaoslab.execution.application.port.ExperimentExecutionRepository;
import com.chaoslab.execution.domain.ExperimentExecution;
import com.chaoslab.execution.domain.ExperimentExecutionStatus;
import com.chaoslab.experiment.application.ExperimentNotFoundException;
import com.chaoslab.experiment.application.port.ExperimentRepository;
import com.chaoslab.experiment.domain.Experiment;
import com.chaoslab.report.application.port.ExperimentReportRepository;
import com.chaoslab.report.domain.ExperimentReport;
import com.chaoslab.report.domain.ReportConclusionStatus;
import com.chaoslab.report.domain.ReportExecutionMode;
import com.chaoslab.report.domain.ReportMetricsStatus;
import com.chaoslab.scenario.application.FaultScenarioNotFoundException;
import com.chaoslab.scenario.application.port.FaultScenarioRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class ExperimentReportApplicationService {

    private final ExperimentReportRepository reports;
    private final ExperimentExecutionRepository executions;
    private final ExperimentRepository experiments;
    private final FaultScenarioRepository scenarios;
    private final AuditLogApplicationService audits;
    private final Clock clock;

    public ExperimentReportApplicationService(
            ExperimentReportRepository reports,
            ExperimentExecutionRepository executions,
            ExperimentRepository experiments,
            FaultScenarioRepository scenarios,
            AuditLogApplicationService audits,
            Clock clock
    ) {
        this.reports = Objects.requireNonNull(reports);
        this.executions = Objects.requireNonNull(executions);
        this.experiments = Objects.requireNonNull(experiments);
        this.scenarios = Objects.requireNonNull(scenarios);
        this.audits = Objects.requireNonNull(audits);
        this.clock = Objects.requireNonNull(clock);
    }

    @Transactional
    public ReportCreationResult create(
            UUID experimentId, UUID executionId, String rawGenerationKey
    ) {
        Objects.requireNonNull(experimentId);
        Objects.requireNonNull(executionId);
        String generationKey = normalizeKey(rawGenerationKey);
        reports.lockExecution(executionId);
        var existing = reports.findByExecutionIdAndGenerationKey(
                executionId, generationKey
        );
        if (existing.isPresent()) {
            ExperimentReport report = existing.get();
            if (!report.experimentId().equals(experimentId)) {
                throw new ExperimentReportNotFoundException(report.id());
            }
            return new ReportCreationResult(report, false);
        }

        ExperimentExecution execution = executions.findById(executionId)
                .filter(found -> found.getExperimentId().equals(experimentId))
                .orElseThrow(() -> new ExperimentExecutionNotFoundException(executionId));
        if (execution.getStatus() != ExperimentExecutionStatus.SUCCESS
                || execution.getStartedAt() == null
                || execution.getFinishedAt() == null) {
            throw rejected("EXECUTION_NOT_RECOVERED",
                    "report requires a successfully recovered execution");
        }
        if (!execution.getFinishedAt().isAfter(execution.getStartedAt())) {
            throw rejected("INVALID_EXECUTION_WINDOW",
                    "execution must have a positive observation window");
        }
        Experiment experiment = experiments.findById(experimentId)
                .orElseThrow(() -> new ExperimentNotFoundException(experimentId));
        String scenarioCode = scenarios.findById(experiment.getScenarioId())
                .orElseThrow(() -> new FaultScenarioNotFoundException(
                        experiment.getScenarioId()
                ))
                .getCode();
        List<AuditLogDetails> logs = audits.findByExecution(experimentId, executionId);
        AuditLogDetails start = logs.stream()
                .filter(log -> log.operation() == AuditOperation.START_EXPERIMENT)
                .filter(log -> log.result() == AuditResult.SUCCESS)
                .min(Comparator.comparing(AuditLogDetails::occurredAt))
                .orElseThrow(() -> rejected("AUDIT_EVIDENCE_MISSING",
                        "successful start audit event is missing"));
        AuditLogDetails recovery = logs.stream()
                .filter(log -> log.operation() == AuditOperation.DESTROY_EXPERIMENT
                        || log.operation() == AuditOperation.AUTOMATIC_RECOVERY
                        || log.operation() == AuditOperation.EMERGENCY_RECOVERY)
                .filter(log -> log.result() == AuditResult.SUCCESS)
                .max(Comparator.comparing(AuditLogDetails::occurredAt))
                .orElseThrow(() -> rejected("AUDIT_EVIDENCE_MISSING",
                        "successful recovery audit event is missing"));
        if (start.targetId() == null
                || !belongsTo(start, experimentId, executionId)
                || !belongsTo(recovery, experimentId, executionId)
                || !start.targetId().equals(experiment.getTargetId())
                || !start.targetId().equals(recovery.targetId())
                || !scenarioCode.equals(start.scenarioCode())
                || start.occurredAt().isAfter(recovery.occurredAt())) {
            throw rejected("AUDIT_EVIDENCE_MISMATCH",
                    "start and recovery audit evidence does not match the experiment");
        }
        ReportExecutionMode mode = ("fake-" + executionId).equals(
                execution.getEngineExperimentId()
        ) ? ReportExecutionMode.SIMULATED : ReportExecutionMode.UNVERIFIED;
        ExperimentReport report = new ExperimentReport(
                UUID.randomUUID(), experimentId, executionId, generationKey,
                start.targetId(), scenarioCode, start.id(), recovery.id(),
                execution.getStartedAt(), execution.getFinishedAt(), clock.instant(),
                mode, ReportMetricsStatus.NOT_COLLECTED,
                ReportConclusionStatus.INSUFFICIENT_DATA,
                "platform metrics have not been collected; no steady-state or causal conclusion is available"
        );
        return new ReportCreationResult(reports.insert(report), true);
    }

    public ExperimentReport findById(
            UUID experimentId, UUID executionId, UUID reportId
    ) {
        return reports.findById(reportId)
                .filter(report -> report.experimentId().equals(experimentId)
                        && report.executionId().equals(executionId))
                .orElseThrow(() -> new ExperimentReportNotFoundException(reportId));
    }

    private String normalizeKey(String raw) {
        if (raw == null || raw.isBlank() || raw.length() > ExperimentReport.MAX_GENERATION_KEY_LENGTH) {
            throw rejected("INVALID_REPORT_GENERATION_KEY",
                    "Idempotency-Key must be 1 to 128 characters");
        }
        return raw.trim();
    }

    private boolean belongsTo(
            AuditLogDetails log, UUID experimentId, UUID executionId
    ) {
        return experimentId.equals(log.experimentId())
                && executionId.equals(log.executionId());
    }

    private ReportCreationRejectedException rejected(String code, String message) {
        return new ReportCreationRejectedException(code, message);
    }
}
