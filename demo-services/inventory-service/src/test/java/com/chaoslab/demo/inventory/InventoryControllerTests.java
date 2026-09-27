package com.chaoslab.demo.inventory;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InventoryControllerTests {

    private final InventoryController controller = new InventoryController();

    @Test
    void returnsDeterministicFixture() {
        assertThat(controller.inventory("item-1"))
                .isEqualTo(new InventoryController.InventoryResponse("item-1", 5));
        assertThat(controller.inventory("item-2").availableQuantity()).isZero();
    }

    @Test
    void unknownItemIsNotFound() {
        assertThatThrownBy(() -> controller.inventory("missing"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void faultEndpointsAreDeterministic() {
        assertThat(controller.slow(0)).containsEntry("delayMs", 0);
        assertThatThrownBy(controller::error)
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }

    @Test
    void slowEndpointRejectsUnboundedDelay() {
        assertThatThrownBy(() -> controller.slow(2001))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }
}
