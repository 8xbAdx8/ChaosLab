package com.chaoslab.experiment.application;

import java.util.UUID;

public class ExperimentNotFoundException extends RuntimeException {

    public ExperimentNotFoundException(UUID experimentId) {
        super("experiment not found: " + experimentId);
    }
}
