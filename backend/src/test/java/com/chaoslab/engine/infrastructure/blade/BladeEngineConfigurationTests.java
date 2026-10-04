package com.chaoslab.engine.infrastructure.blade;

import com.chaoslab.engine.application.port.ChaosEngine;
import com.chaoslab.engine.infrastructure.fake.FakeChaosEngine;
import com.chaoslab.safety.application.port.TargetIdentityVerifier;
import com.chaoslab.target.application.port.TargetRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class BladeEngineConfigurationTests {
    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withUserConfiguration(BladeEngineConfiguration.class, FakeChaosEngine.class)
                .withBean(TargetRepository.class, () -> mock(TargetRepository.class))
                .withBean(TargetIdentityVerifier.class, () -> mock(TargetIdentityVerifier.class))
                .withBean(JdbcBladeExecutionJournal.class, () -> mock(JdbcBladeExecutionJournal.class))
                .withBean(Clock.class, Clock::systemUTC)
                .withBean(BeanFactoryPostProcessor.class, () -> factory -> {
                    // Do not construct even a real channel in adapter/configuration tests.
                    var registry = (BeanDefinitionRegistry) factory;
                    if (registry.containsBeanDefinition("bladeProcessChannel")) {
                        registry.removeBeanDefinition("bladeProcessChannel");
                        factory.registerSingleton("bladeProcessChannel", mock(BladeProcessChannel.class));
                    }
                });
    }
    private Map<String,String> approved() {
        Map<String,String> props = new LinkedHashMap<>();
        props.put("executable", Path.of("unused-blade").toAbsolutePath().toString());
        props.put("state-directory", Path.of(".").toAbsolutePath().normalize().toString());
        props.put("node-marker", Path.of("unused-node-marker").toAbsolutePath().toString());
        props.put("node-id", "node-1"); props.put("state-id", "state-1"); props.put("tool-version", "api3");
        for (String pin : List.of("cli-sha256", "nsexec-sha256", "chaos-os-sha256", "cri-yaml-sha256")) props.put(pin, "c".repeat(64));
        return props;
    }
    private String[] properties(Map<String,String> map) {
        var values = new ArrayList<String>(); values.add("chaoslab.engine=blade");
        map.forEach((k,v) -> values.add("chaoslab.blade."+k+"="+v));
        return values.toArray(String[]::new);
    }
    @Test void unconfiguredAndExplicitFakeUseOnlyFake() {
        runner().run(c -> assertThat(c.getBean(ChaosEngine.class)).isInstanceOf(FakeChaosEngine.class));
        runner().withPropertyValues("chaoslab.engine=fake").run(c -> assertThat(c.getBean(ChaosEngine.class)).isInstanceOf(FakeChaosEngine.class));
    }
    @Test void explicitCompleteConfigurationSelectsBladeWithoutExecutingAnything() {
        runner().withPropertyValues(properties(approved())).run(c -> {
            assertThat(c).hasNotFailed();
            assertThat(c.getBean(ChaosEngine.class)).isInstanceOf(ChaosBladeEngine.class);
            assertThat(c).doesNotHaveBean(FakeChaosEngine.class);
            verifyNoInteractions(c.getBean(BladeProcessChannel.class));
        });
    }
    @Test void everyMissingDeploymentSettingFailsStartupInsteadOfFallingBack() {
        for (String missing : approved().keySet()) {
            var settings = approved(); settings.remove(missing);
            runner().withPropertyValues(properties(settings)).run(c -> assertThat(c).hasFailed());
        }
    }
    @Test void invalidPinAndUnknownSelectionNeverEnableReal() {
        var bad = approved(); bad.put("nsexec-sha256", "not-a-sha");
        runner().withPropertyValues(properties(bad)).run(c -> assertThat(c).hasFailed());
        runner().withPropertyValues("chaoslab.engine=unknown").run(c -> assertThat(c).doesNotHaveBean(ChaosEngine.class));
    }
}
