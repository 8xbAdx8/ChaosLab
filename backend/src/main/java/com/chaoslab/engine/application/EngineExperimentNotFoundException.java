package com.chaoslab.engine.application;

import com.chaoslab.engine.application.model.EngineExperimentId;

public class EngineExperimentNotFoundException extends RuntimeException {

    public EngineExperimentNotFoundException(EngineExperimentId id) {
        super("engine experiment not found: " + id.value());
    }
}
