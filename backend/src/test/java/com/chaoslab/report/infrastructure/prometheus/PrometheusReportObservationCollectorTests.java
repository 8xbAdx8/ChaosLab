package com.chaoslab.report.infrastructure.prometheus;

import com.chaoslab.report.application.port.PrometheusQueryClient;
import com.chaoslab.report.domain.ReportMetricsStatus;
import com.chaoslab.report.domain.ReportWindow;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PrometheusReportObservationCollectorTests {

    private static final Instant START = Instant.parse("2026-09-27T08:00:00Z");
    private static final ReportWindow WINDOW = new ReportWindow(
            "during", START, START.plusSeconds(30), ReportMetricsStatus.NOT_COLLECTED
    );

    @Test
    void completeWindowReportsZeroErrorsWhenNoFiveXxSeriesExists() {
        PrometheusQueryClient queries = (expression, at) -> {
            if (expression.contains("count_over_time")) return 6.0;
            if (expression.contains("min_over_time")) return 1.0;
            if (expression.contains("status=~")) return null;
            if (expression.contains("histogram_quantile")) return 0.04;
            return 12.0;
        };

        ReportWindow observed = new PrometheusReportObservationCollector(queries)
                .collect(List.of(WINDOW)).getFirst();

        assertThat(observed.metricsStatus()).isEqualTo(ReportMetricsStatus.OBSERVED);
        assertThat(observed.estimatedRequests()).isEqualTo(12.0);
        assertThat(observed.estimated5xx()).isEqualTo(0.0);
        assertThat(observed.errorRate()).isEqualTo(0.0);
        assertThat(observed.p95Seconds()).isEqualTo(0.04);
    }

    @Test
    void lowSampleCountNeverLooksLikeAHealthyZeroErrorWindow() {
        PrometheusQueryClient queries = (expression, at) -> 1.0;

        ReportWindow observed = new PrometheusReportObservationCollector(queries)
                .collect(List.of(WINDOW)).getFirst();

        assertThat(observed.metricsStatus())
                .isEqualTo(ReportMetricsStatus.INSUFFICIENT_DATA);
        assertThat(observed.errorRate()).isNull();
        assertThat(observed.p95Seconds()).isNull();
    }

    @Test
    void unavailablePrometheusIsMarkedInsufficient() {
        PrometheusQueryClient queries = (expression, at) -> {
            throw new IllegalStateException("offline");
        };

        ReportWindow observed = new PrometheusReportObservationCollector(queries)
                .collect(List.of(WINDOW)).getFirst();

        assertThat(observed.metricsStatus())
                .isEqualTo(ReportMetricsStatus.INSUFFICIENT_DATA);
        assertThat(observed.reason()).contains("unavailable");
    }
}
