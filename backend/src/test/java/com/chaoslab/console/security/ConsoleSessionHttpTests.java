package com.chaoslab.console.security;

import static org.assertj.core.api.Assertions.*;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "chaoslab.console.read-only=true",
        "server.address=127.0.0.1",
        "server.servlet.session.cookie.name=CHAOSLAB_SESSION",
        "server.servlet.session.cookie.http-only=true",
        "server.servlet.session.cookie.same-site=lax",
        "server.servlet.session.cookie.secure=false",
        "server.servlet.session.tracking-modes=cookie",
        "server.servlet.session.timeout=30m",
        "spring.datasource.url=jdbc:h2:mem:auth-http;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
    }
)
@ActiveProfiles("auth-test")
class ConsoleSessionHttpTests {

    @LocalServerPort
    int port;

    @DynamicPropertySource
    static void accounts(DynamicPropertyRegistry r) {
        ConsoleAuthenticationTests.accounts(r);
    }

    private URI uri(String p) {
        return URI.create("http://127.0.0.1:" + port + p);
    }

    @Test
    void realCookieHttpOnlySameSiteLoginLogoutAndOldSessionReplay()
        throws Exception {
        var cookies = new CookieManager(
            null,
            CookiePolicy.ACCEPT_ORIGINAL_SERVER
        );
        var http = HttpClient.newBuilder()
            .cookieHandler(cookies)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
        var json = JsonMapper.builder().build();
        var before = http.send(
            HttpRequest.newBuilder(uri("/api/v1/auth/csrf")).GET().build(),
            HttpResponse.BodyHandlers.ofString()
        );
        String cookie = before.headers().firstValue("set-cookie").orElseThrow();
        assertThat(cookie)
            .contains("CHAOSLAB_SESSION=", "HttpOnly", "SameSite=Lax")
            .doesNotContain("Secure");
        String token = json.readTree(before.body()).path("token").asText();
        var login = http.send(
            HttpRequest.newBuilder(uri("/api/v1/auth/login"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("X-CSRF-TOKEN", token)
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        "username=" +
                            ConsoleAuthenticationTests.VIEWER +
                            "&password=" +
                            URLEncoder.encode(
                                ConsoleAuthenticationTests.PASSWORD,
                                StandardCharsets.UTF_8
                            )
                    )
                )
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );
        assertThat(login.statusCode()).isEqualTo(200);
        String activeCookie = cookies
            .getCookieStore()
            .getCookies()
            .stream()
            .filter(c ->
                c.getName().equals(ConsoleSecurityConfiguration.SESSION_COOKIE)
            )
            .findFirst()
            .orElseThrow()
            .getValue();
        assertThat(
            http
                .send(
                    HttpRequest.newBuilder(
                        uri("/api/v1/console/history/m1-core")
                    )
                        .GET()
                        .build(),
                    HttpResponse.BodyHandlers.discarding()
                )
                .statusCode()
        ).isEqualTo(200);
        var fresh = http.send(
            HttpRequest.newBuilder(uri("/api/v1/auth/csrf")).GET().build(),
            HttpResponse.BodyHandlers.ofString()
        );
        String renewed = json.readTree(fresh.body()).path("token").asText();
        var logout = http.send(
            HttpRequest.newBuilder(uri("/api/v1/auth/logout"))
                .header("X-CSRF-TOKEN", renewed)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.discarding()
        );
        assertThat(logout.statusCode()).isEqualTo(204);
        assertThat(
            http
                .send(
                    HttpRequest.newBuilder(
                        uri("/api/v1/console/history/m1-core")
                    )
                        .GET()
                        .build(),
                    HttpResponse.BodyHandlers.discarding()
                )
                .statusCode()
        ).isEqualTo(401);
        var replay = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(uri("/api/v1/console/history/m1-core"))
                .header(
                    "Cookie",
                    ConsoleSecurityConfiguration.SESSION_COOKIE +
                        "=" +
                        activeCookie
                )
                .GET()
                .build(),
            HttpResponse.BodyHandlers.discarding()
        );
        assertThat(replay.statusCode()).isEqualTo(401);
    }
}
