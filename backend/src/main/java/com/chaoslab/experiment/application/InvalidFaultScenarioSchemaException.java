package com.chaoslab.experiment.application;

public class InvalidFaultScenarioSchemaException extends RuntimeException {

    public InvalidFaultScenarioSchemaException(Throwable cause) {
        super("fault scenario parameter schema cannot be evaluated", cause);
    }
}
