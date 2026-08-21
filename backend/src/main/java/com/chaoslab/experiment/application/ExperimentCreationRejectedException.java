package com.chaoslab.experiment.application;

public class ExperimentCreationRejectedException extends RuntimeException {

    private final String code;

    public ExperimentCreationRejectedException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
