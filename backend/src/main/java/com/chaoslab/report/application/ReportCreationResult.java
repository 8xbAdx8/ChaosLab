package com.chaoslab.report.application;

import com.chaoslab.report.domain.ExperimentReport;

public record ReportCreationResult(ExperimentReport report, boolean created) {
}
