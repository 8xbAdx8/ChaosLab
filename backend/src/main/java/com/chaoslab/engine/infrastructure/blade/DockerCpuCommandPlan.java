package com.chaoslab.engine.infrastructure.blade;

import com.chaoslab.engine.application.model.ReadyExperimentRequest;
import com.chaoslab.safety.application.model.VerifiedDockerTarget;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Pure command contract. Does not launch a process or register a real engine. */
public final class DockerCpuCommandPlan {
    public static final String EXECUTABLE = "/opt/chaosblade/blade";
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private final UUID executionId;
    private final VerifiedDockerTarget target;
    private final int percent;
    private final int durationSeconds;

    private DockerCpuCommandPlan(ReadyExperimentRequest request, int percent) {
        this.executionId = request.executionId();
        this.target = request.verifiedTarget();
        this.percent = percent;
        this.durationSeconds = request.durationSeconds();
    }

    public static DockerCpuCommandPlan from(
            ReadyExperimentRequest request, VerifiedDockerTarget freshlyVerifiedTarget
    ) {
        Objects.requireNonNull(request);
        if (request.verifiedTarget() == null
                || !request.verifiedTarget().equals(freshlyVerifiedTarget)) {
            throw new IllegalArgumentException("target identity must be reverified before planning");
        }
        if (!"CPU_LOAD".equals(request.scenarioCode())) {
            throw new IllegalArgumentException("only Docker CPU_LOAD is allowed");
        }
        if (request.durationSeconds() > 30) {
            throw new IllegalArgumentException("real CPU duration must be 1 to 30 seconds");
        }
        if (request.parameters().length() > 128) {
            throw new IllegalArgumentException("CPU parameters are too large");
        }
        JsonNode parameters;
        try {
            parameters = JSON.readTree(request.parameters());
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("CPU parameters must be strict JSON", exception);
        }
        JsonNode value = parameters == null ? null : parameters.get("percent");
        if (parameters == null || !parameters.isObject() || parameters.size() != 1
                || value == null || !value.isIntegralNumber() || !value.canConvertToInt()
                || value.intValue() < 10 || value.intValue() > 40) {
            throw new IllegalArgumentException("only integer percent from 10 to 40 is allowed");
        }
        return new DockerCpuCommandPlan(request, value.intValue());
    }

    public UUID executionId() {
        return executionId;
    }

    public VerifiedDockerTarget target() {
        return target;
    }

    public List<String> createArguments() {
        return cpuArguments(target, percent, durationSeconds);
    }

    public int percent() { return percent; }

    public int durationSeconds() { return durationSeconds; }

    static List<String> cpuArguments(VerifiedDockerTarget target, int percent, int durationSeconds) {
        return List.of(EXECUTABLE, "create", "docker", "cpu", "load",
                "--container-id", target.containerId(), "--cpu-percent", Integer.toString(percent),
                "--cpu-count", "1", "--timeout", Integer.toString(durationSeconds));
    }
}
