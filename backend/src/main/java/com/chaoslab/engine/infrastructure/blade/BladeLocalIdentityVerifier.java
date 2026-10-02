package com.chaoslab.engine.infrastructure.blade;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;

/** Read-only local probe, not a Spring bean or execution permission. Paths come from trusted deployment. */
public final class BladeLocalIdentityVerifier {
    public static final String STATE_MARKER = ".chaoslab-state-id";
    private static final long MAX_BINARY_BYTES = 64L * 1024 * 1024;
    private final Path nodeMarker;
    private final Path stateDirectory;
    private final Path binary;
    private final String pinnedVersion;
    private final String pinnedSha256;

    public enum Result {
        LOCAL_IDENTITY_MATCHED_NEEDS_TARGET_CHECK,
        MISSING_RECOVERY_EVIDENCE, NODE_MISMATCH, STATE_DIRECTORY_MISMATCH,
        TOOL_PIN_MISMATCH, TOOL_CONTENT_MISMATCH, LOCAL_EVIDENCE_UNAVAILABLE
    }

    /** Version is a deployment-reviewed label bound to a pinned digest, not CLI version output. */
    public BladeLocalIdentityVerifier(Path nodeMarker, Path stateDirectory, Path binary,
                                      String pinnedVersion, String pinnedSha256) {
        this.nodeMarker = absolute(nodeMarker);
        this.stateDirectory = absolute(stateDirectory);
        this.binary = absolute(binary);
        if (pinnedVersion == null || !pinnedVersion.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}")
                || pinnedSha256 == null || !pinnedSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("invalid deployment tool pin");
        }
        this.pinnedVersion = pinnedVersion;
        this.pinnedSha256 = pinnedSha256;
    }

    public Result verify(BladeExecutionSnapshot saved) {
        if (saved == null || saved.uid() == null) return Result.MISSING_RECOVERY_EVIDENCE;
        if (!pinnedVersion.equals(saved.toolVersion()) || !pinnedSha256.equals(saved.toolSha256())) {
            return Result.TOOL_PIN_MISMATCH;
        }
        try {
            if (!saved.executorInstanceId().equals(marker(nodeMarker))) return Result.NODE_MISMATCH;
            rejectLinks(stateDirectory);
            if (!Files.isDirectory(stateDirectory, LinkOption.NOFOLLOW_LINKS)) throw new IOException();
            if (!saved.stateDirectoryId().equals(marker(stateDirectory.resolve(STATE_MARKER)))) {
                return Result.STATE_DIRECTORY_MISMATCH;
            }
            String observed = digest(binary);
            if (!pinnedSha256.equals(observed)) return Result.TOOL_CONTENT_MISMATCH;
            return Result.LOCAL_IDENTITY_MATCHED_NEEDS_TARGET_CHECK;
        } catch (IOException | SecurityException invalid) {
            // Never leak paths, file content, or underlying exception messages to an API/log.
            return Result.LOCAL_EVIDENCE_UNAVAILABLE;
        }
    }

    private static Path absolute(Path path) {
        if (path == null || !path.isAbsolute() || !path.equals(path.normalize())) {
            throw new IllegalArgumentException("deployment paths must be absolute and normalized");
        }
        return path;
    }

    private static void rejectLinks(Path path) throws IOException {
        for (Path item = path; item != null; item = item.getParent()) {
            if (Files.isSymbolicLink(item)) throw new IOException();
        }
        // Also rejects path redirection such as junctions when the provider resolves them.
        if (!path.toRealPath().equals(path)) throw new IOException();
    }

    private static BasicFileAttributes regular(Path path, long maximum) throws IOException {
        rejectLinks(path);
        var attrs = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attrs.isRegularFile() || attrs.size() < 1 || attrs.size() > maximum) throw new IOException();
        return attrs;
    }

    private static SeekableByteChannel open(Path path) throws IOException {
        return Files.newByteChannel(path, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
    }

    private static String marker(Path path) throws IOException {
        var before = regular(path, 66);
        ByteBuffer buffer = ByteBuffer.allocate(67);
        try (var channel = open(path)) {
            while (buffer.hasRemaining() && channel.read(buffer) != -1) { /* bounded */ }
        }
        unchanged(path, before, 66);
        if (buffer.position() != before.size()) throw new IOException();
        String value = new String(buffer.array(), 0, buffer.position(), StandardCharsets.US_ASCII);
        if (value.endsWith("\r\n")) value = value.substring(0, value.length() - 2);
        else if (value.endsWith("\n")) value = value.substring(0, value.length() - 1);
        if (!value.matches("[a-z0-9][a-z0-9-]{0,63}")) throw new IOException();
        return value;
    }

    private static String digest(Path path) throws IOException {
        var before = regular(path, MAX_BINARY_BYTES);
        MessageDigest hash;
        try { hash = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        ByteBuffer buffer = ByteBuffer.allocate(8192);
        long count = 0;
        try (var channel = open(path)) {
            int read;
            while ((read = channel.read(buffer)) != -1) {
                count += read;
                if (count > MAX_BINARY_BYTES) throw new IOException();
                buffer.flip();
                hash.update(buffer);
                buffer.clear();
            }
        }
        unchanged(path, before, MAX_BINARY_BYTES);
        if (count != before.size()) throw new IOException();
        return HexFormat.of().formatHex(hash.digest());
    }

    private static void unchanged(Path path, BasicFileAttributes before, long maximum) throws IOException {
        var after = regular(path, maximum);
        if (!java.util.Objects.equals(before.fileKey(), after.fileKey()) || before.size() != after.size()
                || !before.lastModifiedTime().equals(after.lastModifiedTime())) throw new IOException();
    }
}
