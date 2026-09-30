package com.chaoslab.engine.application;

/** Creation may have taken effect, but no trustworthy recovery handle is available. */
public final class EngineCreateUncertainException extends RuntimeException {
    public EngineCreateUncertainException() {
        super("engine create outcome uncertain; manual intervention required");
    }
}
