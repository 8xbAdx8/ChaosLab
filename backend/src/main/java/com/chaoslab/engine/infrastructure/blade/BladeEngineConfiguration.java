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
    BladeLocalIdentityVerifier bladeLocalIdentityVerifier(Environment env, DockerCpuCommandPlan.Deployment deployment) {
        return new BladeLocalIdentityVerifier(Path.of(required(env, "node-marker")), deployment.stateDirectory(), deployment,
                required(env, "tool-version"), required(env, "cli-sha256"), Map.of(
                "bin/nsexec", required(env, "nsexec-sha256"), "bin/chaos_os", required(env, "chaos-os-sha256"),
                "yaml/chaosblade-cri-spec-1.8.1.yaml", required(env, "cri-yaml-sha256")));
    }
    @Bean
    BladeProcessChannel bladeProcessChannel(DockerCpuCommandPlan.Deployment deployment) throws java.io.IOException {
        return new BladeProcessChannel(deployment, deployment.stateDirectory(),
                Map.of("PATH", "/usr/sbin:/usr/bin:/sbin:/bin", "LANG", "C", "LC_ALL", "C"));
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
