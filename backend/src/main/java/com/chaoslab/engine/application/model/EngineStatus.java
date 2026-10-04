package com.chaoslab.engine.application.model;

public enum EngineStatus {
    RUNNING,
    DESTROYED,
    /** Blade status confirms Destroyed; residual/health evidence is still absent. */
    ENGINE_RECOVERED
}
