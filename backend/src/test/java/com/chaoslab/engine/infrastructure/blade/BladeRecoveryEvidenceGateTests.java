package com.chaoslab.engine.infrastructure.blade;

import org.junit.jupiter.api.Test;

import static com.chaoslab.engine.infrastructure.blade.BladeRecoveryEvidenceGate.*;
import static org.assertj.core.api.Assertions.assertThat;

class BladeRecoveryEvidenceGateTests {
    @Test
    void onlyCompleteEvidenceVerifiesAcrossAll36Combinations() {
        int verified = 0;
        for (var engine : BladeRecoveryContract.Decision.values()) {
            for (var residual : ResidualObservation.values()) {
                for (var health : HealthObservation.values()) {
                    var result = assess(engine, residual, health);
                    if (result == Outcome.VERIFIED) {
                        verified++;
                        assertThat(engine).isEqualTo(BladeRecoveryContract.Decision.CONFIRMED_RECOVERED);
                        assertThat(residual).isEqualTo(ResidualObservation.CLEAR);
                        assertThat(health).isEqualTo(HealthObservation.HEALTHY);
                    }
                    if (residual == ResidualObservation.PRESENT
                            || engine == BladeRecoveryContract.Decision.MANUAL_INTERVENTION) {
                        assertThat(result).isEqualTo(Outcome.MANUAL_INTERVENTION);
                    }
                }
            }
        }
        assertThat(verified).isEqualTo(1);
    }

    @Test
    void missingEvidenceAndGroupCleanupCannotSubstituteForResidualInspection() {
        var destroyed = BladeRecoveryContract.Decision.CONFIRMED_RECOVERED;
        assertThat(assess(destroyed, ResidualObservation.UNKNOWN, HealthObservation.HEALTHY))
                .isEqualTo(Outcome.INCOMPLETE);
        assertThat(assess(destroyed, null, HealthObservation.HEALTHY)).isEqualTo(Outcome.INCOMPLETE);
        assertThat(assess(destroyed, ResidualObservation.CLEAR, null)).isEqualTo(Outcome.INCOMPLETE);
        assertThat(assess(null, ResidualObservation.CLEAR, HealthObservation.HEALTHY))
                .isEqualTo(Outcome.INCOMPLETE);
        assertThat(assess(null, null, null)).isEqualTo(Outcome.INCOMPLETE);
    }
}
