package com.chaoslab.execution.infrastructure.metrics;

import com.chaoslab.audit.domain.AuditOperation;
import com.chaoslab.execution.application.port.ExecutionOperationMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MicrometerExecutionOperationMetricsTests {

    @Test
    void recordsOnlyBoundedOperationAndResultTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try {
            MicrometerExecutionOperationMetrics metrics =
                    new MicrometerExecutionOperationMetrics(registry);

            metrics.record(AuditOperation.START_EXPERIMENT, ExecutionOperationMetrics.Result.SUCCESS);
            metrics.record(AuditOperation.START_EXPERIMENT, ExecutionOperationMetrics.Result.SUCCESS);
            metrics.record(AuditOperation.START_EXPERIMENT, ExecutionOperationMetrics.Result.REPLAYED);

            assertThat(registry.get("chaoslab.execution.operations")
                    .tag("operation", "start_experiment")
                    .tag("result", "success")
                    .counter().count()).isEqualTo(2);
            assertThat(registry.get("chaoslab.execution.operations")
                    .tag("operation", "start_experiment")
                    .tag("result", "replayed")
                    .counter().count()).isEqualTo(1);
            assertThat(registry.getMeters()).hasSize(2);
        } finally {
            registry.close();
        }
    }

    @Test
    void exportsOperationCounterInPrometheusFormat() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        try {
            new MicrometerExecutionOperationMetrics(registry).record(
                    AuditOperation.AUTOMATIC_RECOVERY,
                    ExecutionOperationMetrics.Result.FAILED
            );

            assertThat(registry.scrape())
                    .contains("chaoslab_execution_operations_total")
                    .contains("operation=\"automatic_recovery\"")
                    .contains("result=\"failed\"");
        } finally {
            registry.close();
        }
    }
}
