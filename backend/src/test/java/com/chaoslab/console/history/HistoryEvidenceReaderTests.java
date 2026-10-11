package com.chaoslab.console.history;

import static org.assertj.core.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class HistoryEvidenceReaderTests {

    private final JsonMapper json = JsonMapper.builder().build();

    private byte[] resource(String name) throws Exception {
        try (var in = getClass().getResourceAsStream("/history/" + name)) {
            return in.readAllBytes();
        }
    }

    private HistoryEvidenceReader reader() {
        return new HistoryEvidenceReader();
    }

    private HistoryEvidenceReader.Fact fact(
        HistoryEvidenceReader.History h,
        String key
    ) {
        return h
            .facts()
            .stream()
            .filter(f -> f.key().equals(key))
            .findFirst()
            .orElseThrow();
    }

    private void matches(JsonNode original, HistoryEvidenceReader.Fact f) {
        JsonNode value = original.at(f.pointer());
        assertThat(f.value()).isEqualTo(
            value.isMissingNode() ? json.nullNode() : value
        );
    }

    @Test
    void exactOriginalBytesArePackagedAndReadOnlyProjectionIsRepeatable()
        throws Exception {
        byte[] original = Files.readAllBytes(
            Path.of("../docs/evidence/m1-core-real-20261010.json")
        );
        assertThat(resource("m1-core-real-20261010.json")).isEqualTo(original);
        assertThat(
            HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(original)
            )
        ).isEqualTo(HistoryEvidenceReader.EVIDENCE_SHA256);
        var first = reader().read();
        assertThat(reader().read()).isEqualTo(first);
        assertThat(
            Files.readAllBytes(
                Path.of("../docs/evidence/m1-core-real-20261010.json")
            )
        ).isEqualTo(original);
        assertThat(first.dataMode()).isEqualTo("ARCHIVED_REAL_COMPLETED");
        assertThat(first.evidenceSource().archiveCommit()).isEqualTo(
            HistoryEvidenceReader.ARCHIVE_COMMIT
        );
    }

    @Test
    void everyScalarAndTimeHasAnExactOriginalPointer() throws Exception {
        var original = json.readTree(resource("m1-core-real-20261010.json"));
        var h = reader().read();
        h.facts().forEach(f -> matches(original, f));
        h.cpu().forEach(s -> {
            matches(original, s.percent());
            assertThat(s.observedAt()).isEqualTo(
                original.at(s.timePointer()).asText()
            );
        });
        h.timeline().forEach(e ->
            assertThat(e.observedAt()).isEqualTo(
                original.at(e.pointer()).asText()
            )
        );
        h.recoveryRounds().forEach(r ->
            r.facts().forEach(f -> matches(original, f))
        );
        h.audits().forEach(a -> {
            assertThat(a.operation()).isEqualTo(
                original.at(a.pointer() + "/operation").asText()
            );
            assertThat(a.result()).isEqualTo(
                original.at(a.pointer() + "/result").asText()
            );
            assertThat(a.occurredAtRaw()).isEqualTo(
                original.at(a.pointer() + "/occurredAt").asText()
            );
            a.parameters().forEach(f -> matches(original, f));
        });
        assertThat(h.cpu()).hasSize(3);
        assertThat(h.cpu().get(1).percent().value().asDouble()).isEqualTo(
            10.911216516130064
        );
        assertThat(h.recoveryRounds()).hasSize(2);
        assertThat(h.audits()).hasSize(4);
    }

    @Test
    void missingFieldsAreNullAndNeverInferredFromSuccess() throws Exception {
        var h = reader().read();
        for (String key : new String[] {
            "experimentName",
            "hypothesis",
            "cpuCount",
            "nativeDestroyedAt",
            "requestP95",
            "requestErrorRate",
        })
            assertThat(fact(h, key).value().isNull()).as(key).isTrue();
        assertThat(fact(h, "backendStatus").value().asText()).isEqualTo(
            "SUCCESS"
        );
        assertThat(fact(h, "recoveryCause").value().asText()).isEqualTo(
            "UNKNOWN"
        );
        assertThat(h.cpuCountReportClaim().quote()).contains("cpu-count1");
        var minimal = reader().project(
            json.readTree(
                "{\"finalBackendExecution\":{\"status\":\"SUCCESS\"}}"
            )
        );
        for (String key : new String[] {
            "physicalRecovery",
            "residual",
            "health",
            "occupancyRetained",
            "recoveryCause",
        })
            assertThat(fact(minimal, key).value().isNull()).isTrue();
        assertThat(minimal.cpu()).allSatisfy(s ->
            assertThat(s.percent().value().isNull()).isTrue()
        );
        assertThat(minimal.audits()).isEmpty();
        assertThat(minimal.recoveryRounds()).isEmpty();
        assertThat(minimal.timeline()).allSatisfy(e ->
            assertThat(e.observedAt()).isNull()
        );
    }

    @Test
    void publicProjectionOmitsPrivilegedRawFieldsAndFabricatedAuditIds() {
        String serialized = json.writeValueAsString(reader().read());
        assertThat(serialized).doesNotContain(
            "/opt/",
            "/var/lib/",
            "/home/w/",
            "stderr",
            "password",
            "executablePath",
            "sourceIp"
        );
        assertThat(reader().read().audits()).allSatisfy(a ->
            assertThat(a.parameters()).allSatisfy(f ->
                assertThat(f.key()).isNotIn(
                    "stderr",
                    "executable",
                    "token",
                    "password"
                )
            )
        );
    }

    @Test
    void changedMissingOversizedAndMalformedEvidenceFailClosed()
        throws Exception {
        byte[] bytes = resource("m1-core-real-20261010.json"),
            report = resource("12-m1-core-field-result.md");
        for (byte[] invalid : new byte[][] {
            null,
            new byte[65537],
            "{}".getBytes(StandardCharsets.UTF_8),
            (new String(bytes, StandardCharsets.UTF_8) + " {}").getBytes(
                StandardCharsets.UTF_8
            ),
            new String(bytes, StandardCharsets.UTF_8)
                .replace("10.911216516130064", "99")
                .getBytes(StandardCharsets.UTF_8),
        }) {
            assertThatThrownBy(() ->
                new HistoryEvidenceReader(() -> invalid, () -> report).read()
            ).isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                assertThat(e.getStatusCode().value()).isEqualTo(503);
                assertThat(e.getReason()).isEqualTo(
                    "HISTORY_EVIDENCE_UNAVAILABLE"
                );
            });
        }
        assertThatThrownBy(() ->
            new HistoryEvidenceReader(
                () -> bytes,
                () -> "changed report".getBytes(StandardCharsets.UTF_8)
            ).read()
        ).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void reportUsesExplicitLfChecksumNotPlatformCheckoutBytes()
        throws Exception {
        byte[] bytes = resource("m1-core-real-20261010.json"),
            report = resource("12-m1-core-field-result.md");
        String lf = new String(report, StandardCharsets.UTF_8).replace(
            "\r\n",
            "\n"
        );
        assertThat(
            new HistoryEvidenceReader(
                () -> bytes,
                () -> lf.getBytes(StandardCharsets.UTF_8)
            ).read()
        ).isEqualTo(
            new HistoryEvidenceReader(
                () -> bytes,
                () -> lf.replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8)
            ).read()
        );
    }
}
