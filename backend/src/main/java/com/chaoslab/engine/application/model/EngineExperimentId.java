package com.chaoslab.engine.application.model;

import java.util.Objects;

public record EngineExperimentId(String value) {

    public EngineExperimentId {
        Objects.requireNonNull(value, "value must not be null");
        value = value.trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("value must not be blank");
        }
    }
}
