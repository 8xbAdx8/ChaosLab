package com.chaoslab.experiment.application.port;

import com.chaoslab.experiment.application.validation.ParameterViolation;

import java.util.List;

public interface ExperimentParameterValidator {

    List<ParameterViolation> validate(String parameterSchema, String parameters);
}
