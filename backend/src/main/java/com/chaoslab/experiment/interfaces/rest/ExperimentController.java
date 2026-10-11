package com.chaoslab.experiment.interfaces.rest;

import com.chaoslab.experiment.application.ExperimentApplicationService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/v1/experiments")
public class ExperimentController {

    private final ExperimentApplicationService experimentApplicationService;
    private final ExperimentJsonMapper jsonMapper;
    private final com.chaoslab.audit.application.DangerousOperationAuditor auditor;
    private final org.springframework.beans.factory.ObjectProvider<com.chaoslab.console.fakedemo.FakeDemoConfiguration> fakeDemo;

    public ExperimentController(
        ExperimentApplicationService experimentApplicationService,
        ExperimentJsonMapper jsonMapper,
        com.chaoslab.audit.application.DangerousOperationAuditor auditor,
        org.springframework.beans.factory.ObjectProvider<com.chaoslab.console.fakedemo.FakeDemoConfiguration> fakeDemo
    ) {
        this.experimentApplicationService = experimentApplicationService;
        this.jsonMapper = jsonMapper;
        this.auditor = auditor;
        this.fakeDemo = fakeDemo;
    }

    @PostMapping
    public ResponseEntity<ExperimentResponse> create(
        @Valid @RequestBody CreateExperimentRequest request
    ) {
        ExperimentResponse response = jsonMapper.from(
            experimentApplicationService.create(jsonMapper.toCommand(request))
        );
        audit(
            com.chaoslab.audit.domain.AuditOperation.CREATE_EXPERIMENT,
            response.id()
        );
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
            .path("/{experimentId}")
            .buildAndExpand(response.id())
            .toUri();
        return ResponseEntity.created(location).body(response);
    }

    @GetMapping("/{experimentId}")
    public ExperimentResponse findById(@PathVariable UUID experimentId) {
        return jsonMapper.from(
            experimentApplicationService.findById(experimentId)
        );
    }

    @PostMapping("/{experimentId}/validation")
    public ExperimentResponse validate(@PathVariable UUID experimentId) {
        var response = jsonMapper.from(
            experimentApplicationService.validate(experimentId)
        );
        audit(
            com.chaoslab.audit.domain.AuditOperation.VALIDATE_EXPERIMENT,
            experimentId
        );
        return response;
    }

    @PostMapping("/{experimentId}/dry-run")
    public ExperimentDryRunResponse dryRun(@PathVariable UUID experimentId) {
        var response = jsonMapper.from(
            experimentApplicationService.dryRun(experimentId)
        );
        if (
            fakeDemo.getIfAvailable() != null && fakeDemo.getObject().enabled()
        ) auditor.complete(
            auditor.prepare(
                com.chaoslab.audit.domain.AuditOperation.DRY_RUN_EXPERIMENT,
                experimentId,
                null
            ),
            response.accepted()
                ? com.chaoslab.audit.domain.AuditResult.SUCCESS
                : com.chaoslab.audit.domain.AuditResult.REJECTED,
            response.accepted() ? null : "SAFETY_CHECK_REJECTED"
        );
        return response;
    }

    @org.springframework.web.bind.annotation.PutMapping("/{experimentId}")
    public ExperimentResponse update(
        @PathVariable UUID experimentId,
        @Valid @RequestBody UpdateExperimentRequest request
    ) {
        var response = jsonMapper.from(
            experimentApplicationService.update(
                experimentId,
                request.version(),
                jsonMapper.toCommand(request.definition())
            )
        );
        audit(
            com.chaoslab.audit.domain.AuditOperation.EDIT_EXPERIMENT,
            experimentId
        );
        return response;
    }

    private void audit(com.chaoslab.audit.domain.AuditOperation op, UUID id) {
        if (
            fakeDemo.getIfAvailable() != null && fakeDemo.getObject().enabled()
        ) auditor.complete(
            auditor.prepare(op, id, null),
            com.chaoslab.audit.domain.AuditResult.SUCCESS,
            null
        );
    }

    @GetMapping
    public List<ExperimentResponse> findAll() {
        return experimentApplicationService
            .findAll()
            .stream()
            .map(jsonMapper::from)
            .toList();
    }
}
