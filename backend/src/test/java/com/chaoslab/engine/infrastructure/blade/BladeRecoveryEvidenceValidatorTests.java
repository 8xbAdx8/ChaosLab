package com.chaoslab.engine.infrastructure.blade;

import com.chaoslab.safety.application.model.VerifiedDockerTarget;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import static com.chaoslab.engine.infrastructure.blade.BladeRecoveryEvidenceGate.*;
import static org.assertj.core.api.Assertions.assertThat;

class BladeRecoveryEvidenceValidatorTests {
    private final Instant now = Instant.parse("2026-10-03T00:00:30Z");
    private final BladeRecoveryHandle handle = new BladeRecoveryHandle(UUID.randomUUID(), "runner-1",
            new VerifiedDockerTarget(UUID.randomUUID(), "a".repeat(64), "sha256:"+"b".repeat(64), "order-service"), "0123456789abcdef");

    private Outcome assess(BladeRecoveryHandle subject, Instant at) {
        return BladeRecoveryEvidenceValidator.assess(handle, now.minusSeconds(20), now, Duration.ofSeconds(10),
                new BladeRecoveryEvidenceValidator.Observation<>(handle, now, BladeRecoveryContract.Decision.CONFIRMED_RECOVERED),
                new BladeRecoveryEvidenceValidator.Observation<>(subject, at, ResidualObservation.CLEAR),
                new BladeRecoveryEvidenceValidator.Observation<>(handle, now, HealthObservation.HEALTHY));
    }

    @Test void acceptsFreshEvidenceIncludingAgeBoundary() {
        assertThat(assess(handle, now)).isEqualTo(Outcome.VERIFIED);
        assertThat(assess(handle, now.minusSeconds(10))).isEqualTo(Outcome.VERIFIED);
    }
    @Test void rejectsStaleFutureAndPreRecoveryEvidence() {
        assertThat(assess(handle, now.minusSeconds(10).minusNanos(1))).isEqualTo(Outcome.INCOMPLETE);
        assertThat(assess(handle, now.plusNanos(1))).isEqualTo(Outcome.INCOMPLETE);
        assertThat(assess(handle, now.minusSeconds(21))).isEqualTo(Outcome.INCOMPLETE);
        assertThat(assess(handle, null)).isEqualTo(Outcome.INCOMPLETE);
    }
    @Test void rejectsDifferentExecutionEvenWithSameTargetAndUid() {
        var other = new BladeRecoveryHandle(UUID.randomUUID(), handle.executorInstanceId(), handle.target(), handle.uid());
        assertThat(assess(other, now)).isEqualTo(Outcome.MANUAL_INTERVENTION);
        assertThat(assess(null, now)).isEqualTo(Outcome.INCOMPLETE);
    }
    @Test void rejectsInvalidPolicyAndAbsentObservations() {
        assertThat(BladeRecoveryEvidenceValidator.assess(handle, now, now, Duration.ZERO, null, null, null))
                .isEqualTo(Outcome.INCOMPLETE);
        assertThat(BladeRecoveryEvidenceValidator.assess(handle, now, now, Duration.ofSeconds(10), null, null, null))
                .isEqualTo(Outcome.INCOMPLETE);
    }
}
