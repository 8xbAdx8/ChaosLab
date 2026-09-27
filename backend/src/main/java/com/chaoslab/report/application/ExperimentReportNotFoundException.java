package com.chaoslab.report.application;

import java.util.UUID;

public class ExperimentReportNotFoundException extends RuntimeException {
    public ExperimentReportNotFoundException(UUID id) {
        super("experiment report not found: " + id);
    }
}
