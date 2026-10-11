package com.chaoslab.report.infrastructure.prometheus;

import com.chaoslab.report.application.port.PrometheusQueryClient;
import com.chaoslab.report.application.port.ReportObservationCollector;
import com.chaoslab.report.domain.ReportMetricsStatus;
import com.chaoslab.report.domain.ReportWindow;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

@Component
public class PrometheusReportObservationCollector implements ReportObservationCollector {

    private static final int SCRAPE_SECONDS = 5;
    private static final String SELECTOR =
            "job=\"order-service\",instance=\"order-service:8080\",uri=\"/orders/{orderId}\"";
    public static final String COLLECTION_VERSION = "PROMETHEUS_HTTP_V2";
    // A teaching minimum, not a statistical confidence guarantee for a quantile.
    private static final int MINIMUM_REQUESTS = 20;
    private static final String UP = "job=\"order-service\",instance=\"order-service:8080\"";

    private final PrometheusQueryClient queries;

    public PrometheusReportObservationCollector(PrometheusQueryClient queries) {
        this.queries = Objects.requireNonNull(queries);
    }

    @Override
    public List<ReportWindow> collect(List<ReportWindow> windows) {
        if (windows == null || windows.isEmpty() || windows.size() > 3) {
            throw new IllegalArgumentException("at most three observation windows are allowed");
        }
        return windows.stream().map(this::observe).toList();
    }

    private ReportWindow observe(ReportWindow window) {
        double seconds = Duration.between(window.start(), window.end()).toMillis() / 1000.0;
        if (seconds < 20 || seconds > 3600) {
            return insufficient(window, null, null,
                    "observation window must be 20 to 3600 seconds");
        }
        String range = Duration.between(window.start(), window.end()).toMillis() + "ms";
        try {
            Double series = queries.query("count(count_over_time(up{" + UP + "}[" + range + "]))", window.end());
            if (series == null || series != 1.0) {
                return insufficient(window, null, null, "scrape subject is missing or not unique");
            }
            Double samples = queries.query(
                    "sum(count_over_time(up{" + UP + "}[" + range + "]))",
                    window.end()
            );
            Double availability = queries.query(
                    "min(min_over_time(up{" + UP + "}[" + range + "]))",
                    window.end()
            );
            int minimum = Math.max(3, (int) Math.floor(seconds / SCRAPE_SECONDS * 0.8));
            if (!valid(samples) || samples < minimum || availability == null
                    || availability != 1.0) {
                return insufficient(window, samples, null,
                        "scrape samples are insufficient or target was unavailable");
            }
            Double total = queries.query(
                    "sum(increase(http_server_requests_seconds_count{" + SELECTOR
                            + "}[" + range + "]))",
                    window.end()
            );
            if (!valid(total) || total < MINIMUM_REQUESTS) {
                return insufficient(window, samples, total,
                        "at least 20 observed requests are required for HTTP comparison");
            }
            Double errors = queries.query(
                    "sum(increase(http_server_requests_seconds_count{" + SELECTOR
                            + ",status=~\"5..\"}[" + range + "]))",
                    window.end()
            );
            Double p95 = queries.query(
                    "histogram_quantile(0.95, sum by (le) "
                            + "(increase(http_server_requests_seconds_bucket{"
                            + SELECTOR + "}[" + range + "])))",
                    window.end()
            );
            Double histogramCount = queries.query(
                    "sum(increase(http_server_requests_seconds_bucket{" + SELECTOR
                            + ",le=\"+Inf\"}[" + range + "]))", window.end());
            if (!valid(p95) || !valid(histogramCount)
                    || Math.abs(histogramCount - total) > Math.max(0.001, total * 0.01)) {
                return insufficient(window, samples, total,
                        "latency histogram is missing or invalid");
            }
            if (!valid(errors) || errors > total) {
                return insufficient(window, samples, total,
                        "5xx counter is missing or inconsistent; absence is not zero");
            }
            return new ReportWindow(window.phase(), window.start(), window.end(),
                    ReportMetricsStatus.OBSERVED, samples, total, errors,
                    errors / total, p95, null, COLLECTION_VERSION);
        } catch (RuntimeException exception) {
            return insufficient(window, null, null, "Prometheus query is unavailable");
        }
    }

    private static boolean valid(Double value) {
        return value != null && Double.isFinite(value) && value >= 0;
    }

    private ReportWindow insufficient(
            ReportWindow window, Double samples, Double total, String reason
    ) {
        return new ReportWindow(window.phase(), window.start(), window.end(),
                ReportMetricsStatus.INSUFFICIENT_DATA,
                valid(samples) ? samples : null, valid(total) ? total : null,
                null, null, null, reason, COLLECTION_VERSION);
    }
}
