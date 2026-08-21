package com.chaoslab.scenario.domain;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public final class FaultScenario {

    public static final int MAX_CODE_LENGTH = 64;
    public static final int MAX_NAME_LENGTH = 100;
    public static final int MAX_DESCRIPTION_LENGTH = 500;

    private static final Pattern CODE_PATTERN = Pattern.compile("[A-Z][A-Z0-9_]*");

    private final UUID id;
    private final String code;
    private final String name;
    private final String description;
    private final String parameterSchema;
    private final boolean enabled;

    private FaultScenario(
            UUID id,
            String code,
            String name,
            String description,
            String parameterSchema,
            boolean enabled
    ) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.code = validateCode(code);
        this.name = validateText(name, "name", MAX_NAME_LENGTH);
        this.description = validateText(
                description,
                "description",
                MAX_DESCRIPTION_LENGTH
        );
        this.parameterSchema = validateRequiredText(
                parameterSchema,
                "parameterSchema"
        );
        this.enabled = enabled;
    }

    public static FaultScenario rehydrate(
            UUID id,
            String code,
            String name,
            String description,
            String parameterSchema,
            boolean enabled
    ) {
        return new FaultScenario(
                id,
                code,
                name,
                description,
                parameterSchema,
                enabled
        );
    }

    public UUID getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getParameterSchema() {
        return parameterSchema;
    }

    public boolean isEnabled() {
        return enabled;
    }

    private static String validateCode(String code) {
        String normalizedCode = validateText(code, "code", MAX_CODE_LENGTH);
        if (!CODE_PATTERN.matcher(normalizedCode).matches()) {
            throw new IllegalArgumentException(
                    "code must use uppercase letters, digits, and underscores"
            );
        }
        return normalizedCode;
    }

    private static String validateText(String value, String field, int maxLength) {
        String normalizedValue = validateRequiredText(value, field);
        if (normalizedValue.length() > maxLength) {
            throw new IllegalArgumentException(
                    field + " must not exceed " + maxLength + " characters"
            );
        }
        return normalizedValue;
    }

    private static String validateRequiredText(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        String normalizedValue = value.trim();
        if (normalizedValue.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalizedValue;
    }
}
