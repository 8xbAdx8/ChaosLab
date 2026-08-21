package com.chaoslab.scenario.application.port;

import com.chaoslab.scenario.domain.FaultScenario;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FaultScenarioRepository {

    Optional<FaultScenario> findById(UUID id);

    List<FaultScenario> findAll();
}
