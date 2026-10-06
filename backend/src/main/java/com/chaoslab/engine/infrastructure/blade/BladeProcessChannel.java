package com.chaoslab.engine.infrastructure.blade;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/** Fixed production wrapper transport; direct constructors are harmless fixture/legacy seams. */
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

    /** No request identity or caller paths. Root verifies its fixed deployment and live sandbox. */
    public tools.jackson.databind.JsonNode preflight() {
        if (!wrapperTransport) throw new IllegalStateException("privileged preflight required");
        return decodePreflight(transport("{\"operation\":\"preflight\"}", () -> false));
    }

    private String transport(String input, BooleanSupplier cancelled) {
        var transport = runner.run(List.of("-n", "--", "/usr/local/libexec/chaoslab-m1-wrapper"), cancelled,
                BoundedProcessRunner.Lifecycle.CONTROLLED_HANDOFF, input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if (transport.outcome() != ProcessRunResult.Outcome.HANDOFF || !Integer.valueOf(0).equals(transport.exitCode())
                || !transport.stderr().isEmpty()) throw new IllegalStateException("privileged transport unavailable");
        return transport.stdout();
    }

    static tools.jackson.databind.JsonNode decodePreflight(String text) {
        var root = strictJson().readTree(text);
        if (!root.isObject() || (root.size() != 6 && root.size() != 7) || !root.path("version").isIntegralNumber()
                || root.path("version").intValue() != 1 || !"OK".equals(root.path("code").asText())
                || !root.path("cleanupComplete").isBoolean() || root.path("cleanupComplete").booleanValue()
                || !root.path("handoff").isBoolean() || root.path("handoff").booleanValue()
                || !root.path("policy").isObject() || root.path("policy").size() != 18
                || !root.path("policyDigest").isTextual() || !root.path("policyDigest").asText().matches("[0-9a-f]{64}"))
            throw new IllegalStateException("invalid privileged preflight");
        var p = root.path("policy");
        var canonical = strictJson().createObjectNode();
        var numeric = java.util.Set.of("nanoCpus", "memory", "pids", "cpuPercent", "durationSeconds");
        for (String key : List.of("deployment", "executable", "stateDirectory", "containerId", "imageId",
                "containerName", "user", "nanoCpus", "memory", "pids", "cpuPercent", "durationSeconds",
                "nodeId", "stateId", "toolSha256", "nsexecSha256", "chaosOsSha256", "yamlSha256")) {
            if (numeric.contains(key) ? !p.path(key).isIntegralNumber() : !p.path(key).isTextual())
                throw new IllegalStateException("invalid policy field type");
            canonical.set(key, p.path(key));
        }
        try {
            String digest = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            if (!digest.equals(root.path("policyDigest").asText())) throw new IllegalStateException("policy digest mismatch");
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        if (!"chaoslab-cpu-sandbox".equals(p.path("containerName").asText()) || !"65534:65534".equals(p.path("user").asText())
                || p.path("nanoCpus").asLong(-1) != 500000000 || p.path("memory").asLong(-1) != 134217728
                || p.path("pids").asLong(-1) != 32) throw new IllegalStateException("unexpected sandbox policy");
        return p;
    }

    public record RecoveryObservation(BladeRecoveryHandle subject, java.time.Instant observedAt,
            BladeRecoveryEvidenceGate.ResidualObservation residual, BladeRecoveryEvidenceGate.HealthObservation health) { }

    public RecoveryObservation observe(BladeRecoveryHandle handle) {
        if (!wrapperTransport) return null; // Historical/direct fixtures cannot attest root probes.
        String input = "{\"operation\":\"observe\",\"executionId\":\""+handle.executionId()+"\",\"nativeUid\":\""+handle.uid()+"\"}";
        return decodeObservation(transport(input, () -> false), handle);
    }

    static RecoveryObservation decodeObservation(String text, BladeRecoveryHandle expected) {
        var root = strictJson().readTree(text);
        if (!root.isObject() || root.size() != 5 || !root.path("version").isIntegralNumber() || root.path("version").asInt(-1) != 1
                || !"OK".equals(root.path("code").asText()) || !root.path("cleanupComplete").isBoolean()
                || root.path("cleanupComplete").booleanValue() || !root.path("handoff").isBoolean()
                || root.path("handoff").booleanValue() || !root.path("observation").isObject())
            throw new IllegalStateException("invalid privileged observation");
        var o = root.path("observation");
        if (!expected.executionId().toString().equals(o.path("executionId").asText()) || !expected.uid().equals(o.path("nativeUid").asText())
                || !expected.executorInstanceId().equals(o.path("nodeId").asText())
                || !expected.target().containerId().equals(o.path("containerId").asText())
                || !expected.target().imageId().equals(o.path("imageId").asText()))
            throw new IllegalStateException("observation identity mismatch");
        var residual = BladeRecoveryEvidenceGate.ResidualObservation.valueOf(o.path("residual").asText());
        var health = BladeRecoveryEvidenceGate.HealthObservation.valueOf(o.path("health").asText());
        if (health == BladeRecoveryEvidenceGate.HealthObservation.HEALTHY) {
            double cpu = o.path("cpuPercent").asDouble(Double.NaN), baseline = o.path("baselinePercent").asDouble(Double.NaN);
            if (!o.path("cpuPercent").isNumber() || !o.path("baselinePercent").isNumber() || !Double.isFinite(cpu)
                    || !Double.isFinite(baseline) || baseline < 0 || baseline > 1 || cpu < 0 || cpu > baseline+1)
                throw new IllegalStateException("healthy observation lacks valid CPU recovery evidence");
        }
        return new RecoveryObservation(expected, java.time.Instant.parse(o.path("observedAt").asText()), residual, health);
    }

    private static tools.jackson.databind.json.JsonMapper strictJson() {
        return tools.jackson.databind.json.JsonMapper.builder()
                .enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    }

    private ProcessRunResult wrapper(String operation, java.util.UUID executionId, String uid, BooleanSupplier cancelled) {
        // No path, target or environment comes from the request. The root policy binds them.
        if (uid == null || !uid.matches("[0-9a-f]{16}")) throw new IllegalArgumentException("invalid native UID");
        String input = "{\"operation\":\"" + operation + "\",\"executionId\":\"" + executionId
                + "\",\"nativeUid\":\"" + uid + "\"}";
        // Java cannot certify privileged descendants. Only a complete successful wrapper envelope can.
        try { return decodeWrapper(transport(input, cancelled), operation, uid); }
        catch (RuntimeException unavailable) { return new ProcessRunResult(ProcessRunResult.Outcome.IO_FAILED, null, "", "", false); }
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
