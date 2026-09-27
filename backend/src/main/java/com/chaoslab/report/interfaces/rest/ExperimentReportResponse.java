package com.chaoslab.report.interfaces.rest;

import com.chaoslab.report.domain.ExperimentReport;
import com.chaoslab.report.domain.ReportConclusionStatus;
import com.chaoslab.report.domain.ReportBindingStatus;
import com.chaoslab.report.domain.ReportExecutionMode;
import com.chaoslab.report.domain.ReportMetricsStatus;
import com.chaoslab.report.domain.ReportWindow;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ExperimentReportResponse(
        UUID id,
        UUID experimentId,
        UUID executionId,
        UUID targetId,
        String scenarioCode,
        UUID startAuditId,
        UUID recoveryAuditId,
        Instant generatedAt,
        ReportExecutionMode executionMode,
        ReportMetricsStatus metricsStatus,
        ReportConclusionStatus conclusionStatus,
        ReportBindingStatus bindingStatus,
        String containerId,
        String imageId,
        String metricsJob,
        String route,
        String reason,
        List<ReportWindow> windows
) {
    public static ExperimentReportResponse from(ExperimentReport report) {
        return new ExperimentReportResponse(
                report.id(), report.experimentId(), report.executionId(),
                report.targetId(), report.scenarioCode(),
                report.startAuditId(), report.recoveryAuditId(),
                report.generatedAt(), report.executionMode(),
                report.metricsStatus(), report.conclusionStatus(),
                report.bindingStatus(), report.containerId(), report.imageId(),
                report.bindingStatus() == ReportBindingStatus.VERIFIED_LOCAL_DEMO
                        ? "order-service" : null,
                report.bindingStatus() == ReportBindingStatus.VERIFIED_LOCAL_DEMO
                        ? "/orders/{orderId}" : null,
                report.reason(), report.windows()
        );
    }
}
