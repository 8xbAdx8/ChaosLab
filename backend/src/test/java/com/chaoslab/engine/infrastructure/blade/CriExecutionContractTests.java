package com.chaoslab.engine.infrastructure.blade;

import com.chaoslab.engine.application.model.ReadyExperimentRequest;
import com.chaoslab.safety.application.model.VerifiedDockerTarget;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Instant;
import java.security.MessageDigest;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CriExecutionContractTests {
    @TempDir Path directory;
    private static final String UID = "0123456789abcdef";
    private DockerCpuCommandPlan plan(DockerCpuCommandPlan.Deployment deployment) {
        var target = new VerifiedDockerTarget(UUID.randomUUID(), "a".repeat(64), "sha256:" + "b".repeat(64), "order-service");
        return DockerCpuCommandPlan.from(new ReadyExperimentRequest(UUID.randomUUID(), UUID.randomUUID(), target.targetId(),
                "CPU_LOAD", 20, "{\"percent\":20}", target), target, deployment);
    }
    private String sha(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }
    @Test
    void criArgumentsAndVersionedRecoveryAreExact() {
        var deployment = new DockerCpuCommandPlan.Deployment(directory.resolve("blade").toAbsolutePath());
        var plan = plan(deployment);
        assertThat(plan.createArguments()).containsExactly(deployment.executable().toString(), "create", "cri", "cpu", "fullload",
                "--container-runtime", "docker", "--container-id", plan.target().containerId(), "--cpu-percent", "20",
                "--cpu-count", "1", "--timeout", "20");
        var handle = new BladeRecoveryHandle(plan.executionId(), "node-1", plan.target(), UID, "CRI_CPU_V1");
        assertThat(handle.statusArguments(deployment)).containsExactly(deployment.executable().toString(), "status", UID, "--type", "create");
        assertThat(handle.destroyArguments(deployment)).containsExactly(deployment.executable().toString(), "destroy", UID);
        assertThatThrownBy(() -> new BladeRecoveryHandle(plan.executionId(), "node-1", plan.target(), "a".repeat(32), "CRI_CPU_V1"))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test
    void channelRejectsDifferentExecutableBeforeDispatch() {
        var runner = mock(BoundedProcessRunner.class);
        var channel = new BladeProcessChannel(runner);
        assertThatThrownBy(() -> channel.create(plan(new DockerCpuCommandPlan.Deployment(directory.resolve("other").toAbsolutePath())), () -> false))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(runner);
    }
    @Test
    void channelRejectsEnvironmentStateOverride() {
        var deployment = new DockerCpuCommandPlan.Deployment(directory.resolve("not-executed").toAbsolutePath());
        assertThatThrownBy(() -> new BladeProcessChannel(deployment, directory,
                Map.of("CHAOSBLADE_DATAFILE_PATH", directory.resolve("other").toString())))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test
    void identityWithoutUidChecksAllPinnedCompanionsAndSamePlanDeployment() throws Exception {
        Path root = directory.toRealPath();
        Path binary = Files.writeString(root.resolve("blade.exe"), "inert fixture");
        binary.toFile().setExecutable(true);
        Path node = Files.writeString(root.resolve("node"), "node-1");
        Path state = Files.createDirectory(root.resolve("state"));
        Files.writeString(state.resolve(BladeLocalIdentityVerifier.STATE_MARKER), "state-1");
        Map<String,String> pins = new HashMap<>();
        for (String name : List.of("bin/nsexec", "bin/chaos_os", "yaml/chaosblade-cri-spec-1.8.1.yaml")) {
            Path file = root.resolve(name);
            Files.createDirectories(file.getParent());
            Files.writeString(file, "inert companion");
            file.toFile().setExecutable(true);
            pins.put(name, sha(file));
        }
        var deployment = new DockerCpuCommandPlan.Deployment(binary, state);
        var plan = plan(deployment);
        var intent = BladeExecutionSnapshot.intent(plan, "node-1", "state-1", "api3", sha(binary), Instant.now());
        var verifier = new BladeLocalIdentityVerifier(node, state, deployment, "api3", sha(binary), pins);
        assertThat(verifier.verifyBeforeCreate(intent, plan)).isEqualTo(BladeLocalIdentityVerifier.Result.LOCAL_IDENTITY_MATCHED_NEEDS_TARGET_CHECK);
        assertThat(verifier.verify(intent)).isEqualTo(BladeLocalIdentityVerifier.Result.MISSING_RECOVERY_EVIDENCE);
        var recovery = new BladeExecutionSnapshot(intent.executionId(), intent.target(), intent.executorInstanceId(),
                intent.stateDirectoryId(), intent.toolVersion(), intent.toolSha256(), intent.cpuPercent(), intent.durationSeconds(),
                intent.recordedAt(), intent.recoveryDeadline(), UID, intent.format());
        assertThat(verifier.verify(recovery)).isEqualTo(BladeLocalIdentityVerifier.Result.LOCAL_IDENTITY_MATCHED_NEEDS_TARGET_CHECK);
        assertThat(verifier.verifyBeforeCreate(recovery, plan)).isEqualTo(BladeLocalIdentityVerifier.Result.LOCAL_IDENTITY_MATCHED_NEEDS_TARGET_CHECK);
        assertThat(new BladeLocalIdentityVerifier(node, state, binary, "api3", sha(binary)).verify(recovery))
                .isEqualTo(BladeLocalIdentityVerifier.Result.TOOL_PIN_MISMATCH);
        assertThat(verifier.verifyBeforeCreate(intent, plan(new DockerCpuCommandPlan.Deployment(root.resolve("other")))))
                .isEqualTo(BladeLocalIdentityVerifier.Result.MISSING_RECOVERY_EVIDENCE);
        for (String name : pins.keySet()) {
            Files.writeString(root.resolve(name), "tampered");
            assertThat(verifier.verifyBeforeCreate(intent, plan)).isEqualTo(BladeLocalIdentityVerifier.Result.TOOL_CONTENT_MISMATCH);
            Files.writeString(root.resolve(name), "inert companion");
        }
        assertThatThrownBy(() -> new BladeLocalIdentityVerifier(node, state, deployment, "api3", sha(binary), Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BladeLocalIdentityVerifier(node, state, new DockerCpuCommandPlan.Deployment(binary),
                "api3", sha(binary), pins)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test
    void createRequiresHandoffAndStrictCommandsNeverAcceptIt() {
        var decoder = new BladeResponseDecoder();
        String json = "{\"code\":200,\"success\":true,\"result\":\"" + UID + "\"}";
        var handoff = new ProcessRunResult(ProcessRunResult.Outcome.HANDOFF, 0, json, "", false);
        assertThat(decoder.decodeCreate(handoff).uid()).isEqualTo(UID);
        assertThatThrownBy(() -> decoder.decodeStatus(handoff)).isInstanceOf(BladeResponseDecoder.DecodeException.class);
        assertThatThrownBy(() -> decoder.decodeDestroy(handoff)).isInstanceOf(BladeResponseDecoder.DecodeException.class);
        for (var process : List.of(new ProcessRunResult(ProcessRunResult.Outcome.EXITED, 0, json, "", true),
                new ProcessRunResult(ProcessRunResult.Outcome.HANDOFF, 1, json, "", false),
                new ProcessRunResult(ProcessRunResult.Outcome.HANDOFF, 0, json, "", true)))
            assertThatThrownBy(() -> decoder.decodeCreate(process)).isInstanceOf(BladeResponseDecoder.DecodeException.class);
        assertThatThrownBy(() -> decoder.decodeCreate(new ProcessRunResult(ProcessRunResult.Outcome.HANDOFF, 0, "invalid", "", false)))
                .isInstanceOf(BladeResponseDecoder.DecodeException.class);
    }
    @Test
    void legacyAndCriResponsesAreNotInterpretedAsEachOther() {
        String json = "{\"code\":200,\"success\":true,\"result\":{\"Uid\":\"" + UID + "\",\"Command\":\"docker\",\"SubCommand\":\"cpu load\","
                + "\"Flag\":\"\",\"Status\":\"Destroyed\",\"Error\":\"\",\"CreateTime\":\"\",\"UpdateTime\":\"\"}}";
        var legacy = new ProcessRunResult(ProcessRunResult.Outcome.EXITED, 0, json, "", true);
        assertThat(new BladeResponseDecoder("DOCKER_CPU_V1").decodeStatus(legacy).uid()).isEqualTo(UID);
        assertThatThrownBy(() -> new BladeResponseDecoder().decodeStatus(legacy)).isInstanceOf(BladeResponseDecoder.DecodeException.class);
        var cri = new ProcessRunResult(ProcessRunResult.Outcome.EXITED, 0, json.replace("docker", "cri").replace("cpu load", "cpu fullload"), "", true);
        assertThatThrownBy(() -> new BladeResponseDecoder("DOCKER_CPU_V1").decodeStatus(cri)).isInstanceOf(BladeResponseDecoder.DecodeException.class);
        assertThatThrownBy(() -> new BladeResponseDecoder("UNKNOWN")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test
    void unknownDestroyShapesFailClosed() {
        String model = "{\"target\":\"cpu\",\"action\":\"fullload\",\"flags\":{\"cpu-count\":\"1\"},\"ActionProcessHang\":false}";
        for (String value : List.of("\"arbitrary success\"", model.replace("fullload", "load"),
                model.replace("false", "true"), model.replace("\"1\"", "1"), model.replace("\"target\"", "\"unknown\""),
                model.replace("\"target\"", "\"scope\":\"cri\",\"target\""))) {
            var process = new ProcessRunResult(ProcessRunResult.Outcome.EXITED, 0,
                    "{\"code\":200,\"success\":true,\"result\":" + value + "}", "", true);
            assertThatThrownBy(() -> new BladeResponseDecoder().decodeDestroy(process)).isInstanceOf(BladeResponseDecoder.DecodeException.class);
        }
    }
}
