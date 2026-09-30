package com.chaoslab.engine.infrastructure.blade;

import com.chaoslab.engine.application.model.ReadyExperimentRequest;
import com.chaoslab.safety.application.model.VerifiedDockerTarget;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DockerCpuCommandPlanTests {
    private final UUID executionId = UUID.randomUUID();
    private final VerifiedDockerTarget target = new VerifiedDockerTarget(
            UUID.randomUUID(), "a".repeat(64), "sha256:" + "b".repeat(64), "order-service");

    @Test
    void plansOnlyOneBoundedContainerWithAnImmutableArgumentVector() {
        var plan = DockerCpuCommandPlan.from(request("CPU_LOAD", 30, "{\"percent\":40}", target), target);
        assertThat(plan.executionId()).isEqualTo(executionId);
        assertThat(plan.target()).isEqualTo(target);
        assertThat(plan.createArguments()).containsExactly(
                "/opt/chaosblade/blade", "create", "docker", "cpu", "load",
                "--container-id", target.containerId(), "--cpu-percent", "40",
                "--cpu-count", "1", "--timeout", "30");
        assertThatThrownBy(() -> plan.createArguments().add("--force"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}", "null", "[]", "{\"percent\":9}", "{\"percent\":41}",
            "{\"percent\":2147483648}", "{\"percent\":40.0}",
            "{\"percent\":\"40; touch /tmp/pwn\"}", "{\"percent\":true}",
            "{\"percent\":20,\"percent\":40}", "{\"percent\":20} {}",
            "{\"percent\":20,\"container-id\":\"other\"}",
            "{\"percent\":20,\"timeout\":999}", "{\"percent\":20,\"cpu-count\":8}"
    })
    void rejectsNonWhitelistedOrAmbiguousParameters(String parameters) {
        assertThatThrownBy(() -> DockerCpuCommandPlan.from(
                request("CPU_LOAD", 30, parameters, target), target))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUnsupportedScenarioAndExcessiveDuration() {
        assertThatThrownBy(() -> DockerCpuCommandPlan.from(
                request("NETWORK_DELAY", 30, "{\"percent\":20}", target), target))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DockerCpuCommandPlan.from(
                request("CPU_LOAD", 31, "{\"percent\":20}", target), target))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requiresFreshIdentityMatchingContainerAndImage() {
        var request = request("CPU_LOAD", 30, "{\"percent\":20}", target);
        assertThatThrownBy(() -> DockerCpuCommandPlan.from(request, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DockerCpuCommandPlan.from(request,
                new VerifiedDockerTarget(target.targetId(), "c".repeat(64), target.imageId(), "order-service")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DockerCpuCommandPlan.from(request,
                new VerifiedDockerTarget(target.targetId(), target.containerId(), "sha256:" + "c".repeat(64), "order-service")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DockerCpuCommandPlan.from(
                request("CPU_LOAD", 30, "{\"percent\":20}", null), target))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private ReadyExperimentRequest request(String scenario, int seconds, String parameters,
                                          VerifiedDockerTarget identity) {
        return new ReadyExperimentRequest(executionId, UUID.randomUUID(), target.targetId(),
                scenario, seconds, parameters, identity);
    }
}
