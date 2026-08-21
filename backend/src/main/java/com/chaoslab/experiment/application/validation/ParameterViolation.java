package com.chaoslab.experiment.application.validation;

public record ParameterViolation(
        String path,
        String keyword,
        String message
) {
}
