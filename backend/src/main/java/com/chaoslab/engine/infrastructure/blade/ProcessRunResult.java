package com.chaoslab.engine.infrastructure.blade;

/**
 * EXITED means the process completed, not that Blade succeeded or recovered.
 * HANDOFF means exit 0 and complete bounded output; descendants were deliberately
 * not cleaned. It is not business success, ownership verification or recovery.
 * cleanupComplete covers only the root and observed descendants, not fault recovery
 * or descendants that escaped observation. Failure output can be partial.
 */
public record ProcessRunResult(
        Outcome outcome, Integer exitCode, String stdout, String stderr, boolean cleanupComplete
) {
    public enum Outcome {
        EXITED, HANDOFF, TIMED_OUT, CANCELLED, OUTPUT_LIMIT, START_FAILED, IO_FAILED, DESCENDANTS_REMAINED
    }
}
