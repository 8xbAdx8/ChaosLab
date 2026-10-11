package com.chaoslab.console.history;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** One immutable, bounded projection. Never manufactures business records or recovery proof. */
@Component
public class HistoryEvidenceReader {

    public static final String EVIDENCE_SHA256 =
        "b57cac027022e3c3b009b6daf20eef22c9146d2c7f32dd5c579841fe6eb84f33";
    public static final String REPORT_SHA256_LF =
        "b776e3b54a8ba6b6ec065fe6c2cb15784f6577c68eab6a1d0058454e7bc8a2a0";
    public static final String ARCHIVE_COMMIT =
        "c98cd599891dae7ffd23495e65fddfa9fe957918";
    private static final int MAX_BYTES = 64 * 1024;
    private static final String REPORT_CLAIM =
        "- CPU10%, cpu-count1, timeout10s; no escalation or other fault type.";
    private final Supplier<byte[]> evidenceBytes;
    private final Supplier<byte[]> reportBytes;
    private final JsonMapper json = JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .build();

    public record Source(
        String path,
        String sha256,
        String checksumMode,
        String archiveCommit,
        String url
    ) {}

    public record Fact(
        String key,
        String label,
        JsonNode value,
        String pointer
    ) {}

    public record CpuSample(
        String phase,
        Fact percent,
        String observedAt,
        String timePointer
    ) {}

    public record Event(String label, String observedAt, String pointer) {}

    public record RecoveryRound(List<Fact> facts, String pointer) {}

    public record Audit(
        String operation,
        String result,
        String occurredAtRaw,
        String pointer,
        List<Fact> parameters
    ) {}

    public record ReportClaim(String label, String quote, String section) {}

    public record History(
        int schemaVersion,
        String dataMode,
        Source evidenceSource,
        Source reportSource,
        List<Fact> facts,
        List<CpuSample> cpu,
        List<Event> timeline,
        List<RecoveryRound> recoveryRounds,
        List<Audit> audits,
        ReportClaim cpuCountReportClaim
    ) {}

    @Autowired
    public HistoryEvidenceReader() {
        this(
            () -> resource("m1-core-real-20261010.json"),
            () -> resource("12-m1-core-field-result.md")
        );
    }

    // Package-only fault injection, not caller-selectable executable/file paths.
    HistoryEvidenceReader(
        Supplier<byte[]> evidenceBytes,
        Supplier<byte[]> reportBytes
    ) {
        this.evidenceBytes = evidenceBytes;
        this.reportBytes = reportBytes;
    }

