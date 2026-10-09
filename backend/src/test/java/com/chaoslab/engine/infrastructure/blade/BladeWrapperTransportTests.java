package com.chaoslab.engine.infrastructure.blade;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BladeWrapperTransportTests {
    private static final String UID = "0123456789abcdef";
    private static final String HANDOFF = """
            {"version":1,"code":"OK","outcome":"HANDOFF","exitCode":0,
             "cleanupComplete":false,"handoff":true,"nativeUid":"0123456789abcdef",
             "response":{"code":200,"success":true,"result":"0123456789abcdef"}}
            """;

    @Test void translatesOnlyMatchingPrivilegedHandoff() {
        var result = BladeProcessChannel.decodeWrapper(HANDOFF, "create-cpu", UID);
        assertThat(result.outcome()).isEqualTo(ProcessRunResult.Outcome.HANDOFF);
        assertThat(result.cleanupComplete()).isFalse();
        assertThat(new BladeResponseDecoder(BladeExecutionSnapshot.CRI_CPU_V1).decodeCreate(result).uid()).isEqualTo(UID);
    }

    @Test void unknownMalformedOrWrongUidNeverClaimsPrivilegedCleanup() {
        for (String value : java.util.List.of("", "{}", HANDOFF + "{}",
                HANDOFF.replace("0123456789abcdef", "fedcba9876543210"),
                HANDOFF.replace("\"cleanupComplete\":false", "\"cleanupComplete\":true"),
                HANDOFF.replace("\"version\":1", "\"version\":1,\"version\":1"))) {
            var result = BladeProcessChannel.decodeWrapper(value, "create-cpu", UID);
            assertThat(result.outcome()).isEqualTo(ProcessRunResult.Outcome.IO_FAILED);
            assertThat(result.cleanupComplete()).isFalse();
        }
    }

    @Test void statusDestroyRejectHandoff() {
        for (String operation : java.util.List.of("status", "destroy")) {
            assertThat(BladeProcessChannel.decodeWrapper(HANDOFF, operation, UID).outcome())
                    .isEqualTo(ProcessRunResult.Outcome.IO_FAILED);
            String strict = HANDOFF.replace("HANDOFF", "EXITED").replace("\"cleanupComplete\":false", "\"cleanupComplete\":true")
                    .replace("\"handoff\":true", "\"handoff\":false");
            assertThat(BladeProcessChannel.decodeWrapper(strict, operation, UID).outcome())
                    .isEqualTo(ProcessRunResult.Outcome.EXITED);
        }
    }

    @Test void currentCandidateCannotProveActiveRecoveryFromAnyAcknowledgement() throws Exception {
        String binary = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        var runner = new BoundedProcessRunner(java.nio.file.Path.of(System.getProperty("java.home"), "bin", binary),
                java.nio.file.Path.of(".").toRealPath(), java.util.Map.of(), java.time.Duration.ofSeconds(1), 65536);
        var channel = new BladeProcessChannel(runner);
        // No run() or root command: this conservative predicate performs no I/O.
        for (String result : java.util.List.of(
                "{\"code\":200,\"success\":true,\"result\":{\"target\":\"cpu\",\"action\":\"fullload\",\"flags\":{\"uid\":\""+UID+"\"},\"ActionProcessHang\":false}}",
                "{\"code\":200,\"success\":true,\"result\":\"command: cri cpu fullload --uid="+UID+", destroy time: 2026-10-09T00:00:01Z\"}")) {
            assertThat(channel.activeRecoveryProvenance(null, ChaosBladeEngineTests.strict(result)))
                    .isEqualTo(BladeProcessChannel.ActiveRecoveryProvenance.NOT_CONFIRMED);
        }
    }

    static tools.jackson.databind.node.ObjectNode policy() {
        var p = tools.jackson.databind.json.JsonMapper.builder().build().createObjectNode();
        p.put("deployment", "REAL");
        p.put("executable", "/opt/chaoslab/m1/api3-identified/blade-chaoslab-api3-identified");
        p.put("stateDirectory", "/var/lib/chaoslab-m1/state");
        p.put("containerId", "a".repeat(64)); p.put("imageId", "sha256:"+"b".repeat(64));
        p.put("containerName", "chaoslab-cpu-sandbox"); p.put("user", "65534:65534");
        p.put("nanoCpus", 500000000); p.put("memory", 134217728); p.put("pids", 32);
        p.put("cpuPercent", 10); p.put("durationSeconds", 10); p.put("nodeId", "node-1"); p.put("stateId", "state-1");
        p.put("toolSha256", "c".repeat(64)); p.put("nsexecSha256", "d".repeat(64));
        p.put("chaosOsSha256", "e".repeat(64)); p.put("yamlSha256", "f".repeat(64));
        return p;
    }

    static String preflight(tools.jackson.databind.node.ObjectNode p) throws Exception {
        String digest = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(p.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        return "{\"version\":1,\"code\":\"OK\",\"cleanupComplete\":false,\"handoff\":false,\"policy\":"+p
                +",\"policyDigest\":\""+digest+"\"}";
    }

    @Test void rootPreflightRequiresFullTypedPolicyAndDigest() throws Exception {
        var p = policy(); String good = preflight(p);
        assertThat(BladeProcessChannel.decodePreflight(good)).isEqualTo(p);
        for (String bad : java.util.List.of("{}", good+"{}", good.replace("\"version\":1", "\"version\":1,\"version\":1"),
                good.replace("\"code\":\"OK\"", "\"code\":\"TARGET_UNKNOWN\""),
                good.replace("\"handoff\":false", "\"handoff\":true"),
                good.replace("\"cpuPercent\":10", "\"cpuPercent\":11"))) {
            assertThatThrownBy(() -> BladeProcessChannel.decodePreflight(bad)).isInstanceOf(RuntimeException.class);
        }
        p.put("cpuPercent", "10");
        String wrongType = preflight(p);
        assertThatThrownBy(() -> BladeProcessChannel.decodePreflight(wrongType)).isInstanceOf(RuntimeException.class);
    }

    @Test void wrapperIdentityRejectsWrongTargetPinsLimitsAndFakeWithoutReadingRootFiles() {
        var p = policy();
        var dep = new DockerCpuCommandPlan.Deployment(java.nio.file.Path.of(p.path("executable").asText()).toAbsolutePath(),
                java.nio.file.Path.of(p.path("stateDirectory").asText()).toAbsolutePath());
        p.put("executable", dep.executable().toString()); p.put("stateDirectory", dep.stateDirectory().toString());
        var channel = org.mockito.Mockito.mock(BladeProcessChannel.class);
        org.mockito.Mockito.when(channel.preflight()).thenAnswer(call -> p);
        var verifier = BladeLocalIdentityVerifier.viaWrapper(java.nio.file.Path.of("unreadable-root-node-marker").toAbsolutePath(),
                dep, "api3", "c".repeat(64), java.util.Map.of("bin/nsexec", "d".repeat(64),
                        "bin/chaos_os", "e".repeat(64), "yaml/chaosblade-cri-spec-1.8.1.yaml", "f".repeat(64)), channel);
        var now = java.time.Instant.now();
        var saved = new BladeExecutionSnapshot(java.util.UUID.randomUUID(),
                new com.chaoslab.safety.application.model.VerifiedDockerTarget(java.util.UUID.randomUUID(),
                        "a".repeat(64), "sha256:"+"b".repeat(64), "order-service"), "node-1", "state-1", "api3",
                "c".repeat(64), 10, 10, now, now.plusSeconds(10), UID, BladeExecutionSnapshot.CRI_CPU_V1);
        assertThat(verifier.verify(saved)).isEqualTo(BladeLocalIdentityVerifier.Result.LOCAL_IDENTITY_MATCHED_NEEDS_TARGET_CHECK);
        for (String key : java.util.List.of("deployment", "executable", "stateDirectory", "containerId", "imageId",
                "nodeId", "stateId", "toolSha256", "nsexecSha256", "chaosOsSha256", "yamlSha256", "cpuPercent", "durationSeconds")) {
            var original = p.get(key);
            p.put(key, "wrong");
            assertThat(verifier.verify(saved)).isNotEqualTo(BladeLocalIdentityVerifier.Result.LOCAL_IDENTITY_MATCHED_NEEDS_TARGET_CHECK);
            p.set(key, original);
        }
        org.mockito.Mockito.when(channel.preflight()).thenThrow(new IllegalStateException());
        assertThat(verifier.verify(saved)).isEqualTo(BladeLocalIdentityVerifier.Result.LOCAL_EVIDENCE_UNAVAILABLE);
    }

    @Test void observationRequiresExactSubjectAndCpuEvidenceForHealthy() {
        var h = new BladeRecoveryHandle(java.util.UUID.randomUUID(), "node-1",
                new com.chaoslab.safety.application.model.VerifiedDockerTarget(java.util.UUID.randomUUID(),
                        "a".repeat(64), "sha256:"+"b".repeat(64), "order-service"), UID, BladeExecutionSnapshot.CRI_CPU_V1);
        String json = "{\"version\":1,\"code\":\"OK\",\"cleanupComplete\":false,\"handoff\":false,\"observation\":{"
                +"\"executionId\":\""+h.executionId()+"\",\"nativeUid\":\""+UID+"\",\"nodeId\":\"node-1\","
                +"\"containerId\":\""+h.target().containerId()+"\",\"imageId\":\""+h.target().imageId()+"\","
                +"\"observedAt\":\"2026-10-06T14:00:00Z\",\"residual\":\"CLEAR\",\"health\":\"HEALTHY\",\"cpuPercent\":0.1,\"baselinePercent\":0}}";
        assertThat(BladeProcessChannel.decodeObservation(json,h).health()).isEqualTo(BladeRecoveryEvidenceGate.HealthObservation.HEALTHY);
        for (String wrong : java.util.List.of(json.replace(UID,"fedcba9876543210"), json.replace("node-1","node-2"),
                json.replace("a".repeat(64), "c".repeat(64)), json.replace(h.executionId().toString(), java.util.UUID.randomUUID().toString()))) {
            assertThatThrownBy(() -> BladeProcessChannel.decodeObservation(wrong,h))
                    .isInstanceOf(com.chaoslab.engine.application.EngineRecoveryException.class)
                    .hasMessage("RECOVERY_IDENTITY_REJECTED");
        }
        for (String bad : java.util.List.of(json+"{}", json.replace(UID,"fedcba9876543210"),
                json.replace("node-1","node-2"), json.replace(h.executionId().toString(), java.util.UUID.randomUUID().toString()),
                json.replace("\"cpuPercent\":0.1", "\"cpuPercent\":10"),json.replace("\"baselinePercent\":0", "\"baselinePercent\":false"))) {
            assertThatThrownBy(() -> BladeProcessChannel.decodeObservation(bad,h)).isInstanceOf(RuntimeException.class);
        }
    }
}
