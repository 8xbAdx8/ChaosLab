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
    private boolean wrapperTransport;

    public static BladeProcessChannel privileged(DockerCpuCommandPlan.Deployment deployment) throws IOException {
        var runner = new BoundedProcessRunner(Path.of("/usr/bin/sudo"), Path.of("/"),
                Map.of("PATH", "/usr/bin:/bin", "LANG", "C", "LC_ALL", "C"), Duration.ofSeconds(10), 64 * 1024);
        var channel = new BladeProcessChannel(runner, deployment);
        channel.wrapperTransport = true;
        return channel;
    }

    private BladeProcessChannel(BoundedProcessRunner runner, DockerCpuCommandPlan.Deployment deployment) {
        this.runner = runner;
        this.deployment = deployment;
    }

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
        if (wrapperTransport) throw new IllegalArgumentException("preallocated UID required");
        if (!deployment.equals(plan.deployment())) throw new IllegalArgumentException("plan deployment mismatch");
        return run(plan.createArguments(), cancelled, BoundedProcessRunner.Lifecycle.CONTROLLED_HANDOFF);
    }

    public ProcessRunResult status(BladeRecoveryHandle handle, BooleanSupplier cancelled) {
        if (wrapperTransport) return wrapper("status", handle.executionId(), handle.uid(), cancelled);
        return run(handle.statusArguments(deployment), cancelled, BoundedProcessRunner.Lifecycle.STRICT_FOREGROUND);
    }

    public ProcessRunResult create(DockerCpuCommandPlan plan, String nativeUid, BooleanSupplier cancelled) {
        if (nativeUid == null || !nativeUid.matches("[0-9a-f]{16}"))
            throw new IllegalArgumentException("preallocated CRI UID required");
        if (!deployment.equals(plan.deployment())) throw new IllegalArgumentException("plan deployment mismatch");
        if (wrapperTransport) return wrapper("create-cpu", plan.executionId(), nativeUid, cancelled);
        var arguments = new java.util.ArrayList<>(plan.createArguments());
        arguments.add("--uid");
        arguments.add(nativeUid);
        return run(arguments, cancelled, BoundedProcessRunner.Lifecycle.CONTROLLED_HANDOFF);
    }

    public ProcessRunResult destroy(BladeRecoveryHandle handle, BooleanSupplier cancelled) {
        if (wrapperTransport) return wrapper("destroy", handle.executionId(), handle.uid(), cancelled);
        return run(handle.destroyArguments(deployment), cancelled, BoundedProcessRunner.Lifecycle.STRICT_FOREGROUND);
    }

    private ProcessRunResult wrapper(String operation, java.util.UUID executionId, String uid, BooleanSupplier cancelled) {
        // No path, target or environment comes from the request. The root policy binds them.
        if (uid == null || !uid.matches("[0-9a-f]{16}")) throw new IllegalArgumentException("invalid native UID");
        String input = "{\"operation\":\"" + operation + "\",\"executionId\":\"" + executionId
                + "\",\"nativeUid\":\"" + uid + "\"}";
        var transport = runner.run(List.of("-n", "--", "/usr/local/libexec/chaoslab-m1-wrapper"), cancelled,
                BoundedProcessRunner.Lifecycle.CONTROLLED_HANDOFF, input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        // Java cannot certify privileged descendants. Only a complete successful wrapper envelope can.
        if (transport.outcome() != ProcessRunResult.Outcome.HANDOFF || !Integer.valueOf(0).equals(transport.exitCode())
                || !transport.stderr().isEmpty())
            return new ProcessRunResult(ProcessRunResult.Outcome.IO_FAILED, transport.exitCode(), "", "", false);
        return decodeWrapper(transport.stdout(), operation, uid);
    }

    static ProcessRunResult decodeWrapper(String text, String operation, String uid) {
        try {
            var mapper = tools.jackson.databind.json.JsonMapper.builder()
                    .enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
            var root = mapper.readTree(text);
            if (!root.isObject() || root.path("version").asInt(-1) != 1 || !"OK".equals(root.path("code").asText())
                    || !root.path("exitCode").isIntegralNumber() || root.path("exitCode").asInt(-1) != 0
                    || !root.path("cleanupComplete").isBoolean() || !root.path("handoff").isBoolean()
                    || !root.path("response").isObject()) throw new IllegalArgumentException();
            boolean create = "create-cpu".equals(operation);
            if (create ? (!"HANDOFF".equals(root.path("outcome").asText()) || !root.path("handoff").asBoolean()
                    || root.path("cleanupComplete").asBoolean() || !uid.equals(root.path("nativeUid").asText()))
                    : (!"EXITED".equals(root.path("outcome").asText()) || root.path("handoff").asBoolean()
                    || !root.path("cleanupComplete").asBoolean())) throw new IllegalArgumentException();
            return new ProcessRunResult(create ? ProcessRunResult.Outcome.HANDOFF : ProcessRunResult.Outcome.EXITED,
                    0, root.path("response").toString(), "", !create);
        } catch (RuntimeException invalid) {
            return new ProcessRunResult(ProcessRunResult.Outcome.IO_FAILED, null, "", "", false);
        }
    }

    private ProcessRunResult run(List<String> command, BooleanSupplier cancelled, BoundedProcessRunner.Lifecycle lifecycle) {
        if (command.isEmpty() || !Path.of(command.getFirst()).toAbsolutePath().normalize().equals(deployment.executable()))
            throw new IllegalArgumentException("command deployment mismatch");
        return runner.run(command.subList(1, command.size()), cancelled, lifecycle);
    }
}
