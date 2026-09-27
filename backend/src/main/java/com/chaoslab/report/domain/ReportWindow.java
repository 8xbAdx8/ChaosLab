package com.chaoslab.report.domain;

import java.time.Instant;

public record ReportWindow(
        String phase,
        Instant start,
        Instant end,
        ReportMetricsStatus metricsStatus
) {
}
