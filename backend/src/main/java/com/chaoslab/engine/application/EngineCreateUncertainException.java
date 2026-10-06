package com.chaoslab.engine.application;

/** Creation may have taken effect. An optional reference points to a committed journal, not a response UID. */
public final class EngineCreateUncertainException extends RuntimeException {
    private final com.chaoslab.engine.application.model.EngineExperimentId recoveryReference;
    public EngineCreateUncertainException() {
        this(null);
    }
    public EngineCreateUncertainException(com.chaoslab.engine.application.model.EngineExperimentId recoveryReference) {
        super("engine create outcome uncertain; manual intervention required");
        this.recoveryReference = recoveryReference;
    }
    public java.util.Optional<com.chaoslab.engine.application.model.EngineExperimentId> recoveryReference() {
        return java.util.Optional.ofNullable(recoveryReference);
    }
}
