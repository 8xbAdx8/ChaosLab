package com.chaoslab.experiment.application;

import com.chaoslab.experiment.application.validation.ParameterViolation;

import java.util.List;

public class ExperimentParametersInvalidException extends RuntimeException {

    private final List<ParameterViolation> violations;

    public ExperimentParametersInvalidException(List<ParameterViolation> violations) {
        super("experiment parameters do not match the fault scenario schema");
        this.violations = List.copyOf(violations);
    }

    public List<ParameterViolation> getViolations() {
        return violations;
    }
}
