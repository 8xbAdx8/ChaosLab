package com.chaoslab.safety.application.model;

import java.util.Objects;

public record SafetyCheck(
        String code,
        boolean passed,
        String message
) {

    public SafetyCheck {
        code = requireText(code, "code");
        message = requireText(message, "message");
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }
}
