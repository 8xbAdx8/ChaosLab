package com.chaoslab.execution.application;

import com.chaoslab.safety.application.model.SafetyCheck;

import java.util.List;
import java.util.Objects;

public class ExperimentExecutionStartRejectedException extends RuntimeException {

    private final String code;
    private final List<SafetyCheck> failedChecks;

    public ExperimentExecutionStartRejectedException(
            String code,
            String message,
            List<SafetyCheck> failedChecks
    ) {
        super(message);
        this.code = Objects.requireNonNull(code, "code must not be null");
        Objects.requireNonNull(failedChecks, "failedChecks must not be null");
        this.failedChecks = List.copyOf(failedChecks);
    }

    public String getCode() {
        return code;
    }

    public List<SafetyCheck> getFailedChecks() {
        return failedChecks;
    }
}
