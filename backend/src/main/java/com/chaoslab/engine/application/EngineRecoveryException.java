package com.chaoslab.engine.application;

import java.util.Objects;

/** Allowlisted recovery diagnosis only; no native output, paths, UID or root details. */
public final class EngineRecoveryException extends IllegalStateException {
    public enum Reason {
        ENGINE_RECOVERY_NOT_CONFIRMED,
        RECOVERY_RESIDUAL_PRESENT,
        RECOVERY_EVIDENCE_INCOMPLETE,
        RECOVERY_IDENTITY_REJECTED
    }

    private final Reason reason;

    public EngineRecoveryException(Reason reason) {
        super(Objects.requireNonNull(reason).name());
        this.reason = reason;
    }

    public Reason reason() { return reason; }
}
