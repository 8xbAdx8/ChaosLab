package com.chaoslab.console.security;

import static org.assertj.core.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class PasswordHashToolTests {
    @Test
    void matchingInputIsHashedAndBothArraysAreCleared() {
        String password = UUID.randomUUID().toString();
        char[] first = password.toCharArray(), second = password.toCharArray();
        var reads = new ArrayDeque<>(List.of(first, second));
        var notices = new ArrayList<String>();
        String hash = PasswordHashTool.readHash(prompt -> reads.remove(), notices::add);
        assertThat(new BCryptPasswordEncoder().matches(password, hash)).isTrue();
        assertThat(hash).startsWith("$2a$12$");
        assertThat(first).containsOnly('\0');
        assertThat(second).containsOnly('\0');
        assertThat(notices).isEmpty();
    }

    @Test
    void mismatchCanRetryWithoutARebuildOrDisclosingInput() {
        String password = UUID.randomUUID().toString();
        var arrays = List.of(password.toCharArray(), "different-long-input".toCharArray(), password.toCharArray(), password.toCharArray());
        var reads = new ArrayDeque<>(arrays);
        var notices = new ArrayList<String>();
        String hash = PasswordHashTool.readHash(prompt -> reads.remove(), notices::add);
        assertThat(new BCryptPasswordEncoder().matches(password, hash)).isTrue();
        assertThat(notices).contains("Passwords do not match. No account saved.", "Try again here; no rebuild is needed.");
        assertThat(String.join(" ", notices)).doesNotContain(password, hash, "different-long-input");
        arrays.forEach(array -> assertThat(array).containsOnly('\0'));
    }

    @Test
    void lengthAndUtf8ByteLimitsRemainFailClosedAndRetriesAreBounded() {
        for (String password : List.of("short", "a".repeat(73), "密".repeat(25))) {
            var arrays = new ArrayList<char[]>();
            for (int i = 0; i < 6; i++) arrays.add(password.toCharArray());
            var reads = new ArrayDeque<>(arrays);
            var notices = new ArrayList<String>();
            assertThatThrownBy(() -> PasswordHashTool.readHash(prompt -> reads.remove(), notices::add))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("3 attempts");
            assertThat(reads).isEmpty();
            assertThat(notices).hasSize(5);
            assertThat(String.join(" ", notices)).doesNotContain(password, "$2a$");
            arrays.forEach(array -> assertThat(array).containsOnly('\0'));
        }
    }

    @Test
    void eofCancelsWithoutHashOrPartialConfiguration() {
        char[] first = UUID.randomUUID().toString().toCharArray();
        int[] calls = {0};
        assertThatThrownBy(() -> PasswordHashTool.readHash(prompt -> calls[0]++ == 0 ? first : null, message -> fail("No diagnostic input leakage")))
            .hasMessage("Password input cancelled; no account saved.");
        assertThat(first).containsOnly('\0');
        assertThat(calls[0]).isEqualTo(2);
    }
}
