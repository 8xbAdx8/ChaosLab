package com.chaoslab.scenario.interfaces.rest;

import com.chaoslab.scenario.application.FaultScenarioApplicationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/scenarios")
public class FaultScenarioController {

    private final FaultScenarioApplicationService faultScenarioApplicationService;
    private final FaultScenarioResponseMapper responseMapper;

    public FaultScenarioController(
            FaultScenarioApplicationService faultScenarioApplicationService,
            FaultScenarioResponseMapper responseMapper
    ) {
        this.faultScenarioApplicationService = faultScenarioApplicationService;
        this.responseMapper = responseMapper;
    }

    @GetMapping("/{scenarioId}")
    public FaultScenarioResponse findById(@PathVariable UUID scenarioId) {
        return responseMapper.from(
                faultScenarioApplicationService.findById(scenarioId)
        );
    }

    @GetMapping
    public List<FaultScenarioResponse> findAll() {
        return faultScenarioApplicationService.findAll().stream()
                .map(responseMapper::from)
                .toList();
    }
}
