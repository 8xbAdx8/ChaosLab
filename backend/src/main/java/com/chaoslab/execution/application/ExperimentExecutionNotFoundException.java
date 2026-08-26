package com.chaoslab.execution.application;

import java.util.UUID;

public class ExperimentExecutionNotFoundException extends RuntimeException {

    public ExperimentExecutionNotFoundException(UUID executionId) {
        super("experiment execution not found: " + executionId);
    }
}
