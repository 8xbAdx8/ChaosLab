package com.chaoslab.report.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record ExperimentReport(
        UUID id,
        UUID experimentId,
        UUID executionId,
        String generationKey,
        UUID targetId,
        String scenarioCode,
        UUID startAuditId,
        UUID recoveryAuditId,
        Instant startedAt,
        Instant finishedAt,
        Instant generatedAt,
        ReportExecutionMode executionMode,
        ReportMetricsStatus metricsStatus,
        ReportConclusionStatus conclusionStatus,
        String reason,
        ReportBindingStatus bindingStatus,
        String containerId,
        String imageId,
        List<ReportWindow> observations
) {
    public static final int MAX_GENERATION_KEY_LENGTH = 128;
    public static final int MAX_REASON_LENGTH = 500;

    public ExperimentReport(
            UUID id, UUID experimentId, UUID executionId, String generationKey,
            UUID targetId, String scenarioCode, UUID startAuditId, UUID recoveryAuditId,
            Instant startedAt, Instant finishedAt, Instant generatedAt,
            ReportExecutionMode executionMode, ReportMetricsStatus metricsStatus,
            ReportConclusionStatus conclusionStatus, String reason
    ) {
        this(id, experimentId, executionId, generationKey, targetId, scenarioCode,
                startAuditId, recoveryAuditId, startedAt, finishedAt, generatedAt,
                executionMode, metricsStatus, conclusionStatus, reason,
                ReportBindingStatus.NOT_VERIFIED, null, null, null);
    }

    public ExperimentReport {
        Objects.requireNonNull(id);
        Objects.requireNonNull(experimentId);
        Objects.requireNonNull(executionId);
        generationKey = requiredText(generationKey, MAX_GENERATION_KEY_LENGTH);
        Objects.requireNonNull(targetId);
        scenarioCode = requiredText(scenarioCode, 64);
        Objects.requireNonNull(startAuditId);
        Objects.requireNonNull(recoveryAuditId);
        Objects.requireNonNull(startedAt);
        Objects.requireNonNull(finishedAt);
        if (!finishedAt.isAfter(startedAt)) {
            throw new IllegalArgumentException("report window must have positive duration");
        }
        Objects.requireNonNull(generatedAt);
        Objects.requireNonNull(executionMode);
        Objects.requireNonNull(metricsStatus);
        Objects.requireNonNull(conclusionStatus);
        reason = requiredText(reason, MAX_REASON_LENGTH);
        Objects.requireNonNull(bindingStatus);
        if (bindingStatus == ReportBindingStatus.VERIFIED_LOCAL_DEMO) {
            if (containerId == null || !containerId.matches("[0-9a-f]{64}")
                    || imageId == null || !imageId.matches("sha256:[0-9a-f]{64}")) {
                throw new IllegalArgumentException("verified binding requires full Docker identities");
            }
        } else if (containerId != null || imageId != null) {
            throw new IllegalArgumentException("unverified report cannot retain Docker identities");
        }
        observations = observations == null ? null : List.copyOf(observations);
        if (observations != null && (observations.size() != 3
                || !"before".equals(observations.get(0).phase())
                || !"during".equals(observations.get(1).phase())
                || !"after".equals(observations.get(2).phase())
                || !observations.get(0).end().equals(startedAt)
                || !observations.get(1).start().equals(startedAt)
                || !observations.get(1).end().equals(finishedAt)
                || !observations.get(2).start().equals(finishedAt))) {
            throw new IllegalArgumentException("report requires three ordered windows");
        }
        if (metricsStatus != ReportMetricsStatus.OBSERVED
                && conclusionStatus != ReportConclusionStatus.INSUFFICIENT_DATA) {
            throw new IllegalArgumentException("incomplete metrics cannot support a conclusion");
        }
        if (metricsStatus == ReportMetricsStatus.OBSERVED
                && (bindingStatus != ReportBindingStatus.VERIFIED_LOCAL_DEMO
                || observations == null
                || observations.stream().anyMatch(window ->
                        window.metricsStatus() != ReportMetricsStatus.OBSERVED))) {
            throw new IllegalArgumentException("observed report requires three bound windows");
        }
        if (metricsStatus == ReportMetricsStatus.OBSERVED
                && ((executionMode == ReportExecutionMode.SIMULATED
                        && conclusionStatus != ReportConclusionStatus.SIMULATED_ONLY)
                || (executionMode == ReportExecutionMode.UNVERIFIED
                        && conclusionStatus != ReportConclusionStatus.EXECUTION_UNVERIFIED))) {
            throw new IllegalArgumentException("observation must not imply a verified fault effect");
        }
    }

    public List<ReportWindow> windows() {
        if (observations != null) {
            return observations;
        }
        Duration duration = Duration.between(startedAt, finishedAt);
        return List.of(
                new ReportWindow("before", startedAt.minus(duration), startedAt,
                        ReportMetricsStatus.NOT_COLLECTED),
                new ReportWindow("during", startedAt, finishedAt,
                        ReportMetricsStatus.NOT_COLLECTED),
                new ReportWindow("after", finishedAt, finishedAt.plus(duration),
                        ReportMetricsStatus.NOT_COLLECTED)
        );
    }

    private static String requiredText(String value, int maximum) {
        Objects.requireNonNull(value);
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.length() > maximum) {
            throw new IllegalArgumentException("report text is blank or too long");
        }
        return normalized;
    }
}
