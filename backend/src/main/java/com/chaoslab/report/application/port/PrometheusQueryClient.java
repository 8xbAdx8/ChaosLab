package com.chaoslab.report.application.port;

import java.time.Instant;

public interface PrometheusQueryClient {
    Double query(String expression, Instant at);
}
