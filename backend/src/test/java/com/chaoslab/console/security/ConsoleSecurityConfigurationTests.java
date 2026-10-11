package com.chaoslab.console.security;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class ConsoleSecurityConfigurationTests {

    private final ConsoleSecurityConfiguration config =
        new ConsoleSecurityConfiguration();

    @Test
    void noDefaultAccountAndPartialPlaintextOrCaseCollisionConfigFailsClosed() {
        assertThat(
            config.consoleUsers(new MockEnvironment()).userExists("user")
        ).isFalse();
        for (var env : java.util.List.of(
            new MockEnvironment().withProperty(
                "chaoslab.security.viewer.username",
                "viewer"
            ),
            new MockEnvironment()
                .withProperty("chaoslab.security.viewer.username", "viewer")
                .withProperty(
                    "chaoslab.security.viewer.password-hash",
                    "plaintext-is-rejected"
                ),
            new MockEnvironment()
                .withProperty("chaoslab.security.viewer.username", "same")
                .withProperty(
                    "chaoslab.security.viewer.password-hash",
                    ConsoleAuthenticationTests.HASH
                )
                .withProperty("chaoslab.security.admin.username", "SAME")
                .withProperty(
                    "chaoslab.security.admin.password-hash",
                    ConsoleAuthenticationTests.HASH
                )
        ))
            assertThatThrownBy(() -> config.consoleUsers(env))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("CONSOLE_CREDENTIAL_CONFIGURATION_INVALID");
    }

    @Test
    void insecureSessionCookieRequiresExplicitLoopbackAndHistoryHasNoLoginAccounts() {
        var insecure = new MockEnvironment()
            .withProperty("server.servlet.session.cookie.secure", "false")
            .withProperty("server.address", "0.0.0.0");
        assertThatThrownBy(() -> config.consoleUsers(insecure)).hasMessage(
            "INSECURE_SESSION_REQUIRES_LOOPBACK"
        );
        assertThatCode(() ->
            config.consoleUsers(
                insecure.withProperty("server.address", "127.0.0.1")
            )
        ).doesNotThrowAnyException();
        var history = new MockEnvironment().withProperty(
            "chaoslab.security.admin.username",
            "ignored"
        );
        history.setActiveProfiles("history-demo");
        assertThat(
            config.consoleUsers(history).userExists("ignored")
        ).isFalse();
    }
}
