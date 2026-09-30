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

    /**
     * Implementations must report a possibly applied create without a trustworthy
     * recovery identity via EngineCreateUncertainException, never as a definite failure.
     * This signal does not provide pre-dispatch durability or crash recovery by itself.
     */
    EngineCreateResult create(ReadyExperimentRequest request);

    EngineStatusResult status(EngineExperimentId engineExperimentId);

    EngineDestroyResult destroy(EngineExperimentId engineExperimentId);
}
