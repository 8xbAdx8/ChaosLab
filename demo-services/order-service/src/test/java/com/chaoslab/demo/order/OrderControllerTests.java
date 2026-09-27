package com.chaoslab.demo.order;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderControllerTests {

    private HttpServer inventory;
    private OrderController controller;

    @BeforeEach
    void startInventoryStub() throws IOException {
        inventory = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        inventory.createContext("/inventory/item-1", exchange -> respond(
                exchange, 200, "{\"itemId\":\"item-1\",\"availableQuantity\":5}"
        ));
        inventory.createContext("/inventory/item-2", exchange -> respond(
                exchange, 200, "{\"itemId\":\"item-2\",\"availableQuantity\":0}"
        ));
        inventory.createContext("/error", exchange -> respond(exchange, 503, "unavailable"));
        inventory.createContext("/slow", exchange -> {
            try {
                Thread.sleep(250);
                respond(exchange, 200, "slow");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });
        inventory.start();

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(100));
        factory.setReadTimeout(Duration.ofMillis(100));
        RestClient client = RestClient.builder()
                .baseUrl("http://127.0.0.1:" + inventory.getAddress().getPort())
                .requestFactory(factory)
                .build();
        controller = new OrderController(client);
    }

    @AfterEach
    void stopInventoryStub() {
        inventory.stop(0);
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
    void inventorySlownessBecomesGatewayTimeout() {
        assertStatus(HttpStatus.GATEWAY_TIMEOUT, controller::slow);
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
