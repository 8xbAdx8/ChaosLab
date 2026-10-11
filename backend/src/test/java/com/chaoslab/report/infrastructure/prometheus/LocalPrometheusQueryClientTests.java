package com.chaoslab.report.infrastructure.prometheus;

import static org.assertj.core.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class LocalPrometheusQueryClientTests {

    private static final Instant AT = Instant.ofEpochSecond(1700000000);

    private LocalPrometheusQueryClient client(URI uri) {
        return new LocalPrometheusQueryClient(
            HttpClient.newHttpClient(),
            JsonMapper.builder().build(),
            uri
        );
    }

    private Double query(String body, int status) throws Exception {
        var server = HttpServer.create(
            new InetSocketAddress("127.0.0.1", 0),
            0
        );
        server.createContext("/api/v1/query", exchange -> {
            assertThat(exchange.getRequestMethod()).isEqualTo("GET");
            assertThat(exchange.getRequestURI().getQuery()).contains(
                "time=1.7E9"
            );
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            return client(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort())
            ).query("fixed metric", AT);
        } finally {
            server.stop(0);
        }
    }

    private String vector(String result) {
        return (
            "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":" +
            result +
            "}}"
        );
    }

    @Test
    void samplesMustMatchRequestedTimeAndSingleSeries() throws Exception {
        assertThat(
            query(vector("[{\"value\":[1700000000,\"0\"]}]"), 200)
        ).isZero();
        assertThat(query(vector("[]"), 200)).isNull();
        assertThat(
            query(vector("[{\"value\":[1700000000,\"NaN\"]}]"), 200)
        ).isNull();
        assertThatThrownBy(() ->
            query(vector("[{\"value\":[1699999990,\"1\"]}]"), 200)
        ).hasMessageContaining("time");
        assertThatThrownBy(() ->
            query(vector("[{},{}]"), 200)
        ).hasMessageContaining("single series");
        assertThatThrownBy(() ->
            query("x".repeat(65537), 200)
        ).hasMessageContaining("unavailable");
        assertThatThrownBy(() -> query(vector("[]"), 302)).hasMessageContaining(
            "invalid"
        );
    }

    @Test
    void trustedConfigCannotBecomeAnInternalUrlProxy() {
        for (String url : new String[] {
            "http://169.254.169.254:80",
            "https://127.0.0.1:123",
            "http://user:secret@localhost:80",
            "http://localhost:80/other",
            "http://localhost:80?url=x",
            "http://localhost:80#x",
        })
            assertThatThrownBy(() -> client(URI.create(url))).isInstanceOf(
                IllegalArgumentException.class
            );
        assertThatThrownBy(() ->
            client(URI.create("http://localhost:123")).query(
                "x".repeat(2049),
                AT
            )
        ).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void deadlineAlsoBoundsABodyThatNeverCompletes() throws Exception {
        var server = HttpServer.create(
            new InetSocketAddress("127.0.0.1", 0),
            0
        );
        var held = new java.util.concurrent.CountDownLatch(1);
        server.setExecutor(
            java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()
        );
        server.createContext("/api/v1/query", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write('{');
            exchange.getResponseBody().flush();
            try {
                held.await(5, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        server.start();
        long start = System.nanoTime();
        try {
            assertThatThrownBy(() ->
                client(
                    URI.create(
                        "http://127.0.0.1:" + server.getAddress().getPort()
                    )
                ).query("fixed", AT)
            ).hasMessageContaining("unavailable");
            assertThat((System.nanoTime() - start) / 1e9).isLessThan(4.5);
        } finally {
            held.countDown();
            server.stop(0);
        }
    }
}
