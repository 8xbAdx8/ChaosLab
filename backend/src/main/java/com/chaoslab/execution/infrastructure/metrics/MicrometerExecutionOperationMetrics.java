package com.chaoslab.execution.infrastructure.metrics;

import com.chaoslab.audit.domain.AuditOperation;
import com.chaoslab.execution.application.port.ExecutionOperationMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Objects;

@Component
public class MicrometerExecutionOperationMetrics implements ExecutionOperationMetrics {

    private static final Logger LOGGER = LoggerFactory.getLogger(
            MicrometerExecutionOperationMetrics.class
    );

    private final MeterRegistry registry;

    public MicrometerExecutionOperationMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
    }

    @Override
    public void record(AuditOperation operation, Result result) {
        Objects.requireNonNull(operation, "operation must not be null");
        Objects.requireNonNull(result, "result must not be null");
        try {
            registry.counter(
                    "chaoslab.execution.operations",
                    "operation", operation.name().toLowerCase(Locale.ROOT),
                    "result", result.name().toLowerCase(Locale.ROOT)
            ).increment();
        } catch (RuntimeException exception) {
            LOGGER.atWarn()
                    .addKeyValue("operation", operation)
                    .addKeyValue("result", result)
                    .addKeyValue("failureType", exception.getClass().getSimpleName())
                    .log("failed to record execution operation metric");
        }
    }
}
