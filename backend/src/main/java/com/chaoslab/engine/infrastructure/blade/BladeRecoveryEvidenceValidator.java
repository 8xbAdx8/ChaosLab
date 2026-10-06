package com.chaoslab.engine.infrastructure.blade;

import java.time.Duration;
import java.time.Instant;

/** Pure correlation/freshness check. Does not authenticate observers or collect evidence. */
public final class BladeRecoveryEvidenceValidator {
    private BladeRecoveryEvidenceValidator() { }

    public record Observation<T>(BladeRecoveryHandle subject, Instant observedAt, T value) { }

    public static BladeRecoveryEvidenceGate.Outcome assess(
            BladeRecoveryHandle expected, Instant recoveryStartedAt, Instant now, Duration maximumAge,
            Observation<BladeRecoveryContract.Decision> engine,
            Observation<BladeRecoveryEvidenceGate.ResidualObservation> residual,
            Observation<BladeRecoveryEvidenceGate.HealthObservation> health) {
        if (expected == null || recoveryStartedAt == null || now == null || maximumAge == null
                || maximumAge.isNegative() || maximumAge.isZero() || recoveryStartedAt.isAfter(now)) {
            return BladeRecoveryEvidenceGate.Outcome.INCOMPLETE;
        }
        for (Observation<?> observation : new Observation<?>[]{engine, residual, health}) {
            if (observation != null && observation.subject() != null
                    && !expected.equals(observation.subject())) {
                return BladeRecoveryEvidenceGate.Outcome.MANUAL_INTERVENTION;
            }
        }
        for (Observation<?> observation : new Observation<?>[]{engine, residual, health}) {
            if (observation == null || observation.subject() == null || observation.value() == null
                    || observation.observedAt() == null || observation.observedAt().isBefore(recoveryStartedAt)
                    || observation.observedAt().isAfter(now)
                    || Duration.between(observation.observedAt(), now).compareTo(maximumAge) > 0) {
                return BladeRecoveryEvidenceGate.Outcome.INCOMPLETE;
            }
        }
        return BladeRecoveryEvidenceGate.assess(engine.value(), residual.value(), health.value());
    }
}
