package com.chaoslab.engine.infrastructure.fake;

import com.chaoslab.engine.application.EngineExperimentNotFoundException;
import com.chaoslab.engine.application.model.EngineCreateResult;
import com.chaoslab.engine.application.model.EngineDestroyResult;
import com.chaoslab.engine.application.model.EngineExperimentId;
import com.chaoslab.engine.application.model.EngineStatus;
import com.chaoslab.engine.application.model.EngineStatusResult;
import com.chaoslab.engine.application.model.ReadyExperimentRequest;
import com.chaoslab.engine.application.port.ChaosEngine;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "chaoslab.engine", havingValue = "fake", matchIfMissing = true)
public class FakeChaosEngine implements ChaosEngine {

    private static final String ID_PREFIX = "fake-";

    private final ConcurrentMap<EngineExperimentId, EngineStatus> experiments =
            new ConcurrentHashMap<>();

    @Override
    public boolean simulated() {
        return true;
    }

    @Override
    public EngineCreateResult create(ReadyExperimentRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        EngineExperimentId id = new EngineExperimentId(
                ID_PREFIX + request.executionId()
        );
        EngineStatus status = experiments.computeIfAbsent(
                id,
                ignored -> EngineStatus.RUNNING
        );
        return new EngineCreateResult(id, status);
    }

    @Override
    public EngineStatusResult status(EngineExperimentId engineExperimentId) {
        Objects.requireNonNull(
                engineExperimentId,
                "engineExperimentId must not be null"
        );
        return new EngineStatusResult(
                engineExperimentId,
                findStatus(engineExperimentId)
        );
    }

    @Override
    public EngineDestroyResult destroy(EngineExperimentId engineExperimentId) {
        Objects.requireNonNull(
                engineExperimentId,
                "engineExperimentId must not be null"
        );
        EngineStatus status = experiments.computeIfPresent(
                engineExperimentId,
                (ignored, current) -> EngineStatus.DESTROYED
        );
        if (status == null) {
            throw new EngineExperimentNotFoundException(engineExperimentId);
        }
        return new EngineDestroyResult(engineExperimentId, status);
    }

    private EngineStatus findStatus(EngineExperimentId id) {
        EngineStatus status = experiments.get(id);
        if (status == null) {
            throw new EngineExperimentNotFoundException(id);
        }
        return status;
    }
}
