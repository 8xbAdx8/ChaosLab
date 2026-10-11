package com.chaoslab.console.security;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
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
        char[] first = console.readPassword("Password (not echoed): "),
            second = console.readPassword("Confirm password: ");
        try {
            if (
                first == null ||
                second == null ||
                first.length < 12 ||
                !Arrays.equals(first, second) ||
                new String(first).getBytes(StandardCharsets.UTF_8).length > 72
            ) throw new IllegalArgumentException(
                "Use matching passwords of >=12 characters and <=72 UTF8 bytes"
            );
            console.printf(
                "%s%n",
                new BCryptPasswordEncoder(12).encode(new String(first))
            );
        } finally {
            if (first != null) Arrays.fill(first, '\0');
            if (second != null) Arrays.fill(second, '\0');
        }
    }
}
