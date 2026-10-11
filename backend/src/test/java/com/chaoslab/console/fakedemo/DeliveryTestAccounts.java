package com.chaoslab.console.fakedemo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.UUID;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** CI/local acceptance only. Never packaged; no production default credentials. */
public final class DeliveryTestAccounts {

    private DeliveryTestAccounts() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 0) throw new IllegalArgumentException(
            "No arguments"
        );
        Path directory = Path.of("../.local/dev").toAbsolutePath().normalize();
        Files.createDirectories(directory);
        if (Files.getFileStore(directory).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(
                directory,
                PosixFilePermissions.fromString("rwx------")
            );
        }
        String password = UUID.randomUUID().toString();
        String hash = new BCryptPasswordEncoder(12).encode(password);
        write(
            directory.resolve("accounts.json"),
            "{\"admin\":{\"username\":\"demo.admin\",\"hash\":\"" +
                hash +
                "\"},\"viewer\":{\"username\":\"demo.viewer\",\"hash\":\"" +
                hash +
                "\"}}"
        );
        write(
            directory.resolve("smoke-credentials.json"),
            "{\"password\":\"" + password + "\"}"
        );
        System.out.println(
            "Random test-only accounts written privately; no credentials printed."
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
