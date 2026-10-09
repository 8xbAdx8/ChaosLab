package com.chaoslab.engine.application.model;

import java.util.Objects;

public record EngineDestroyResult(
        EngineExperimentId engineExperimentId,
        EngineStatus status,
        RecoveryCause recoveryCause
) {
    public enum RecoveryCause { UNKNOWN, NOT_APPLICABLE }

    /** Existing Fake/legacy callers do not attest a real recovery actor. */
    public EngineDestroyResult(EngineExperimentId id, EngineStatus status) {
        this(id, status, RecoveryCause.NOT_APPLICABLE);
    }

    public EngineDestroyResult {
        Objects.requireNonNull(recoveryCause);
        Objects.requireNonNull(
                engineExperimentId,
                "engineExperimentId must not be null"
        );
        Objects.requireNonNull(status, "status must not be null");
    }
}
