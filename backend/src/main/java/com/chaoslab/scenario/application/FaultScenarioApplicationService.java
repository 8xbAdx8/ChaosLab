package com.chaoslab.scenario.application;

import com.chaoslab.scenario.application.dto.FaultScenarioDetails;
import com.chaoslab.scenario.application.port.FaultScenarioRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class FaultScenarioApplicationService {

    private final FaultScenarioRepository faultScenarioRepository;

    public FaultScenarioApplicationService(
            FaultScenarioRepository faultScenarioRepository
    ) {
        this.faultScenarioRepository = Objects.requireNonNull(
                faultScenarioRepository,
                "faultScenarioRepository must not be null"
        );
    }

    public FaultScenarioDetails findById(UUID scenarioId) {
        Objects.requireNonNull(scenarioId, "scenarioId must not be null");
        return faultScenarioRepository.findById(scenarioId)
                .map(FaultScenarioDetails::from)
                .orElseThrow(() -> new FaultScenarioNotFoundException(scenarioId));
    }

    public List<FaultScenarioDetails> findAll() {
        return faultScenarioRepository.findAll().stream()
                .map(FaultScenarioDetails::from)
                .toList();
    }
}
