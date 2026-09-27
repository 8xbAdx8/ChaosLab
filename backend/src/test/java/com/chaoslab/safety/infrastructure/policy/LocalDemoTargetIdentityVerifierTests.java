package com.chaoslab.safety.infrastructure.policy;

import com.chaoslab.target.application.port.TargetRepository;
import com.chaoslab.target.domain.Target;
import com.chaoslab.target.domain.TargetEnvironment;
import com.chaoslab.target.domain.TargetType;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class LocalDemoTargetIdentityVerifierTests {

    private final TargetRepository targets = mock(TargetRepository.class);
    private final LocalDemoTargetIdentityVerifier verifier =
            new LocalDemoTargetIdentityVerifier(
                    targets, new ObjectMapper(), Clock.systemUTC(), ""
            );

    @Test
    void missingScriptFailsClosedBeforeRepositoryOrProcessAccess() {
        Target target = Target.register(UUID.randomUUID(), "order-service",
                TargetType.DOCKER_CONTAINER, TargetEnvironment.CHAOS_LAB);

        assertThat(verifier.verify(target).verified()).isFalse();
        assertThat(verifier.verify(target).reason())
                .isEqualTo("local Demo binding verifier is not configured");
        verifyNoInteractions(targets);
    }

    @Test
    void nonDemoDockerTargetIsRejectedBeforeRepositoryOrProcessAccess() {
        Target target = Target.register(UUID.randomUUID(), "not-order-service",
                TargetType.DOCKER_CONTAINER, TargetEnvironment.CHAOS_LAB);

        assertThat(verifier.verify(target).verified()).isFalse();
        verifyNoInteractions(targets);
    }
}
