package com.chaoslab.console.security;

import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.boot.SpringApplication;

/** Developer smoke-test launcher only: actual session chain, empty private in-memory DB.
 * Random credentials are temporary test artifacts, never logs/configured production credentials.
 * Excluded from the production JAR; do not run against any persistent database. */
public final class ConsoleBrowserFixture {

    private ConsoleBrowserFixture() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 0) throw new IllegalArgumentException(
            "No caller paths or DB options"
        );
        Files.writeString(
            Path.of("target/m3-browser-test-password"),
            ConsoleAuthenticationTests.PASSWORD
        );
        Files.writeString(
            Path.of("target/m3-browser-test-username"),
            ConsoleAuthenticationTests.VIEWER
        );
        new SpringApplication(com.chaoslab.ChaosLabApplication.class).run(
            "--spring.profiles.active=auth-test",
            "--chaoslab.console.read-only=true",
            "--server.address=127.0.0.1",
            "--server.port=18083",
            "--server.servlet.session.cookie.name=CHAOSLAB_SESSION",
            "--server.servlet.session.cookie.http-only=true",
            "--server.servlet.session.cookie.same-site=lax",
            "--server.servlet.session.cookie.secure=false",
            "--server.servlet.session.tracking-modes=cookie",
            "--spring.datasource.url=jdbc:h2:mem:m3-browser-only;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
            "--chaoslab.security.viewer.username=" +
                ConsoleAuthenticationTests.VIEWER,
            "--chaoslab.security.viewer.password-hash=" +
                ConsoleAuthenticationTests.HASH,
            "--chaoslab.security.admin.username=" +
                ConsoleAuthenticationTests.ADMIN,
            "--chaoslab.security.admin.password-hash=" +
                ConsoleAuthenticationTests.HASH
        );
    }
}
