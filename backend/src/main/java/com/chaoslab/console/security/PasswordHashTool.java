package com.chaoslab.console.security;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.function.Consumer;
import java.util.function.Function;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** Local console only; never accepts/logs passwords via argv, environment or HTTP. */
public final class PasswordHashTool {

    private PasswordHashTool() {}

    public static void main(String[] args) {
        var console = System.console();
        if (
            args.length != 0 || console == null
        ) throw new IllegalStateException(
            "Run in an interactive terminal without arguments"
        );
        try {
            console.printf("%s%n", readHash(
                prompt -> console.readPassword("%s", prompt),
                message -> console.printf("%s%n", message)
            ));
        } catch (IllegalArgumentException failure) {
            // Only fixed messages, never input, stack traces or partial hashes.
            console.printf("%s%n", failure.getMessage());
            System.exit(1);
        }
    }

    // Small test seam for hidden input validation; not an HTTP or stdin-pipe API.
    static String readHash(Function<String, char[]> read, Consumer<String> notice) {
        for (int attempt = 1; attempt <= 3; attempt++) {
            char[] first = null, second = null;
            try {
                first = read.apply("Password (not echoed): ");
                if (first == null) throw new IllegalArgumentException("Password input cancelled; no account saved.");
                second = read.apply("Confirm password: ");
                if (second == null) throw new IllegalArgumentException("Password input cancelled; no account saved.");
                if (first.length < 12 || new String(first).getBytes(StandardCharsets.UTF_8).length > 72) {
                    notice.accept("Use >=12 characters and <=72 UTF8 bytes. No account saved.");
                } else if (!Arrays.equals(first, second)) {
                    notice.accept("Passwords do not match. No account saved.");
                } else {
                    return new BCryptPasswordEncoder(12).encode(new String(first));
                }
                if (attempt < 3) notice.accept("Try again here; no rebuild is needed.");
            } finally {
                if (first != null) Arrays.fill(first, '\0');
                if (second != null) Arrays.fill(second, '\0');
            }
        }
        throw new IllegalArgumentException("Password setup stopped after 3 attempts; no account saved.");
    }
}
