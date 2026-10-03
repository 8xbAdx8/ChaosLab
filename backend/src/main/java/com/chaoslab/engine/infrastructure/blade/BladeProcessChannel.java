package com.chaoslab.engine.infrastructure.blade;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/** Not a Spring bean. No application route or real engine enables this transport. */
public final class BladeProcessChannel {
    private final BoundedProcessRunner runner;
    private final DockerCpuCommandPlan.Deployment deployment;

    public BladeProcessChannel(Path workingDirectory, Map<String, String> environment) throws IOException {
        this(DockerCpuCommandPlan.DEFAULT_DEPLOYMENT, workingDirectory, environment);
    }

    public BladeProcessChannel(DockerCpuCommandPlan.Deployment deployment, Path workingDirectory,
                               Map<String, String> environment) throws IOException {
        this.deployment = java.util.Objects.requireNonNull(deployment);
        if (environment.containsKey("CHAOSBLADE_DATAFILE_PATH"))
            throw new IllegalArgumentException("state path comes only from deployment");
        var boundEnvironment = new java.util.HashMap<>(environment);
        boundEnvironment.put("CHAOSBLADE_DATAFILE_PATH", deployment.stateDirectory().toString());
        runner = new BoundedProcessRunner(deployment.executable(), workingDirectory,
                boundEnvironment, Duration.ofSeconds(5), 64 * 1024);
    }

    // Package-only seam for harmless executable fixtures; not an application API.
    BladeProcessChannel(BoundedProcessRunner runner) {
        this.runner = java.util.Objects.requireNonNull(runner);
        this.deployment = DockerCpuCommandPlan.DEFAULT_DEPLOYMENT;
    }

    public ProcessRunResult create(DockerCpuCommandPlan plan, BooleanSupplier cancelled) {
        if (!deployment.equals(plan.deployment())) throw new IllegalArgumentException("plan deployment mismatch");
        return run(plan.createArguments(), cancelled, BoundedProcessRunner.Lifecycle.CONTROLLED_HANDOFF);
    }

    public ProcessRunResult status(BladeRecoveryHandle handle, BooleanSupplier cancelled) {
        return run(handle.statusArguments(deployment), cancelled, BoundedProcessRunner.Lifecycle.STRICT_FOREGROUND);
    }

    public ProcessRunResult destroy(BladeRecoveryHandle handle, BooleanSupplier cancelled) {
        return run(handle.destroyArguments(deployment), cancelled, BoundedProcessRunner.Lifecycle.STRICT_FOREGROUND);
    }

    private ProcessRunResult run(List<String> command, BooleanSupplier cancelled, BoundedProcessRunner.Lifecycle lifecycle) {
        if (command.isEmpty() || !Path.of(command.getFirst()).toAbsolutePath().normalize().equals(deployment.executable()))
            throw new IllegalArgumentException("command deployment mismatch");
        return runner.run(command.subList(1, command.size()), cancelled, lifecycle);
    }
}
