package com.chaoslab.execution.interfaces.rest;

import com.chaoslab.execution.application.ExperimentExecutionApplicationService;
import com.chaoslab.execution.application.dto.StartExperimentExecutionResult;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/experiments/{experimentId}/executions")
public class ExperimentExecutionController {

    private final ExperimentExecutionApplicationService executionService;
    private final ExperimentExecutionResponseMapper responseMapper;

    public ExperimentExecutionController(
            ExperimentExecutionApplicationService executionService,
            ExperimentExecutionResponseMapper responseMapper
    ) {
        this.executionService = executionService;
        this.responseMapper = responseMapper;
    }

    @PostMapping
    public ResponseEntity<ExperimentExecutionResponse> start(
            @PathVariable UUID experimentId,
            @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        StartExperimentExecutionResult result = executionService.start(
                experimentId,
                idempotencyKey
        );
        ExperimentExecutionResponse response = responseMapper.from(result.execution());
        if (!result.created()) {
            return ResponseEntity.ok(response);
        }
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{executionId}")
                .buildAndExpand(response.id())
                .toUri();
        return ResponseEntity.created(location).body(response);
    }

    @GetMapping("/{executionId}")
    public ExperimentExecutionResponse findById(
            @PathVariable UUID experimentId,
            @PathVariable UUID executionId
    ) {
        return responseMapper.from(executionService.findById(
                experimentId,
                executionId
        ));
    }
}
