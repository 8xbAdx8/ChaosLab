package com.chaoslab.engine.infrastructure.blade;

import com.chaoslab.safety.application.model.VerifiedDockerTarget;

/** Decision contract only. No retries, persistence, or external commands run here. */
public final class BladeRecoveryContract {
    private BladeRecoveryContract() {
    }

    public enum ObservedStatus {
        CREATED, SUCCESS, ERROR, DESTROYED, DATA_NOT_FOUND, UNAVAILABLE
    }

    public enum Decision {
        CONFIRMED_RECOVERED, DESTROY_REQUIRED, RETRY_STATUS, MANUAL_INTERVENTION
    }

    public static Decision decide(
            BladeRecoveryHandle handle, String executorInstanceId,
            VerifiedDockerTarget currentTarget, String observedUid, ObservedStatus status
    ) {
        if (handle == null || !handle.executorInstanceId().equals(executorInstanceId)
                || !handle.target().equals(currentTarget)) {
            return Decision.MANUAL_INTERVENTION;
        }
        if (status == null || status == ObservedStatus.UNAVAILABLE) {
            return Decision.RETRY_STATUS;
        }
        if (status == ObservedStatus.DATA_NOT_FOUND || !handle.uid().equals(observedUid)) {
            return Decision.MANUAL_INTERVENTION;
        }
        return switch (status) {
            case DESTROYED -> Decision.CONFIRMED_RECOVERED;
            case CREATED, SUCCESS, ERROR -> Decision.DESTROY_REQUIRED;
            case DATA_NOT_FOUND, UNAVAILABLE -> throw new IllegalStateException("handled above");
        };
    }
}
