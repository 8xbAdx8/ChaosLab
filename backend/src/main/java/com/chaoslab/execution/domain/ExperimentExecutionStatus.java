package com.chaoslab.execution.domain;

public enum ExperimentExecutionStatus {
    PREPARING,
    RUNNING,
    DESTROYING,
    SUCCESS,
    FAILED,
    ROLLBACK_FAILED
}