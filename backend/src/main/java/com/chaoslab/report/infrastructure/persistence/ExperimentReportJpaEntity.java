package com.chaoslab.report.infrastructure.persistence;

import com.chaoslab.report.domain.ExperimentReport;
import com.chaoslab.report.domain.ReportBindingStatus;
import com.chaoslab.report.domain.ReportConclusionStatus;
import com.chaoslab.report.domain.ReportExecutionMode;
import com.chaoslab.report.domain.ReportMetricsStatus;
import com.chaoslab.report.domain.ReportWindow;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Entity
@Immutable
@Table(name = "experiment_reports")
class ExperimentReportJpaEntity {

    @Id
    @Column(name = "id", nullable = false, length = 36, columnDefinition = "char(36)")
    private String id;

    @Column(name = "experiment_id", nullable = false, length = 36, columnDefinition = "char(36)")
    private String experimentId;

    @Column(name = "execution_id", nullable = false, length = 36, columnDefinition = "char(36)")
    private String executionId;

    @Column(name = "generation_key", nullable = false, length = ExperimentReport.MAX_GENERATION_KEY_LENGTH)
    private String generationKey;

    @Column(name = "target_id", nullable = false, length = 36, columnDefinition = "char(36)")
    private String targetId;

    @Column(name = "scenario_code", nullable = false, length = 64)
    private String scenarioCode;

    @Column(name = "start_audit_id", nullable = false, length = 36, columnDefinition = "char(36)")
    private String startAuditId;

    @Column(name = "recovery_audit_id", nullable = false, length = 36, columnDefinition = "char(36)")
    private String recoveryAuditId;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at", nullable = false)
    private Instant finishedAt;

    @Column(name = "generated_at", nullable = false)
    private Instant generatedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "execution_mode", nullable = false, length = 32)
    private ReportExecutionMode executionMode;

    @Enumerated(EnumType.STRING)
    @Column(name = "metrics_status", nullable = false, length = 32)
    private ReportMetricsStatus metricsStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "conclusion_status", nullable = false, length = 32)
    private ReportConclusionStatus conclusionStatus;

    @Column(name = "reason", nullable = false, length = ExperimentReport.MAX_REASON_LENGTH)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "binding_status", nullable = false, length = 32)
    private ReportBindingStatus bindingStatus;

    @Column(name = "container_id", length = 64)
    private String containerId;

    @Column(name = "image_id", length = 71)
    private String imageId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "observations", columnDefinition = "json")
    private String observationsJson;

    protected ExperimentReportJpaEntity() {
    }

    static ExperimentReportJpaEntity from(ExperimentReport report, String observationsJson) {
        ExperimentReportJpaEntity entity = new ExperimentReportJpaEntity();
        entity.id = report.id().toString();
        entity.experimentId = report.experimentId().toString();
        entity.executionId = report.executionId().toString();
        entity.generationKey = report.generationKey();
        entity.targetId = report.targetId().toString();
        entity.scenarioCode = report.scenarioCode();
        entity.startAuditId = report.startAuditId().toString();
        entity.recoveryAuditId = report.recoveryAuditId().toString();
        entity.startedAt = report.startedAt();
        entity.finishedAt = report.finishedAt();
        entity.generatedAt = report.generatedAt();
        entity.executionMode = report.executionMode();
        entity.metricsStatus = report.metricsStatus();
        entity.conclusionStatus = report.conclusionStatus();
        entity.reason = report.reason();
        entity.bindingStatus = report.bindingStatus();
        entity.containerId = report.containerId();
        entity.imageId = report.imageId();
        entity.observationsJson = observationsJson;
        return entity;
    }

    String observationsJson() {
        return observationsJson;
    }

    ExperimentReport toDomain(List<ReportWindow> observations) {
        return new ExperimentReport(
                UUID.fromString(id), UUID.fromString(experimentId),
                UUID.fromString(executionId), generationKey,
                UUID.fromString(targetId), scenarioCode,
                UUID.fromString(startAuditId), UUID.fromString(recoveryAuditId),
                startedAt, finishedAt, generatedAt,
                executionMode, metricsStatus, conclusionStatus, reason,
                bindingStatus, containerId, imageId, observations
        );
    }
}
