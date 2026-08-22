package com.chaoslab.engine.application.model;

import java.util.Objects;

public record EngineDestroyResult(
        EngineExperimentId engineExperimentId,
        EngineStatus status
) {

    public EngineDestroyResult {
        Objects.requireNonNull(
                engineExperimentId,
                "engineExperimentId must not be null"
        );
        Objects.requireNonNull(status, "status must not be null");
    }
}
