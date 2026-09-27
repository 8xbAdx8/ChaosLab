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
            "job=\"order-service\",uri=\"/orders/{orderId}\"";

    private final PrometheusQueryClient queries;

    public PrometheusReportObservationCollector(PrometheusQueryClient queries) {
        this.queries = Objects.requireNonNull(queries);
    }

    @Override
    public List<ReportWindow> collect(List<ReportWindow> windows) {
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
            Double samples = queries.query(
                    "sum(count_over_time(up{job=\"order-service\"}[" + range + "]))",
                    window.end()
            );
            Double availability = queries.query(
                    "min(min_over_time(up{job=\"order-service\"}[" + range + "]))",
                    window.end()
            );
            int minimum = Math.max(3, (int) Math.floor(seconds / SCRAPE_SECONDS * 0.8));
            if (samples == null || samples < minimum || availability == null
                    || availability != 1.0) {
                return insufficient(window, samples, null,
                        "scrape samples are insufficient or target was unavailable");
            }
            Double total = queries.query(
                    "sum(increase(http_server_requests_seconds_count{" + SELECTOR
                            + "}[" + range + "]))",
                    window.end()
            );
            if (total == null || total <= 0) {
                return insufficient(window, samples, total,
                        "no order requests were observed in this window");
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
            if (p95 == null || p95 < 0) {
                return insufficient(window, samples, total,
                        "latency histogram is missing or invalid");
            }
            double fiveXx = errors == null ? 0.0 : errors;
            return new ReportWindow(window.phase(), window.start(), window.end(),
                    ReportMetricsStatus.OBSERVED, samples, total, fiveXx,
                    Math.min(1.0, Math.max(0.0, fiveXx / total)), p95, null);
        } catch (RuntimeException exception) {
            return insufficient(window, null, null, "Prometheus query is unavailable");
        }
    }

    private ReportWindow insufficient(
            ReportWindow window, Double samples, Double total, String reason
    ) {
        return new ReportWindow(window.phase(), window.start(), window.end(),
                ReportMetricsStatus.INSUFFICIENT_DATA,
                samples, total, null, null, null, reason);
    }
}
