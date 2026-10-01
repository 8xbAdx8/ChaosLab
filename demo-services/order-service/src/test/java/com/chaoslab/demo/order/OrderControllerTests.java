package com.chaoslab.demo.order;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Timeout(15)
class OrderControllerTests {

    private HttpServer inventory;
    private OrderController controller;
    private CountDownLatch slowRequestReceived;
    private CountDownLatch releaseSlowResponse;

    @BeforeEach
    void startInventoryStub() throws IOException {
        slowRequestReceived = new CountDownLatch(1);
        releaseSlowResponse = new CountDownLatch(1);
        inventory = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        inventory.createContext("/inventory/item-1", exchange -> respond(
                exchange, 200, "{\"itemId\":\"item-1\",\"availableQuantity\":5}"
        ));
        inventory.createContext("/inventory/item-2", exchange -> respond(
                exchange, 200, "{\"itemId\":\"item-2\",\"availableQuantity\":0}"
        ));
        inventory.createContext("/error", exchange -> respond(exchange, 503, "unavailable"));
        inventory.createContext("/slow", exchange -> {
            slowRequestReceived.countDown();
            try {
                // Do not race a short sleep against a short client deadline.
                // The test releases this handler only after observing the timeout.
                releaseSlowResponse.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        inventory.start();
        controller = controllerWithReadTimeout(Duration.ofSeconds(5));
    }

    private OrderController controllerWithReadTimeout(Duration readTimeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(readTimeout);
        RestClient client = RestClient.builder()
                .baseUrl("http://127.0.0.1:" + inventory.getAddress().getPort())
                .requestFactory(factory)
                .build();
        return new OrderController(client);
    }

    @AfterEach
    void stopInventoryStub() {
        releaseSlowResponse.countDown();
        if (inventory != null) inventory.stop(0);
    }

    @Test
    void normalOrderReadsInventory() {
        assertThat(controller.order("order-1"))
                .isEqualTo(new OrderController.OrderResponse("order-1", "item-1", "AVAILABLE"));
        assertThat(controller.order("order-2").status()).isEqualTo("OUT_OF_STOCK");
    }

    @Test
    void unknownOrderDoesNotCallInventory() {
        assertStatus(HttpStatus.NOT_FOUND, () -> controller.order("missing"));
    }

    @Test
    void inventoryErrorBecomesBadGateway() {
        assertStatus(HttpStatus.BAD_GATEWAY, controller::error);
    }

    @Test
    void inventorySlownessBecomesGatewayTimeout() throws InterruptedException {
        OrderController timeoutController = controllerWithReadTimeout(Duration.ofMillis(200));
        try {
            assertStatus(HttpStatus.GATEWAY_TIMEOUT, timeoutController::slow);
            // A connection failure must not accidentally satisfy the read-timeout test.
            assertThat(slowRequestReceived.await(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            releaseSlowResponse.countDown();
        }
    }

    private void assertStatus(HttpStatus expected, Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(expected));
    }

    private static void respond(
            com.sun.net.httpserver.HttpExchange exchange,
            int status,
            String body
    ) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
