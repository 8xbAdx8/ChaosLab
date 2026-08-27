package com.chaoslab.execution.application.port;

import java.util.UUID;

public interface TargetExecutionMutex {

    void lockForExperiment(UUID experimentId);
}