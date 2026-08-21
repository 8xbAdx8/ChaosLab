package com.chaoslab.experiment.application.port;

import com.chaoslab.experiment.domain.Experiment;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ExperimentRepository {

    Experiment insert(Experiment experiment);

    Optional<Experiment> findById(UUID id);

    List<Experiment> findAll();
}
