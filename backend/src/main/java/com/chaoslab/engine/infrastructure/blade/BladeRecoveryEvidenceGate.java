package com.chaoslab.engine.infrastructure.blade;

/** Pure evidence policy, not a probe or authorization to release execution occupancy.
 * Callers must supply fresh, same-execution evidence from trusted observers.
 * A successful process-group kill is not a CLEAR residual observation.
 */
public final class BladeRecoveryEvidenceGate {
    private BladeRecoveryEvidenceGate() { }

    public enum ResidualObservation { CLEAR, PRESENT, UNKNOWN }
    public enum HealthObservation { HEALTHY, UNHEALTHY, UNKNOWN }
    public enum Outcome { VERIFIED, INCOMPLETE, MANUAL_INTERVENTION }

    public static Outcome assess(BladeRecoveryContract.Decision engine,
                                 ResidualObservation residual, HealthObservation health) {
        if (engine == BladeRecoveryContract.Decision.MANUAL_INTERVENTION
                || residual == ResidualObservation.PRESENT) {
            return Outcome.MANUAL_INTERVENTION;
        }
        if (engine == BladeRecoveryContract.Decision.CONFIRMED_RECOVERED
                && residual == ResidualObservation.CLEAR && health == HealthObservation.HEALTHY) {
            return Outcome.VERIFIED;
        }
        return Outcome.INCOMPLETE;
    }
}
