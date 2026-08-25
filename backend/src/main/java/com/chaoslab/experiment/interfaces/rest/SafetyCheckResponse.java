package com.chaoslab.experiment.interfaces.rest;

public record SafetyCheckResponse(
        String code,
        boolean passed,
        String message
) {
}
