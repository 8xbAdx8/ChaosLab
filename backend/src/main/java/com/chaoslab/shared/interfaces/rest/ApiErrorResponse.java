package com.chaoslab.shared.interfaces.rest;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ApiErrorResponse(
        String code,
        String message,
        String path,
        Instant timestamp,
        Map<String, String> fieldErrors,
        List<ApiViolationResponse> violations
) {
}
