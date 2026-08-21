package com.chaoslab.target.domain;

import java.util.Objects;
import java.util.UUID;

public final class Target {

    public static final int MAX_NAME_LENGTH = 100;

    private final UUID id;
    private final String name;
    private final TargetType type;
    private final TargetEnvironment environment;
    private boolean enabled;

    private Target(
            UUID id,
            String name,
            TargetType type,
            TargetEnvironment environment
    ) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.name = normalizeName(name);
        this.type = Objects.requireNonNull(type, "type must not be null");
        this.environment = Objects.requireNonNull(environment, "environment must not be null");
        this.enabled = true;
    }

    public static Target register(
            UUID id,
            String name,
            TargetType type,
            TargetEnvironment environment
    ) {
        return new Target(id, name, type, environment);
    }

    public static Target rehydrate(
            UUID id,
            String name,
            TargetType type,
            TargetEnvironment environment,
            boolean enabled
    ) {
        Target target = new Target(id, name, type, environment);
        target.enabled = enabled;
        return target;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public TargetType getType() {
        return type;
    }

    public TargetEnvironment getEnvironment() {
        return environment;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void enable() {
        enabled = true;
    }

    public void disable() {
        enabled = false;
    }

    private static String normalizeName(String name) {
        Objects.requireNonNull(name, "name must not be null");
        String normalizedName = name.trim();
        if (normalizedName.isEmpty()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (normalizedName.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException(
                    "name must not exceed " + MAX_NAME_LENGTH + " characters"
            );
        }
        return normalizedName;
    }
}
