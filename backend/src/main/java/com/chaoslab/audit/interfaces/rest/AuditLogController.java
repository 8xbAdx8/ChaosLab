package com.chaoslab.audit.interfaces.rest;

import com.chaoslab.audit.application.AuditLogApplicationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/audit-logs")
public class AuditLogController {

    private final AuditLogApplicationService auditLogService;
    private final AuditLogResponseMapper responseMapper;

    public AuditLogController(
            AuditLogApplicationService auditLogService,
            AuditLogResponseMapper responseMapper
    ) {
        this.auditLogService = auditLogService;
        this.responseMapper = responseMapper;
    }

    @GetMapping
    public List<AuditLogResponse> findRecent(
            @RequestParam(required = false) UUID experimentId,
            @RequestParam(defaultValue = "100") int limit
    ) {
        return auditLogService.findRecent(experimentId, limit).stream()
                .map(responseMapper::from)
                .toList();
    }

    @GetMapping("/by-execution/{experimentId}/{executionId}")
    public List<AuditLogResponse> findByExecution(
            @PathVariable UUID experimentId,
            @PathVariable UUID executionId
    ) {
        return auditLogService.findByExecution(experimentId, executionId).stream()
                .map(responseMapper::from)
                .toList();
    }
}
