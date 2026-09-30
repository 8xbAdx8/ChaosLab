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

    public BladeProcessChannel(Path workingDirectory, Map<String, String> environment) throws IOException {
        runner = new BoundedProcessRunner(Path.of(DockerCpuCommandPlan.EXECUTABLE), workingDirectory,
                environment, Duration.ofSeconds(5), 64 * 1024);
    }

    public ProcessRunResult create(DockerCpuCommandPlan plan, BooleanSupplier cancelled) {
        return run(plan.createArguments(), cancelled);
    }

    public ProcessRunResult status(BladeRecoveryHandle handle, BooleanSupplier cancelled) {
        return run(handle.statusArguments(), cancelled);
    }

    public ProcessRunResult destroy(BladeRecoveryHandle handle, BooleanSupplier cancelled) {
        return run(handle.destroyArguments(), cancelled);
    }

    private ProcessRunResult run(List<String> command, BooleanSupplier cancelled) {
        return runner.run(command.subList(1, command.size()), cancelled);
    }
}
