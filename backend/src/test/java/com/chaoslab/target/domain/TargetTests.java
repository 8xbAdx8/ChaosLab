package com.chaoslab.target.domain;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TargetTests {

    private static final UUID TARGET_ID = UUID.fromString("c1e754d2-c9c8-4b5a-ad28-a40653767863");

    @Test
    void shouldRegisterEnabledTargetWithNormalizedName() {
        Target target = Target.register(
                TARGET_ID,
                "  demo-order-container  ",
                TargetType.DOCKER_CONTAINER,
                TargetEnvironment.CHAOS_LAB
        );

        assertThat(target.getId()).isEqualTo(TARGET_ID);
        assertThat(target.getName()).isEqualTo("demo-order-container");
        assertThat(target.getType()).isEqualTo(TargetType.DOCKER_CONTAINER);
        assertThat(target.getEnvironment()).isEqualTo(TargetEnvironment.CHAOS_LAB);
        assertThat(target.isEnabled()).isTrue();
    }

    @Test
    void shouldRejectBlankName() {
        assertThatThrownBy(() -> Target.register(
                TARGET_ID,
                "   ",
                TargetType.DOCKER_CONTAINER,
                TargetEnvironment.TEST
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("name must not be blank");
    }

    @Test
    void shouldRejectNameLongerThanMaximum() {
        String tooLongName = "a".repeat(Target.MAX_NAME_LENGTH + 1);

        assertThatThrownBy(() -> Target.register(
                TARGET_ID,
                tooLongName,
                TargetType.DOCKER_CONTAINER,
                TargetEnvironment.TEST
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("name must not exceed 100 characters");
    }

    @Test
    void shouldRequireIdentityTypeAndEnvironment() {
        assertThatNullPointerException()
                .isThrownBy(() -> Target.register(
                        null,
                        "demo-target",
                        TargetType.DOCKER_CONTAINER,
                        TargetEnvironment.TEST
                ))
                .withMessage("id must not be null");

        assertThatNullPointerException()
                .isThrownBy(() -> Target.register(
                        TARGET_ID,
                        "demo-target",
                        null,
                        TargetEnvironment.TEST
                ))
                .withMessage("type must not be null");

        assertThatNullPointerException()
                .isThrownBy(() -> Target.register(
                        TARGET_ID,
                        "demo-target",
                        TargetType.DOCKER_CONTAINER,
                        null
                ))
                .withMessage("environment must not be null");
    }

    @Test
    void shouldDisableAndEnableTarget() {
        Target target = Target.register(
                TARGET_ID,
                "demo-target",
                TargetType.DOCKER_CONTAINER,
                TargetEnvironment.TEST
        );

        target.disable();
        assertThat(target.isEnabled()).isFalse();

        target.enable();
        assertThat(target.isEnabled()).isTrue();
    }

    @Test
    void shouldRehydrateDisabledTarget() {
        UUID id = UUID.randomUUID();

        Target target = Target.rehydrate(
                id,
                "payment-service",
                TargetType.JAVA_APPLICATION,
                TargetEnvironment.CHAOS_LAB,
                false
        );

        assertThat(target.getId()).isEqualTo(id);
        assertThat(target.isEnabled()).isFalse();
    }
}
