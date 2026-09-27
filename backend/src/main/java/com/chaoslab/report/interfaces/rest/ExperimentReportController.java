package com.chaoslab.report.interfaces.rest;

import com.chaoslab.report.application.ExperimentReportApplicationService;
import com.chaoslab.report.application.ReportCreationResult;
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
@RequestMapping("/api/v1/experiments/{experimentId}/executions/{executionId}/reports")
public class ExperimentReportController {

    private final ExperimentReportApplicationService reports;

    public ExperimentReportController(ExperimentReportApplicationService reports) {
        this.reports = reports;
    }

    @PostMapping
    public ResponseEntity<ExperimentReportResponse> create(
            @PathVariable UUID experimentId,
            @PathVariable UUID executionId,
            @RequestHeader("Idempotency-Key") String generationKey
    ) {
        ReportCreationResult result = reports.create(
                experimentId, executionId, generationKey
        );
        ExperimentReportResponse response = ExperimentReportResponse.from(result.report());
        if (!result.created()) {
            return ResponseEntity.ok(response);
        }
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{reportId}")
                .buildAndExpand(response.id())
                .toUri();
        return ResponseEntity.created(location).body(response);
    }

    @GetMapping("/{reportId}")
    public ExperimentReportResponse findById(
            @PathVariable UUID experimentId,
            @PathVariable UUID executionId,
            @PathVariable UUID reportId
    ) {
        return ExperimentReportResponse.from(
                reports.findById(experimentId, executionId, reportId)
        );
    }
}
