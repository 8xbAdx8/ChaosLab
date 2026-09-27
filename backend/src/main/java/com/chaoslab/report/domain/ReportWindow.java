package com.chaoslab.report.domain;

import java.time.Instant;

public record ReportWindow(
        String phase,
        Instant start,
        Instant end,
        ReportMetricsStatus metricsStatus,
        Double scrapeSamples,
        Double estimatedRequests,
        Double estimated5xx,
        Double errorRate,
        Double p95Seconds,
        String reason
) {
    public ReportWindow(String phase, Instant start, Instant end, ReportMetricsStatus metricsStatus) {
        this(phase, start, end, metricsStatus, null, null, null, null, null, null);
    }

    public ReportWindow {
        if (!"before".equals(phase) && !"during".equals(phase) && !"after".equals(phase)) {
            throw new IllegalArgumentException("unknown report phase");
        }
        if (start == null || end == null || !end.isAfter(start) || metricsStatus == null) {
            throw new IllegalArgumentException("invalid report window");
        }
        for (Double number : new Double[]{scrapeSamples, estimatedRequests,
                estimated5xx, errorRate, p95Seconds}) {
            if (number != null && (!Double.isFinite(number) || number < 0)) {
                throw new IllegalArgumentException("report metrics must be finite and non-negative");
            }
        }
        if (metricsStatus == ReportMetricsStatus.OBSERVED
                && (scrapeSamples == null || estimatedRequests == null
                || estimated5xx == null || errorRate == null || p95Seconds == null
                || errorRate > 1 || reason != null)) {
            throw new IllegalArgumentException("observed window requires complete metrics");
        }
        if (metricsStatus == ReportMetricsStatus.INSUFFICIENT_DATA
                && (reason == null || reason.isBlank())) {
            throw new IllegalArgumentException("insufficient window requires a reason");
        }
        if (metricsStatus == ReportMetricsStatus.NOT_COLLECTED
                && (scrapeSamples != null || estimatedRequests != null
                || estimated5xx != null || errorRate != null || p95Seconds != null)) {
            throw new IllegalArgumentException("uncollected window must not contain metrics");
        }
    }
}
