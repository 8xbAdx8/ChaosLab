package com.chaoslab.engine.infrastructure.blade;

import com.chaoslab.safety.application.model.VerifiedDockerTarget;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;

import static com.chaoslab.engine.infrastructure.blade.BladeRecoveryContract.Decision.*;
import static com.chaoslab.engine.infrastructure.blade.BladeRecoveryContract.ObservedStatus.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BladeRecoveryContractTests {
    private final VerifiedDockerTarget target = new VerifiedDockerTarget(
            UUID.randomUUID(), "a".repeat(64), "sha256:" + "b".repeat(64), "order-service");
    private final BladeRecoveryHandle handle = new BladeRecoveryHandle(
            UUID.randomUUID(), "local-runner-1", target, "0123456789abcdef");

    @Test
    void recoveryCommandsUseOnlyTheSavedUid() {
        assertThat(handle.destroyArguments()).containsExactly(
                "/opt/chaosblade/blade", "destroy", handle.uid());
        assertThat(handle.statusArguments()).containsExactly(
                "/opt/chaosblade/blade", "status", handle.uid());
    }

    @ParameterizedTest
    @ValueSource(strings = {"--all", "0123456789abcdef;id", "fake-123", "", "ABCDEF0123456789", "1234"})
    void rejectsUnsafeOrNonBladeIdentifiers(String uid) {
        assertThatThrownBy(() -> new BladeRecoveryHandle(handle.executionId(), "local-runner-1", target, uid))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @EnumSource(value = BladeRecoveryContract.ObservedStatus.class,
            names = {"CREATED", "SUCCESS", "ERROR"})
    void nonDestroyedRecordsStillRequireRecovery(BladeRecoveryContract.ObservedStatus status) {
        assertThat(decide(handle.uid(), status)).isEqualTo(DESTROY_REQUIRED);
    }

    @Test
    void onlyMatchingDestroyedEvidenceConfirmsRecovery() {
        assertThat(decide(handle.uid(), DESTROYED)).isEqualTo(CONFIRMED_RECOVERED);
        assertThat(decide("fedcba9876543210", DESTROYED)).isEqualTo(MANUAL_INTERVENTION);
        assertThat(decide(null, DATA_NOT_FOUND)).isEqualTo(MANUAL_INTERVENTION);
        assertThat(decide(null, UNAVAILABLE)).isEqualTo(RETRY_STATUS);
        assertThat(decide(null, null)).isEqualTo(RETRY_STATUS);
    }

    @Test
    void lostHandleOrChangedTargetOrExecutorCannotBeDeclaredRecovered() {
        assertThat(BladeRecoveryContract.decide(null, "local-runner-1", target, handle.uid(), DESTROYED))
                .isEqualTo(MANUAL_INTERVENTION);
        assertThat(BladeRecoveryContract.decide(handle, "other-runner", target, handle.uid(), DESTROYED))
                .isEqualTo(MANUAL_INTERVENTION);
        var replacement = new VerifiedDockerTarget(target.targetId(), "c".repeat(64), target.imageId(), "order-service");
        assertThat(BladeRecoveryContract.decide(handle, "local-runner-1", replacement, handle.uid(), DESTROYED))
                .isEqualTo(MANUAL_INTERVENTION);
    }

    private BladeRecoveryContract.Decision decide(String uid, BladeRecoveryContract.ObservedStatus status) {
        return BladeRecoveryContract.decide(handle, "local-runner-1", target, uid, status);
    }
}
