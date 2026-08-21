package com.chaoslab.shared.interfaces.rest;

public record ApiViolationResponse(
        String path,
        String keyword,
        String message
) {
}
