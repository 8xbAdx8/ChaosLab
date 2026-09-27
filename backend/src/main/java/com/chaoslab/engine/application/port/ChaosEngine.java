package com.chaoslab.engine.application.port;

import com.chaoslab.engine.application.model.EngineCreateResult;
import com.chaoslab.engine.application.model.EngineDestroyResult;
import com.chaoslab.engine.application.model.EngineExperimentId;
import com.chaoslab.engine.application.model.EngineStatusResult;
import com.chaoslab.engine.application.model.ReadyExperimentRequest;

public interface ChaosEngine {

    default boolean simulated() {
        return false;
    }

    EngineCreateResult create(ReadyExperimentRequest request);

    EngineStatusResult status(EngineExperimentId engineExperimentId);

    EngineDestroyResult destroy(EngineExperimentId engineExperimentId);
}