    private static byte[] resource(String name) {
        try (
            var stream = HistoryEvidenceReader.class.getResourceAsStream(
                "/history/" + name
            )
        ) {
            if (stream == null) throw unavailable();
            byte[] bytes = stream.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw unavailable();
            return bytes;
        } catch (IOException failure) {
            throw unavailable();
        }
    }

    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(
            HttpStatus.SERVICE_UNAVAILABLE,
            "HISTORY_EVIDENCE_UNAVAILABLE"
        );
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes)
            );
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    public History read() {
        try {
            byte[] original = evidenceBytes.get(),
                reportOriginal = reportBytes.get();
            if (
                original == null ||
                reportOriginal == null ||
                original.length > MAX_BYTES ||
                reportOriginal.length > MAX_BYTES ||
                !EVIDENCE_SHA256.equals(sha256(original))
            ) throw unavailable();
            // Markdown follows Git text checkout rules. Its documented checksum is UTF8/LF;
            // the JSON checksum ALWAYS uses exact unmodified bytes.
            String report = new String(
                reportOriginal,
                StandardCharsets.UTF_8
            ).replace("\r\n", "\n");
            if (
                !REPORT_SHA256_LF.equals(
                    sha256(report.getBytes(StandardCharsets.UTF_8))
                ) ||
                !report.contains(REPORT_CLAIM)
            ) throw unavailable();
            return project(json.readTree(original));
        } catch (RuntimeException invalid) {
            throw unavailable();
        }
    }

    private Source source(String path, String sha, String mode) {
        return new Source(
            path,
            sha,
            mode,
            ARCHIVE_COMMIT,
            "https://github.com/8xbAdx8/ChaosLab/blob/" +
                ARCHIVE_COMMIT +
                "/" +
                path
        );
    }

    private Fact fact(JsonNode root, String key, String label, String pointer) {
        var value = root.at(pointer);
        // Public scalar projection only; never expose executable/root/cgroup paths or raw objects.
        if (
            value.isMissingNode() ||
            !(
                value.isNull() ||
                value.isTextual() ||
                value.isBoolean() ||
                value.isNumber()
            )
        ) value = json.nullNode();
        return new Fact(key, label, value, pointer);
    }

    private static String text(JsonNode root, String pointer) {
        var value = root.at(pointer);
        return value.isTextual() ? value.asText() : null;
    }

    private static String time(JsonNode root, String pointer) {
        String value = text(root, pointer);
        try {
            Instant.parse(value);
            return value;
        } catch (RuntimeException missing) {
            return null;
        }
    }

    // Package-only pure projection seam. Production can reach it ONLY after both hash checks.
    History project(JsonNode root) {
        var facts = new ArrayList<Fact>();
        String[][] fields = {
            { "experimentId", "Experiment ID", "/experimentId" },
            { "executionId", "Execution ID", "/executionId" },
            { "nativeUid", "Native UID", "/nativeUid" },
            { "experimentName", "业务实验名称", "/experimentName" },
            { "hypothesis", "业务实验假设", "/hypothesis" },
            { "codeCommit", "实验执行代码提交", "/mergeCommit" },
            { "containerName", "Sandbox", "/preflight/policy/containerName" },
            { "targetId", "Target ID", "/committedIntent/targetId" },
            {
                "containerId",
                "完整 Container ID",
                "/committedIntent/containerId",
            },
            { "imageId", "Image ID", "/committedIntent/imageId" },
            { "nodeId", "执行节点", "/committedIntent/nodeId" },
            { "stateId", "状态代次", "/committedIntent/stateId" },
            { "toolSha256", "Blade SHA256", "/committedIntent/toolSha256" },
            { "policyDigest", "Policy digest", "/preflight/policyDigest" },
            { "cpuPercent", "计划 CPU %", "/committedIntent/cpuPercent" },
            { "cpuCount", "CPU count（JSON）", "/committedIntent/cpuCount" },
            {
                "timeoutSeconds",
                "Timeout 秒",
                "/committedIntent/durationSeconds",
            },
            { "scenario", "故障类型", "/committedIntent/scenarioCode" },
            { "createRequests", "后端 create 请求次数", "/createApiAttempts" },
            {
                "destroyRequests",
                "后端主动 destroy 请求次数",
                "/destroyApiAttempts",
            },
            { "automaticRetry", "自动重试", "/automaticRetry" },
            { "commitProof", "UID 持久化证明（归档陈述）", "/commitProof" },
            { "intentStatus", "已提交 Intent 状态", "/committedIntent/status" },
            { "runningStatus", "Create 响应状态", "/createResponse/status" },
            {
                "nativeStatus",
                "Native status",
                "/nativeRecord/records/0/status",
            },
            {
                "nativeDestroyedAt",
                "Native 完成时间",
                "/nativeRecord/records/0/updateTime",
            },
            {
                "residual",
                "最终 Residual",
                "/productionPhysicalAssessment/residual",
            },
            { "health", "最终 Health", "/productionPhysicalAssessment/health" },
            { "gate", "Recovery Gate", "/recoveryGate" },
            { "physicalRecovery", "Physical recovery", "/physicalRecovery" },
            { "backendStatus", "后端终态", "/finalBackendExecution/status" },
            { "occupancyRetained", "占用仍保留", "/occupancyRetained" },
            { "recoveryCause", "Recovery cause", "/recoveryCause" },
            {
                "activeDestroyRequest",
                "主动恢复请求",
                "/productionPhysicalAssessment/activeDestroyRequest",
            },
            {
                "authorizationConsumed",
                "历史授权已消费",
                "/authorizationConsumed",
            },
            { "result", "M1 Core 验收结果", "/result" },
            { "requestP95", "应用 P95", "/requestP95" },
            { "requestErrorRate", "应用错误率", "/requestErrorRate" },
        };
        for (var field : fields)
            facts.add(fact(root, field[0], field[1], field[2]));
        var cpu = List.of(
            sample(
                root,
                "baseline",
                "/baselineCpuPercent",
                "/preflight/observation/observedAt"
            ),
            sample(
                root,
                "during",
                "/productionCpuEvidence/cpuPercent",
                "/productionCpuEvidence/observedAt"
            ),
            sample(
                root,
                "after",
                "/afterObservation/observation/cpuPercent",
                "/afterObservation/observation/observedAt"
            )
        );
        var events = new ArrayList<Event>();
        String[][] eventFields = {
            { "现场预检观察", "/preflight/observation/observedAt" },
            {
                "唯一创建请求提交（不是 Native CreateTime）",
                "/startSubmittedAt",
            },
            {
                "执行记录创建（并非 UID 提交的精确时间）",
                "/createResponse/createdAt",
            },
            { "历史一次性授权写入", "/authorizationCreatedAt" },
            { "生产 CPU 故障证据采样", "/productionCpuEvidence/observedAt" },
            { "后端 RUNNING", "/createResponse/startedAt" },
            { "主动 destroy 请求（同 UID）", "/activeDestroyRequestedAt" },
            { "恢复观察回合 1", "/recoveryRounds/0/observedAt" },
            {
                "最终回合的新鲜 Native Destroyed 状态观察",
                "/productionPhysicalAssessment/engineObservedAt",
            },
            { "恢复观察回合 2", "/recoveryRounds/1/observedAt" },
            { "后端终态事务完成", "/finalBackendExecution/finishedAt" },
            {
                "后续只读 After CPU 观察",
                "/afterObservation/observation/observedAt",
            },
        };
        for (var field : eventFields)
            events.add(new Event(field[0], time(root, field[1]), field[1]));
        var rounds = new ArrayList<RecoveryRound>();
        for (int i = 0; i < root.path("recoveryRounds").size(); i++) {
            String pointer = "/recoveryRounds/" + i;
            var values = new ArrayList<Fact>();
            for (String key : List.of(
                "round",
                "executionId",
                "nativeStatus",
                "residual",
                "health",
                "gateOutcome",
                "observedAt",
                "activeDestroyRequest",
                "recoveryCause",
                "successEligible"
            ))
                values.add(fact(root, key, key, pointer + "/" + key));
            rounds.add(new RecoveryRound(values, pointer));
        }
        var audits = new ArrayList<Audit>();
        Set<String> publicKeys = Set.of(
            "percent",
            "version",
            "cpuPercent",
            "baselinePercent",
            "usageUsec",
            "nodeId",
            "stateId",
            "containerId",
            "imageId",
            "nativeUid",
            "toolSha256",
            "residual",
            "health",
            "physicalRecovery",
            "nativeStatus",
            "recoveryCause",
            "activeDestroyRequest",
            "observedAt",
            "engineObservedAt",
            "recoveryAttemptStartedAt"
        );
        for (int i = 0; i < root.path("productionAudits").size(); i++) {
            String pointer = "/productionAudits/" + i;
            var values = new ArrayList<Fact>();
            for (String key : root.at(pointer + "/parameters").propertyNames())
                if (publicKeys.contains(key)) values.add(
                    fact(root, key, key, pointer + "/parameters/" + key)
                );
            audits.add(
                new Audit(
                    text(root, pointer + "/operation"),
                    text(root, pointer + "/result"),
                    text(root, pointer + "/occurredAt"),
                    pointer,
                    values
                )
            );
        }
        return new History(
            1,
            "ARCHIVED_REAL_COMPLETED",
            source(
                "docs/evidence/m1-core-real-20261010.json",
                EVIDENCE_SHA256,
                "EXACT_BYTES_SHA256"
            ),
            source(
                "docs/12-m1-core-field-result.md",
                REPORT_SHA256_LF,
                "UTF8_LF_NORMALIZED_SHA256"
            ),
            List.copyOf(facts),
            cpu,
            List.copyOf(events),
            List.copyOf(rounds),
            List.copyOf(audits),
            new ReportClaim(
                "1 核（仅验收报告记载，JSON 未单列）",
                REPORT_CLAIM,
                "Scope and preservation"
            )
        );
    }

    private CpuSample sample(
        JsonNode root,
        String phase,
        String pointer,
        String timePointer
    ) {
        return new CpuSample(
            phase,
            fact(root, phase + "CpuPercent", phase + " CPU %", pointer),
            time(root, timePointer),
            timePointer
        );
    }
}
