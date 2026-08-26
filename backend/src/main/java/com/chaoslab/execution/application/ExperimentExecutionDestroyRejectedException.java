package com.chaoslab.execution.application;

import java.util.Objects;

public class ExperimentExecutionDestroyRejectedException extends RuntimeException {

    private final String code;

    public ExperimentExecutionDestroyRejectedException(
            String code,
            String message
    ) {
        super(message);
        this.code = Objects.requireNonNull(code, "code must not be null");
    }

    public String getCode() {
        return code;
    }
}