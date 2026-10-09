package com.chaoslab.engine.application.model;

public enum EngineStatus {
    RUNNING,
    DESTROYED,
    /** Native Destroyed only; full physical recovery evidence may be absent. Not causal attribution. */
    ENGINE_RECOVERED
}
