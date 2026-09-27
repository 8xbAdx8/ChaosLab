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
        String reason
) {
    public static final int MAX_GENERATION_KEY_LENGTH = 128;
    public static final int MAX_REASON_LENGTH = 500;

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
        if (metricsStatus == ReportMetricsStatus.NOT_COLLECTED
                && conclusionStatus != ReportConclusionStatus.INSUFFICIENT_DATA) {
            throw new IllegalArgumentException("uncollected metrics cannot support a conclusion");
        }
    }

    public List<ReportWindow> windows() {
        Duration duration = Duration.between(startedAt, finishedAt);
        return List.of(
                new ReportWindow("before", startedAt.minus(duration), startedAt, metricsStatus),
                new ReportWindow("during", startedAt, finishedAt, metricsStatus),
                new ReportWindow("after", finishedAt, finishedAt.plus(duration), metricsStatus)
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
