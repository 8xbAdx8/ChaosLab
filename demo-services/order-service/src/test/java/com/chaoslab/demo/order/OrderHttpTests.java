package com.chaoslab.demo.order;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "demo.inventory.base-url=http://127.0.0.1:9"
)
class OrderHttpTests {

    @Value("${local.server.port}")
    private int port;

    @Test
    void applicationErrorsDoNotDispatchIntoDemoErrorEndpoint() throws IOException, InterruptedException {
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + port + "/orders/missing"))
                    .build();
            assertThat(client.send(request, java.net.http.HttpResponse.BodyHandlers.discarding())
                    .statusCode()).isEqualTo(404);
            HttpRequest scrape = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + port + "/actuator/prometheus"))
                    .build();
            HttpResponse<String> metrics = client.send(scrape, HttpResponse.BodyHandlers.ofString());
            assertThat(metrics.statusCode()).isEqualTo(200);
            assertThat(metrics.body()).contains("jvm_memory_used_bytes");
            assertThat(metrics.body()).contains("http_server_requests_seconds_bucket");
            assertThat(metrics.body()).contains("uri=\"/orders/{orderId}\"");
        }
    }
}
