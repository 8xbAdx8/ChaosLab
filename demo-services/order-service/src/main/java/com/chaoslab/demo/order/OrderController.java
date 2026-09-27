package com.chaoslab.demo.order;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import java.net.SocketTimeoutException;
import java.util.Map;

@RestController
public class OrderController {

    private static final Map<String, String> FIXTURES = Map.of(
            "order-1", "item-1",
            "order-2", "item-2"
    );

    private final RestClient inventoryClient;

    public OrderController(RestClient inventoryRestClient) {
        this.inventoryClient = inventoryRestClient;
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "UP");
    }

    @GetMapping("/orders/{orderId}")
    public OrderResponse order(@PathVariable String orderId) {
        String itemId = FIXTURES.get(orderId);
        if (itemId == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown demo order");
        }
        InventoryResponse inventory = inventory(itemId);
        return new OrderResponse(orderId, itemId, inventory.availableQuantity() > 0
                ? "AVAILABLE" : "OUT_OF_STOCK");
    }

    @GetMapping("/slow")
    public Map<String, String> slow() {
        probeInventory("/slow");
        return Map.of("status", "UNEXPECTED_FAST_RESPONSE");
    }

    @GetMapping("/error")
    public Map<String, String> error() {
        probeInventory("/error");
        return Map.of("status", "UNEXPECTED_SUCCESS");
    }

    private InventoryResponse inventory(String itemId) {
        try {
            InventoryResponse response = inventoryClient.get()
                    .uri("/inventory/{itemId}", itemId)
                    .retrieve()
                    .body(InventoryResponse.class);
            if (response == null) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "inventory returned no body");
            }
            return response;
        } catch (ResourceAccessException exception) {
            throw dependencyFailure(exception);
        } catch (RestClientResponseException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "inventory returned an error", exception);
        }
    }

    private void probeInventory(String path) {
        try {
            inventoryClient.get().uri(path).retrieve().toBodilessEntity();
        } catch (ResourceAccessException exception) {
            throw dependencyFailure(exception);
        } catch (RestClientResponseException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "inventory returned an error", exception);
        }
    }

    private ResponseStatusException dependencyFailure(ResourceAccessException exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof SocketTimeoutException) {
                return new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "inventory timed out", exception);
            }
        }
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, "inventory is unreachable", exception);
    }

    public record InventoryResponse(String itemId, int availableQuantity) {
    }

    public record OrderResponse(String orderId, String itemId, String status) {
    }
}
