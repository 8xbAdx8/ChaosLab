package com.chaoslab.experiment.application;

public class ExperimentValidationRejectedException extends RuntimeException {

    private final String code;

    public ExperimentValidationRejectedException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
