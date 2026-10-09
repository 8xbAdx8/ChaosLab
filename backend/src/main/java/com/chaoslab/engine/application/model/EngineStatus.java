package com.chaoslab.engine.application.model;

public enum EngineStatus {
    RUNNING,
    DESTROYED,
    /** Native Destroyed only; full recovery evidence/active provenance may be absent. */
    ENGINE_RECOVERED
}
