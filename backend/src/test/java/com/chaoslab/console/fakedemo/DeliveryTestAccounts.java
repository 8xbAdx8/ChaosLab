package com.chaoslab.console.fakedemo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.UUID;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import java.nio.charset.StandardCharsets;
import tools.jackson.databind.json.JsonMapper;

/** CI/local acceptance only. Never packaged; no production default credentials. */
public final class DeliveryTestAccounts {

    private DeliveryTestAccounts() {}

    public static void main(String[] args) {
        try {
            setup(args);
        } catch (Exception rejected) {
            // Parser exceptions can include raw input. Never print that diagnostic.
            System.err.println("Test account setup rejected; no existing account was overwritten.");
            System.exit(1);
        }
    }

    private static void setup(String[] args) throws Exception {
        boolean local = args.length == 1 && args[0].equals("--stdin-local");
        if (args.length != 0 && !local) throw new IllegalArgumentException(
            "No arguments, or test-only --stdin-local"
        );
        Path directory = Path.of("../.local/dev").toAbsolutePath().normalize();
        Files.createDirectories(directory);
        if (Files.getFileStore(directory).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(
                directory,
                PosixFilePermissions.fromString("rwx------")
            );
        }
        // Explicit local acceptance only: a bounded pipe, never secret argv/env
        // or a plaintext credentials file. No production account default.
        String password = UUID.randomUUID().toString(), username = "demo.admin", viewer = "demo.viewer";
        if (local) {
            byte[] input = System.in.readNBytes(4097);
            if (input.length > 4096) throw new IllegalArgumentException("Invalid local setup input");
            try {
                var request = JsonMapper.builder().build().readTree(input);
                if (!request.isObject() || request.size() != 2 || !request.has("username") || !request.has("password") ||
                    !request.get("username").isTextual() || !request.get("password").isTextual())
                    throw new IllegalArgumentException("Invalid local setup input");
                username = request.get("username").asString();
                viewer = username + ".viewer";
                password = request.get("password").asString();
                if (!username.matches("[A-Za-z0-9._-]{1,50}") || password.length() < 12 ||
                    password.getBytes(StandardCharsets.UTF_8).length > 72)
                    throw new IllegalArgumentException("Invalid local setup input");
            } finally {
                java.util.Arrays.fill(input, (byte)0);
            }
        }
        if (Files.exists(directory.resolve("accounts.json")) || Files.exists(directory.resolve("smoke-credentials.json")))
            throw new IllegalStateException("Existing local credentials preserved; refusing setup");
        String hash = new BCryptPasswordEncoder(12).encode(password);
        write(
            directory.resolve("accounts.json"),
            "{\"admin\":{\"username\":\"" + username + "\",\"hash\":\"" +
                hash +
                "\"},\"viewer\":{\"username\":\"" + viewer + "\",\"hash\":\"" +
                hash +
                "\"}}"
        );
        if (!local) write(
            directory.resolve("smoke-credentials.json"),
            "{\"password\":\"" + password + "\"}"
        );
        System.out.println(
            "Test-only accounts saved without overwrite; no credentials printed."
        );
    }

    private static void write(Path file, String value) throws Exception {
        // Never overwrite someone's local accounts or database.
        Files.writeString(
            file,
            value,
            java.nio.file.StandardOpenOption.CREATE_NEW
        );
        if (Files.getFileStore(file).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(
                file,
                PosixFilePermissions.fromString("rw-------")
            );
        }
    }
}
