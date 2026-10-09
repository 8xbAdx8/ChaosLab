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
import com.chaoslab.report.application.port.ReportObservationCollector;
import com.chaoslab.report.domain.ExperimentReport;
import com.chaoslab.report.domain.ReportBindingStatus;
import com.chaoslab.report.domain.ReportConclusionStatus;
import com.chaoslab.report.domain.ReportExecutionMode;
import com.chaoslab.report.domain.ReportMetricsStatus;
import com.chaoslab.report.domain.ReportWindow;
import com.chaoslab.safety.application.model.TargetIdentityVerification;
import com.chaoslab.safety.application.model.VerifiedDockerTarget;
import com.chaoslab.safety.application.port.TargetIdentityVerifier;
import com.chaoslab.scenario.application.FaultScenarioNotFoundException;
import com.chaoslab.scenario.application.port.FaultScenarioRepository;
import com.chaoslab.target.application.TargetNotFoundException;
import com.chaoslab.target.application.port.TargetRepository;
import com.chaoslab.target.domain.Target;
import com.chaoslab.target.domain.TargetType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
    private final TargetRepository targets;
    private final TargetIdentityVerifier bindingVerifier;
    private final ReportObservationCollector observations;
    private final Clock clock;

    public ExperimentReportApplicationService(
            ExperimentReportRepository reports,
            ExperimentExecutionRepository executions,
            ExperimentRepository experiments,
            FaultScenarioRepository scenarios,
            AuditLogApplicationService audits,
            TargetRepository targets,
            TargetIdentityVerifier bindingVerifier,
            ReportObservationCollector observations,
            Clock clock
    ) {
        this.reports = Objects.requireNonNull(reports);
        this.executions = Objects.requireNonNull(executions);
        this.experiments = Objects.requireNonNull(experiments);
        this.scenarios = Objects.requireNonNull(scenarios);
        this.audits = Objects.requireNonNull(audits);
        this.targets = Objects.requireNonNull(targets);
        this.bindingVerifier = Objects.requireNonNull(bindingVerifier);
        this.observations = Objects.requireNonNull(observations);
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
        // Future SUCCESS is committed only after the adapter's entire production
        // contract, including active provenance. Do not reinterpret/backfill
        // historical R2 rows here; report generation does not certify native cause.
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
        Target target = targets.findById(start.targetId())
                .orElseThrow(() -> new TargetNotFoundException(start.targetId()));
        Instant startedAt = execution.getStartedAt();
        Instant finishedAt = execution.getFinishedAt();
        Duration duration = Duration.between(startedAt, finishedAt);
        Instant baselineStart = startedAt.minus(duration);
        List<ReportWindow> emptyWindows = List.of(
                new ReportWindow("before", baselineStart, startedAt,
                        ReportMetricsStatus.NOT_COLLECTED),
                new ReportWindow("during", startedAt, finishedAt,
                        ReportMetricsStatus.NOT_COLLECTED),
                new ReportWindow("after", finishedAt, finishedAt.plus(duration),
                        ReportMetricsStatus.NOT_COLLECTED)
        );
        ReportBindingStatus bindingStatus = ReportBindingStatus.NOT_VERIFIED;
        String containerId = null;
        String imageId = null;
        List<ReportWindow> windows = emptyWindows;
        ReportMetricsStatus metricsStatus = ReportMetricsStatus.NOT_COLLECTED;
        String reason = "target and metrics binding was not verified";
        if (target.getType() == TargetType.DOCKER_CONTAINER) {
            TargetIdentityVerification binding;
            try {
                binding = bindingVerifier.verifyForWindow(target, baselineStart);
            } catch (RuntimeException exception) {
                binding = TargetIdentityVerification.rejected(
                        "historical target binding is unavailable"
                );
            }
            VerifiedDockerTarget identity = binding.verified() ? binding.identity() : null;
            if (identity != null && identity.targetId().equals(target.getId())) {
                bindingStatus = ReportBindingStatus.VERIFIED_LOCAL_DEMO;
                containerId = identity.containerId();
                imageId = identity.imageId();
                if (clock.instant().isBefore(finishedAt.plus(duration).plusSeconds(5))) {
                    reason = "recovery observation window has not finished";
                } else {
                    try {
                        windows = observations.collect(emptyWindows);
                        if (windows == null || windows.size() != 3
                                || !windows.get(0).phase().equals("before")
                                || !windows.get(1).phase().equals("during")
                                || !windows.get(2).phase().equals("after")
                                || !windows.get(0).start().equals(emptyWindows.get(0).start())
                                || !windows.get(0).end().equals(startedAt)
                                || !windows.get(1).start().equals(startedAt)
                                || !windows.get(1).end().equals(finishedAt)
                                || !windows.get(2).start().equals(finishedAt)
                                || !windows.get(2).end().equals(emptyWindows.get(2).end())) {
                            throw new IllegalStateException("collector returned incomplete windows");
                        }
                    } catch (RuntimeException exception) {
                        windows = emptyWindows.stream()
                                .map(window -> new ReportWindow(
                                        window.phase(), window.start(), window.end(),
                                        ReportMetricsStatus.INSUFFICIENT_DATA,
                                        null, null, null, null, null,
                                        "Prometheus observation is unavailable"
                                )).toList();
                    }
                    metricsStatus = windows.stream().allMatch(window ->
                            window.metricsStatus() == ReportMetricsStatus.OBSERVED)
                            ? ReportMetricsStatus.OBSERVED
                            : ReportMetricsStatus.INSUFFICIENT_DATA;
                    reason = metricsStatus == ReportMetricsStatus.OBSERVED
                            ? mode == ReportExecutionMode.SIMULATED
                                    ? "metrics were observed, but the execution was simulated; no fault-effect conclusion"
                                    : "metrics were observed, but real execution was not verified"
                            : "one or more observation windows have insufficient data";
                }
            } else {
                reason = "historical Demo container and metrics binding was not verified";
            }
        }
        ReportConclusionStatus conclusion = metricsStatus == ReportMetricsStatus.OBSERVED
                ? mode == ReportExecutionMode.SIMULATED
                        ? ReportConclusionStatus.SIMULATED_ONLY
                        : ReportConclusionStatus.EXECUTION_UNVERIFIED
                : ReportConclusionStatus.INSUFFICIENT_DATA;
        ExperimentReport report = new ExperimentReport(
                UUID.randomUUID(), experimentId, executionId, generationKey,
                start.targetId(), scenarioCode, start.id(), recovery.id(),
                startedAt, finishedAt, clock.instant(), mode, metricsStatus,
                conclusion, reason, bindingStatus, containerId, imageId, windows
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
