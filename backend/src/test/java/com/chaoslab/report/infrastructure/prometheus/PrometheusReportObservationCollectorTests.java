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
    void missingFiveXxSeriesNeverBecomesZero() {
        PrometheusQueryClient queries = (expression, at) -> {
            if (expression.startsWith("count(")) return 1.0;
            if (expression.contains("count_over_time")) return 6.0;
            if (expression.contains("min_over_time")) return 1.0;
            if (expression.contains("status=~")) return null;
            if (expression.contains("histogram_quantile")) return 0.04;
            return 120.0;
        };

        ReportWindow observed = new PrometheusReportObservationCollector(queries)
                .collect(List.of(WINDOW)).getFirst();

        assertThat(observed.metricsStatus()).isEqualTo(ReportMetricsStatus.INSUFFICIENT_DATA);
        assertThat(observed.estimatedRequests()).isEqualTo(120.0);
        assertThat(observed.estimated5xx()).isNull();
        assertThat(observed.errorRate()).isNull();
        assertThat(observed.p95Seconds()).isNull();
    }

    private ReportWindow observe(double subjects, double requests, double errors, double histogram) {
        return new PrometheusReportObservationCollector((expression, at) -> {
            assertThat(at).isEqualTo(WINDOW.end());
            assertThat(expression).contains("job=\"order-service\"", "instance=\"order-service:8080\"", "[30000ms]");
            if (expression.startsWith("count(")) return subjects;
            if (expression.contains("count_over_time")) return 6.0;
            if (expression.contains("min_over_time")) return 1.0;
            assertThat(expression).contains("uri=\"/orders/{orderId}\"");
            if (expression.contains("status=~")) return errors;
            if (expression.contains("le=\"+Inf\"")) return histogram;
            if (expression.contains("histogram_quantile")) return 0.04;
            return requests;
        }).collect(List.of(WINDOW)).getFirst();
    }

    @Test void completeMeasuredWindowKeepsRealZeroAndCollectorVersion() {
        var window = observe(1, 120, 0, 120);
        assertThat(window.metricsStatus()).isEqualTo(ReportMetricsStatus.OBSERVED);
        assertThat(window.errorRate()).isZero();
        assertThat(window.p95Seconds()).isEqualTo(0.04);
        assertThat(window.collectionVersion()).isEqualTo("PROMETHEUS_HTTP_V2");
        assertThat(observe(1, 120, 12, 120).errorRate()).isEqualTo(0.1);
    }

    @Test void tooFewRequestsAmbiguousSubjectAndInconsistentCountersRejectComparison() {
        for (var window : List.of(observe(2, 120, 0, 120), observe(0, 120, 0, 120),
                observe(1, 1, 0, 1), observe(1, 120, 130, 120), observe(1, 120, 0, 10),
                observe(1, 120, Double.NaN, 120))) {
            assertThat(window.metricsStatus()).isEqualTo(ReportMetricsStatus.INSUFFICIENT_DATA);
            assertThat(window.errorRate()).isNull();
            assertThat(window.p95Seconds()).isNull();
        }
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
