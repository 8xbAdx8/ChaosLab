package com.chaoslab.experiment.domain;

public enum ExperimentStatus {
    CREATED,
    VALIDATED,
    READY,
    RUNNING,
    DESTROYING,
    SUCCESS,
    ROLLBACK_FAILED
}