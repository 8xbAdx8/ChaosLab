package com.chaoslab.execution.domain;

public enum ExperimentExecutionStatus {
    PREPARING,
    CREATE_UNCERTAIN,
    RUNNING,
    DESTROYING,
    SUCCESS,
    FAILED,
    ROLLBACK_FAILED
}
