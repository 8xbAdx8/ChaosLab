package com.chaoslab.console.fakedemo;

import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.boot.SpringApplication;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** Local UI smoke test only; real production security and Fake services, isolated persistent test DB.
 * Random test credentials stay in ignored target files; this launcher is excluded from the JAR. */
public final class FakeDemoBrowserFixture {

    private FakeDemoBrowserFixture() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 0) throw new IllegalArgumentException(
            "No caller paths or DB options"
        );
        Files.writeString(
            Path.of("target/m4-browser-test-password"),
            FakeDemoApiTests.PASSWORD
        );
        Files.writeString(
            Path.of("target/m4-browser-test-admin"),
            FakeDemoApiTests.ADMIN
        );
        Files.writeString(
            Path.of("target/m4-browser-test-viewer"),
            FakeDemoApiTests.VIEWER
        );
        String dir = Path.of("target/m4-browser-proof/chaoslab-fake-demo")
            .toAbsolutePath()
            .toString()
            .replace('\\', '/');
        String hash = new BCryptPasswordEncoder(10).encode(
            FakeDemoApiTests.PASSWORD
        );
        new SpringApplication(com.chaoslab.ChaosLabApplication.class).run(
            "--spring.profiles.active=fake-demo",
            "--server.port=18085",
            "--chaoslab.fake-demo.directory=" + dir,
            "--spring.datasource.url=jdbc:h2:file:" +
                dir +
                "/chaoslab_fake_demo;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE",
            "--chaoslab.security.admin.username=" + FakeDemoApiTests.ADMIN,
            "--chaoslab.security.admin.password-hash=" + hash,
            "--chaoslab.security.viewer.username=" + FakeDemoApiTests.VIEWER,
            "--chaoslab.security.viewer.password-hash=" + hash
        );
    }
}
