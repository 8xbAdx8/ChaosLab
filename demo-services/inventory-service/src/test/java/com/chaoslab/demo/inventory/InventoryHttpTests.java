package com.chaoslab.demo.inventory;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InventoryHttpTests {

    @Value("${local.server.port}")
    private int port;

    @Test
    void servesFixturesAndBoundsSlowEndpoint() throws IOException, InterruptedException {
        try (HttpClient client = HttpClient.newHttpClient()) {
            assertThat(status(client, "/inventory/item-1")).isEqualTo(200);
            assertThat(status(client, "/slow?delayMs=2001")).isEqualTo(400);
            assertThat(status(client, "/error")).isEqualTo(503);
        }
    }

    private int status(HttpClient client, String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }
}
