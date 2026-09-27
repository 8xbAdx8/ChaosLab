package com.chaoslab.demo.inventory;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestController
public class InventoryController {

    private static final Map<String, Integer> FIXTURES = Map.of(
            "item-1", 5,
            "item-2", 0
    );

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "UP");
    }

    @GetMapping("/inventory/{itemId}")
    public InventoryResponse inventory(@PathVariable String itemId) {
        Integer quantity = FIXTURES.get(itemId);
        if (quantity == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown demo item");
        }
        return new InventoryResponse(itemId, quantity);
    }

    @GetMapping("/slow")
    public Map<String, Object> slow(
            @RequestParam(defaultValue = "1200") int delayMs
    ) {
        if (delayMs < 0 || delayMs > 2000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "delayMs must be between 0 and 2000");
        }
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "demo delay interrupted", exception);
        }
        return Map.of("status", "SLOW_RESPONSE", "delayMs", delayMs);
    }

    @GetMapping("/error")
    public void error() {
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "simulated inventory failure");
    }

    public record InventoryResponse(String itemId, int availableQuantity) {
    }
}
