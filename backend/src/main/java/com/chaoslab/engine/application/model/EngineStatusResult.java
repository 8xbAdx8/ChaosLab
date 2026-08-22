package com.chaoslab.engine.application.model;

import java.util.Objects;

public record EngineStatusResult(
        EngineExperimentId engineExperimentId,
        EngineStatus status
) {

    public EngineStatusResult {
        Objects.requireNonNull(
                engineExperimentId,
                "engineExperimentId must not be null"
        );
        Objects.requireNonNull(status, "status must not be null");
    }
}
