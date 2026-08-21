package com.chaoslab.experiment.interfaces.rest;

import com.chaoslab.experiment.application.ExperimentApplicationService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/experiments")
public class ExperimentController {

    private final ExperimentApplicationService experimentApplicationService;
    private final ExperimentJsonMapper jsonMapper;

    public ExperimentController(
            ExperimentApplicationService experimentApplicationService,
            ExperimentJsonMapper jsonMapper
    ) {
        this.experimentApplicationService = experimentApplicationService;
        this.jsonMapper = jsonMapper;
    }

    @PostMapping
    public ResponseEntity<ExperimentResponse> create(
            @Valid @RequestBody CreateExperimentRequest request
    ) {
        ExperimentResponse response = jsonMapper.from(
                experimentApplicationService.create(jsonMapper.toCommand(request))
        );
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{experimentId}")
                .buildAndExpand(response.id())
                .toUri();
        return ResponseEntity.created(location).body(response);
    }

    @GetMapping("/{experimentId}")
    public ExperimentResponse findById(@PathVariable UUID experimentId) {
        return jsonMapper.from(experimentApplicationService.findById(experimentId));
    }

    @GetMapping
    public List<ExperimentResponse> findAll() {
        return experimentApplicationService.findAll().stream()
                .map(jsonMapper::from)
                .toList();
    }
}
