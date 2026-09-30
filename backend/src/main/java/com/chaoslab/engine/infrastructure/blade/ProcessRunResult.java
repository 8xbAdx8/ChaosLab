package com.chaoslab.engine.infrastructure.blade;

/**
 * EXITED means the process completed, not that Blade succeeded or recovered.
 * cleanupComplete covers only the root and observed descendants, not fault recovery
 * or descendants that escaped observation. Failure output can be partial.
 */
public record ProcessRunResult(
        Outcome outcome, Integer exitCode, String stdout, String stderr, boolean cleanupComplete
) {
    public enum Outcome {
        EXITED, TIMED_OUT, CANCELLED, OUTPUT_LIMIT, START_FAILED, IO_FAILED, DESCENDANTS_REMAINED
    }
}
