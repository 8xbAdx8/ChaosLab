package com.chaoslab.console.fakedemo;

import com.chaoslab.engine.application.port.ChaosEngine;
import com.chaoslab.engine.infrastructure.fake.FakeChaosEngine;
import com.chaoslab.target.application.port.TargetRepository;
import com.chaoslab.target.domain.*;
import java.nio.file.*;
import java.util.UUID;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;

/** Local demo boundary, not a real-execution permission. Validate before DataSource/Flyway beans. */
@Configuration(proxyBeanMethods = false)
@Profile("fake-demo")
public class FakeDemoConfiguration {

    public static final UUID TARGET_ID = UUID.fromString(
        "00000000-0000-0000-0000-000000000401"
    );
    private final ChaosEngine engine;
    private final Environment env;

    public FakeDemoConfiguration(ChaosEngine engine, Environment env) {
        if (
            engine.getClass() != FakeChaosEngine.class || !engine.simulated()
        ) throw new IllegalStateException("FAKE_DEMO_ENGINE_REJECTED");
        this.engine = engine;
        this.env = env;
    }

    @Bean
    static BeanFactoryPostProcessor fakeDemoBeforeDatabase(Environment env) {
        validateSettings(env);
        return factory -> {};
    }

    public static void validateSettings(Environment env) {
        String dir = env.getProperty("chaoslab.fake-demo.directory", "");
        boolean safeDirectory;
        try {
            Path p = Path.of(dir).toAbsolutePath().normalize();
            safeDirectory =
                !dir.isBlank() &&
                !dir.contains(";") &&
                !dir.contains("?") &&
                !dir.contains("\\") &&
                p.getFileName().toString().equals("chaoslab-fake-demo");
            for (Path a = p; a != null; a = a.getParent()) if (
                Files.isSymbolicLink(a)
            ) safeDirectory = false;
            if (
                Files.isSymbolicLink(p.resolve("chaoslab_fake_demo.mv.db"))
            ) safeDirectory = false;
        } catch (RuntimeException badPath) {
            safeDirectory = false;
        }
        String expected =
            "jdbc:h2:file:" +
            dir +
            "/chaoslab_fake_demo;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE";
        if (
            !env.matchesProfiles("fake-demo") ||
            env.matchesProfiles("console", "history-demo", "legacy-api-test") ||
            !"FAKE_DEMO".equals(env.getProperty("chaoslab.fake-demo.mode")) ||
            !"fake".equals(env.getProperty("chaoslab.engine")) ||
            env.getProperty(
                "chaoslab.console.read-only",
                Boolean.class,
                true
            ) ||
            !"127.0.0.1".equals(env.getProperty("server.address")) ||
            !safeDirectory ||
            !expected.equals(env.getProperty("spring.datasource.url")) ||
            !"org.h2.Driver".equals(
                env.getProperty("spring.datasource.driver-class-name")
            ) ||
            !env.getProperty("chaoslab.demo.binding-script", "").isEmpty() ||
            !env
                .getProperty(
                    "spring.flyway.locations",
                    "classpath:db/migration"
                )
                .equals("classpath:db/migration") ||
            env.getProperty("spring.flyway.url") != null ||
            env.getProperty("spring.datasource.jndi-name") != null ||
            env.getProperty("spring.datasource.hikari.jdbc-url") != null ||
            env.getProperty(
                "spring.datasource.hikari.data-source-class-name"
            ) != null
        ) {
            throw new IllegalStateException("FAKE_DEMO_CONFIGURATION_REJECTED");
        }
    }

    public boolean enabled() {
        // Both the trusted mode AND the instantiated engine matter; ADMIN alone is insufficient.
        return (
            engine.getClass() == FakeChaosEngine.class &&
            engine.simulated() &&
            env.matchesProfiles("fake-demo") &&
            "FAKE_DEMO".equals(env.getProperty("chaoslab.fake-demo.mode")) &&
            "fake".equals(env.getProperty("chaoslab.engine")) &&
            !env.getProperty("chaoslab.console.read-only", Boolean.class, true)
        );
    }

    public static boolean mutation(String method, String path) {
        String id =
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
        if (method.equals("PUT")) return path.matches(
            "/api/v1/experiments/" + id
        );
        return (
            method.equals("POST") &&
            (path.equals("/api/v1/experiments") ||
                path.matches(
                    "/api/v1/experiments/" +
                        id +
                        "/(validation|dry-run|executions)"
                ) ||
                path.matches(
                    "/api/v1/experiments/" +
                        id +
                        "/executions/" +
                        id +
                        "/(destroy|reports)"
                ))
        );
    }

    @Bean
    org.springframework.beans.factory.SmartInitializingSingleton fakeDemoTarget(
        TargetRepository targets,
        com.chaoslab.execution.application.port.ExperimentExecutionRepository executions,
        com.chaoslab.experiment.application.port.ExperimentRepository experiments
    ) {
        // Initialize persisted simulations before scheduled recovery begins.
        return () -> {
            if (!enabled()) throw new IllegalStateException(
                "FAKE_DEMO_ENGINE_REJECTED"
            );
            var existing = targets.findById(TARGET_ID);
            if (existing.isEmpty()) targets.save(
                Target.register(
                    TARGET_ID,
                    "FAKE classroom target · no host access",
                    TargetType.LINUX_HOST,
                    TargetEnvironment.LOCAL
                )
            );
            else if (
                existing.get().getType() != TargetType.LINUX_HOST ||
                existing.get().getEnvironment() != TargetEnvironment.LOCAL
            ) throw new IllegalStateException("FAKE_DEMO_TARGET_REJECTED");
            for (var execution : executions.findAllByStatus(
                com.chaoslab.execution.domain.ExperimentExecutionStatus.RUNNING
            )) {
                if (
                    !("fake-" + execution.getId()).equals(
                        execution.getEngineExperimentId()
                    ) ||
                    experiments
                        .findById(execution.getExperimentId())
                        .map(
                            e ->
                                e.getStatus() !=
                                com.chaoslab.experiment.domain.ExperimentStatus.RUNNING
                        )
                        .orElse(true)
                ) throw new IllegalStateException(
                    "FAKE_DEMO_PERSISTED_IDENTITY_REJECTED"
                );
                ((FakeChaosEngine) engine).restoreRunningSimulation(
                    execution.getId()
                );
            }
        };
    }
}
