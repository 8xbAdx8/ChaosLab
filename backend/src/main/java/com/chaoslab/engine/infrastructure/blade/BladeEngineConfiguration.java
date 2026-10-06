package com.chaoslab.engine.infrastructure.blade;

import com.chaoslab.safety.application.port.TargetIdentityVerifier;
import com.chaoslab.target.application.port.TargetRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;

/** No HTTP configuration, no tool discovery or fallback, no process launched during startup. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "chaoslab.engine", havingValue = "blade")
public class BladeEngineConfiguration {
    @Bean
    DockerCpuCommandPlan.Deployment bladeDeployment(Environment env) {
        return new DockerCpuCommandPlan.Deployment(Path.of(required(env, "executable")), Path.of(required(env, "state-directory")));
    }
    @Bean
    BladeLocalIdentityVerifier bladeLocalIdentityVerifier(Environment env, DockerCpuCommandPlan.Deployment deployment, BladeProcessChannel channel) {
        return BladeLocalIdentityVerifier.viaWrapper(Path.of(required(env, "node-marker")), deployment,
                required(env, "tool-version"), required(env, "cli-sha256"), Map.of(
                "bin/nsexec", required(env, "nsexec-sha256"), "bin/chaos_os", required(env, "chaos-os-sha256"),
                "yaml/chaosblade-cri-spec-1.8.1.yaml", required(env, "cri-yaml-sha256")), channel);
    }
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(TargetIdentityVerifier.class)
    TargetIdentityVerifier bladeTargetIdentityVerifier(BladeProcessChannel channel, DockerCpuCommandPlan.Deployment deployment,
            TargetRepository targets) {
        return target -> {
            if (!target.isEnabled() || target.getType() != com.chaoslab.target.domain.TargetType.DOCKER_CONTAINER
                    || target.getEnvironment() != com.chaoslab.target.domain.TargetEnvironment.CHAOS_LAB
                    || !"order-service".equals(target.getName())
                    || targets.findAll().stream().filter(t -> "order-service".equals(t.getName())).count() != 1)
                return com.chaoslab.safety.application.model.TargetIdentityVerification.rejected("M1 target alias rejected");
            try {
                var p = channel.preflight();
                if (!"REAL".equals(p.path("deployment").asText())
                        || !deployment.executable().toString().equals(p.path("executable").asText())
                        || !deployment.stateDirectory().toString().equals(p.path("stateDirectory").asText()))
                    throw new IllegalStateException();
                return com.chaoslab.safety.application.model.TargetIdentityVerification.verified(
                        new com.chaoslab.safety.application.model.VerifiedDockerTarget(target.getId(),
                                p.path("containerId").asText(), p.path("imageId").asText(), "order-service"));
            } catch (RuntimeException unavailable) {
                return com.chaoslab.safety.application.model.TargetIdentityVerification.rejected("root target preflight unavailable");
            }
        };
    }
    @Bean
    BladeProcessChannel bladeProcessChannel(DockerCpuCommandPlan.Deployment deployment) throws java.io.IOException {
        return BladeProcessChannel.privileged(deployment);
    }
    @Bean
    ChaosBladeEngine chaosBladeEngine(Environment env, DockerCpuCommandPlan.Deployment deployment,
            TargetRepository targets, TargetIdentityVerifier verifier, BladeLocalIdentityVerifier local,
            JdbcBladeExecutionJournal journal, BladeProcessChannel channel, Clock clock) {
        String node = required(env, "node-id"), state = required(env, "state-id");
        if (!node.matches("[a-z0-9][a-z0-9-]{0,63}") || !state.matches("[a-z0-9][a-z0-9-]{0,63}"))
            throw new IllegalArgumentException("invalid Blade deployment identity");
        return new ChaosBladeEngine(deployment, node, state, required(env, "tool-version"), required(env, "cli-sha256"),
                targets, verifier, local, journal, channel, clock);
    }
    private static String required(Environment env, String key) {
        String value = env.getProperty("chaoslab.blade." + key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("missing Blade deployment setting: " + key);
        return value;
    }
}
