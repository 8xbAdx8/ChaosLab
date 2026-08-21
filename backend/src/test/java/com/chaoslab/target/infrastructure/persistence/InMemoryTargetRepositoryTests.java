package com.chaoslab.target.infrastructure.persistence;

import com.chaoslab.target.domain.Target;
import com.chaoslab.target.domain.TargetEnvironment;
import com.chaoslab.target.domain.TargetType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryTargetRepositoryTests {

    private final InMemoryTargetRepository targetRepository = new InMemoryTargetRepository();

    @Test
    void shouldSaveAndFindTargetById() {
        Target target = target(
                "7d550907-d8e3-4cb0-89cc-8365a43b7249",
                "demo-target"
        );

        targetRepository.save(target);

        assertThat(targetRepository.findById(target.getId()))
                .containsSame(target);
    }

    @Test
    void shouldReturnTargetsInStableNameOrder() {
        Target second = target(
                "f17b3a5b-1e6a-4e78-bbd6-4132f3c8bfaa",
                "second-target"
        );
        Target first = target(
                "13b64c80-1eb0-42da-8176-79bd35f576d5",
                "first-target"
        );
        targetRepository.save(second);
        targetRepository.save(first);

        List<Target> result = targetRepository.findAll();

        assertThat(result)
                .extracting(Target::getName)
                .containsExactly("first-target", "second-target");
    }

    private static Target target(String id, String name) {
        return Target.register(
                UUID.fromString(id),
                name,
                TargetType.DOCKER_CONTAINER,
                TargetEnvironment.TEST
        );
    }
}
