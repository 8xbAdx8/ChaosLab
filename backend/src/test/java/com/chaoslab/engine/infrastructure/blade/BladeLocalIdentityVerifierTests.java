package com.chaoslab.engine.infrastructure.blade;

import com.chaoslab.safety.application.model.VerifiedDockerTarget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

import static com.chaoslab.engine.infrastructure.blade.BladeLocalIdentityVerifier.Result.*;
import static org.assertj.core.api.Assertions.*;

class BladeLocalIdentityVerifierTests {
    @TempDir Path temporary;
    private Path node;
    private Path state;
    private Path binary;
    private String sha;
    private BladeExecutionSnapshot saved;

    @BeforeEach
    void fixture() throws Exception {
        Path root = temporary.toRealPath();
        node = Files.writeString(root.resolve("node-id"), "node-1\n");
        state = Files.createDirectory(root.resolve("state"));
        Files.writeString(state.resolve(BladeLocalIdentityVerifier.STATE_MARKER), "state-1\r\n");
        binary = Files.writeString(root.resolve("blade"), "inert test fixture; never execute");
        sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(binary)));
        Instant now = Instant.parse("2026-10-02T00:00:00Z");
        saved = new BladeExecutionSnapshot(UUID.randomUUID(), new VerifiedDockerTarget(UUID.randomUUID(),
                "a".repeat(64), "sha256:" + "b".repeat(64), "order-service"), "node-1", "state-1",
                "1.7.4", sha, 20, 30, now, now.plusSeconds(30), "0123456789abcdef");
    }

    private BladeLocalIdentityVerifier verifier() {
        return new BladeLocalIdentityVerifier(node, state, binary, "1.7.4", sha);
    }

    @Test
    void matchingFilesOnlyConfirmLocalEvidenceAndRemainUnchanged() throws Exception {
        var nodeBefore = Files.readAllBytes(node);
        var stateBefore = Files.readAllBytes(state.resolve(BladeLocalIdentityVerifier.STATE_MARKER));
        var toolBefore = Files.readAllBytes(binary);
        assertThat(verifier().verify(saved)).isEqualTo(LOCAL_IDENTITY_MATCHED_NEEDS_TARGET_CHECK);
        assertThat(new BladeLocalIdentityVerifier(node, state, binary, "1.7.4", sha).verify(saved))
                .isEqualTo(LOCAL_IDENTITY_MATCHED_NEEDS_TARGET_CHECK);
        assertThat(Files.readAllBytes(node)).isEqualTo(nodeBefore);
        assertThat(Files.readAllBytes(state.resolve(BladeLocalIdentityVerifier.STATE_MARKER))).isEqualTo(stateBefore);
        assertThat(Files.readAllBytes(binary)).isEqualTo(toolBefore);
    }

    @Test
    void missingUidNeverBecomesLocalApproval() {
        var missing = new BladeExecutionSnapshot(saved.executionId(), saved.target(), saved.executorInstanceId(),
                saved.stateDirectoryId(), saved.toolVersion(), saved.toolSha256(), saved.cpuPercent(),
                saved.durationSeconds(), saved.recordedAt(), saved.recoveryDeadline(), null);
        assertThat(verifier().verify(missing)).isEqualTo(MISSING_RECOVERY_EVIDENCE);
        assertThat(verifier().verify(null)).isEqualTo(MISSING_RECOVERY_EVIDENCE);
    }

    @Test
    void deploymentPinsMustMatchPersistedVersionAndDigest() {
        assertThat(new BladeLocalIdentityVerifier(node, state, binary, "other-version", sha).verify(saved))
                .isEqualTo(TOOL_PIN_MISMATCH);
        assertThat(new BladeLocalIdentityVerifier(node, state, binary, "1.7.4", "f".repeat(64)).verify(saved))
                .isEqualTo(TOOL_PIN_MISMATCH);
    }

    @Test
    void changedNodeAndStateAreRejected() throws Exception {
        Files.writeString(node, "node-2");
        assertThat(verifier().verify(saved)).isEqualTo(NODE_MISMATCH);
        Files.writeString(node, "node-1");
        Files.writeString(state.resolve(BladeLocalIdentityVerifier.STATE_MARKER), "state-2");
        assertThat(verifier().verify(saved)).isEqualTo(STATE_DIRECTORY_MISMATCH);
    }

    @Test
    void toolBytesAreRehashedOnEveryCall() throws Exception {
        var probe = verifier();
        assertThat(probe.verify(saved)).isEqualTo(LOCAL_IDENTITY_MATCHED_NEEDS_TARGET_CHECK);
        Files.writeString(binary, "replaced tool bytes");
        assertThat(probe.verify(saved)).isEqualTo(TOOL_CONTENT_MISMATCH);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "NODE-1", " node-1", "node-1\n\n", "node-1\u0000", "秘密", "x"})
    void malformedOrDifferentMarkersNeverMatch(String value) throws Exception {
        Files.writeString(node, value);
        assertThat(verifier().verify(saved)).isIn(LOCAL_EVIDENCE_UNAVAILABLE, NODE_MISMATCH);
    }

    @ParameterizedTest
    @ValueSource(strings = {"node", "state", "binary"})
    void missingLocalFilesFailClosedWithoutRecreatingThem(String which) throws Exception {
        Path missing = switch (which) {
            case "node" -> node;
            case "state" -> state.resolve(BladeLocalIdentityVerifier.STATE_MARKER);
            default -> binary;
        };
        Files.delete(missing);
        assertThat(verifier().verify(saved)).isEqualTo(LOCAL_EVIDENCE_UNAVAILABLE);
        assertThat(missing).doesNotExist();
    }

    @Test
    void directoriesAndOversizedFilesAreNotReadAsEvidence() throws Exception {
        Files.delete(binary);
        Files.createDirectory(binary);
        assertThat(verifier().verify(saved)).isEqualTo(LOCAL_EVIDENCE_UNAVAILABLE);
        Files.delete(binary);
        try (var channel = Files.newByteChannel(binary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            channel.position(128L * 1024 * 1024);
            channel.write(ByteBuffer.wrap(new byte[] {1}));
        }
        assertThat(verifier().verify(saved)).isEqualTo(LOCAL_EVIDENCE_UNAVAILABLE);
        Files.writeString(node, "x".repeat(67));
        assertThat(verifier().verify(saved)).isEqualTo(LOCAL_EVIDENCE_UNAVAILABLE);
    }

    @Test
    void deploymentCannotUseRelativePathsTraversalOrUnpinnedTools() {
        assertThatThrownBy(() -> new BladeLocalIdentityVerifier(Path.of("node"), state, binary, "1.7.4", sha))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BladeLocalIdentityVerifier(node, state.resolve(".."), binary, "1.7.4", sha))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BladeLocalIdentityVerifier(node, state, binary, "1.7.4", ""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void boundedLimitAcceptsCandidateSizedFile() throws Exception {
        try (var channel = Files.newByteChannel(binary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            channel.position(74921321L);
            channel.write(ByteBuffer.wrap(new byte[]{1}));
        }
        var digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(binary)) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = input.read(buffer)) != -1) digest.update(buffer, 0, n);
        }
        sha = HexFormat.of().formatHex(digest.digest());
        saved = new BladeExecutionSnapshot(saved.executionId(), saved.target(), saved.executorInstanceId(),
                saved.stateDirectoryId(), saved.toolVersion(), sha, saved.cpuPercent(), saved.durationSeconds(),
                saved.recordedAt(), saved.recoveryDeadline(), saved.uid());
        assertThat(verifier().verify(saved)).isEqualTo(LOCAL_IDENTITY_MATCHED_NEEDS_TARGET_CHECK);
    }

    @Test
    void symbolicLinkIsNotAcceptedAsToolEvidence() throws Exception {
        Path link = binary.resolveSibling("blade-link");
        try { Files.createSymbolicLink(link, binary); }
        catch (java.io.IOException | UnsupportedOperationException unavailable) {
            org.junit.jupiter.api.Assumptions.abort("symbolic link creation is unavailable on this host");
        }
        assertThat(new BladeLocalIdentityVerifier(node, state, link, "1.7.4", sha).verify(saved))
                .isEqualTo(LOCAL_EVIDENCE_UNAVAILABLE);
    }
}
