package com.chaoslab.console.fakedemo;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class FakeDemoSettingsTests {

    static MockEnvironment valid() {
        var e = new MockEnvironment();
        e.setActiveProfiles("fake-demo");
        return e
            .withProperty("chaoslab.fake-demo.mode", "FAKE_DEMO")
            .withProperty(
                "chaoslab.fake-demo.directory",
                "./target/settings/chaoslab-fake-demo"
            )
            .withProperty("chaoslab.engine", "fake")
            .withProperty("chaoslab.console.read-only", "false")
            .withProperty("server.address", "127.0.0.1")
            .withProperty(
                "spring.datasource.driver-class-name",
                "org.h2.Driver"
            )
            .withProperty(
                "spring.datasource.url",
                "jdbc:h2:file:./target/settings/chaoslab-fake-demo/chaoslab_fake_demo;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE"
            );
    }

    @Test
    void onlyExactLocalDemoSettingsAllowedBeforeDatabase() {
        assertThatCode(() ->
            FakeDemoConfiguration.validateSettings(valid())
        ).doesNotThrowAnyException();
        for (String[] entry : new String[][] {
            { "chaoslab.engine", "blade" },
            { "chaoslab.engine", "unknown" },
            {
                "spring.datasource.url",
                "jdbc:mysql://192.168.32.132/chaoslab_m1",
            },
            { "spring.flyway.url", "jdbc:mysql://127.0.0.1/chaoslab_m1_r2" },
            {
                "spring.datasource.hikari.jdbc-url",
                "jdbc:mysql://127.0.0.1/chaoslab_m1",
            },
            { "spring.datasource.jndi-name", "java:comp/env/jdbc/production" },
            {
                "spring.datasource.hikari.data-source-class-name",
                "com.mysql.cj.jdbc.MysqlDataSource",
            },
            { "server.address", "0.0.0.0" },
            { "chaoslab.console.read-only", "true" },
            { "chaoslab.demo.binding-script", "/tmp/script" },
            { "chaoslab.fake-demo.mode", "UNKNOWN" },
            { "chaoslab.fake-demo.directory", "/tmp/another-db" },
        }) {
            assertThatThrownBy(() ->
                FakeDemoConfiguration.validateSettings(
                    valid().withProperty(entry[0], entry[1])
                )
            ).hasMessage("FAKE_DEMO_CONFIGURATION_REJECTED");
        }
        var overlap = valid();
        overlap.setActiveProfiles("fake-demo", "console");
        assertThatThrownBy(() ->
            FakeDemoConfiguration.validateSettings(overlap)
        ).hasMessage("FAKE_DEMO_CONFIGURATION_REJECTED");
        assertThat(
            FakeDemoConfiguration.mutation("POST", "/api/v1/authorization")
        ).isFalse();
        assertThat(
            FakeDemoConfiguration.mutation("POST", "/api/v1/targets")
        ).isFalse();
        assertThat(
            FakeDemoConfiguration.mutation(
                "POST",
                "/api/v1/experiments/../root"
            )
        ).isFalse();
    }
}
