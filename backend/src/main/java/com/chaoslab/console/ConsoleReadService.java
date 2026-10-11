package com.chaoslab.console;

import com.chaoslab.audit.domain.*;
import com.chaoslab.execution.domain.ExperimentExecutionStatus;
import com.chaoslab.execution.interfaces.rest.ExperimentExecutionResponse;
import com.chaoslab.experiment.domain.ExperimentStatus;
import com.chaoslab.experiment.interfaces.rest.ExperimentResponse;
import com.chaoslab.report.application.ExperimentReportApplicationService;
import com.chaoslab.report.domain.ReportMetricsStatus;
import com.chaoslab.report.infrastructure.prometheus.PrometheusReportObservationCollector;
import com.chaoslab.report.interfaces.rest.ExperimentReportResponse;
import java.sql.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
@Transactional(readOnly = true)
public class ConsoleReadService {

    private final JdbcTemplate jdbc;
    private final Environment env;
    private final ExperimentReportApplicationService reports;
    private final JsonMapper json = JsonMapper.builder().build();

    public ConsoleReadService(
        JdbcTemplate jdbc,
        Environment env,
        ExperimentReportApplicationService reports
    ) {
        this.jdbc = jdbc;
        this.env = env;
        this.reports = reports;
    }

    public record Page<T>(List<T> items, long total, int page, int size) {}

    public record Overview(
        long experimentCount,
        long executionCount,
        long targetCount,
        long reportCount,
        Map<String, Long> executionStates,
        Double successRate,
        String successRateDefinition,
        List<ExperimentResponse> recentExperiments,
        String engineMode,
        boolean readOnlyMode,
        boolean authenticationConfigured
    ) {}

    public record PublicAudit(
        UUID id,
        String actor,
        String operation,
        UUID experimentId,
        UUID executionId,
        UUID targetId,
        String scenarioCode,
        JsonNode parameters,
        String result,
        String failureCode,
        Instant occurredAt
    ) {}

    public record Evidence(
        UUID executionId,
        String nativeUid,
        String snapshotFormat,
        Double baselineCpuPercent,
        Double duringCpuPercent,
        Double afterCpuPercent,
        String nativeStatus,
        String physicalRecovery,
        String residual,
        String health,
        String recoveryGate,
        String recoveryCause,
        Instant cpuObservedAt,
        Instant recoveryAttemptStartedAt,
        Instant engineObservedAt,
        Instant recoveryObservedAt,
        UUID cpuAuditId,
        UUID recoveryAuditId,
        String note
    ) {}

    /** Composition of existing persisted records. Reading never samples a target or creates a report. */
    public record ReportAnalysis(
        ExperimentReportResponse report,
        ExperimentExecutionResponse execution,
        String experimentName,
        String targetName,
        String targetType,
        JsonNode parameters,
        String parameterSource,
        String traceability,
        String comparisonStatus,
        String metricSource,
        String metricSubject,
        String metricInstance,
        Long durationMillis,
        Evidence recovery,
        List<PublicAudit> audits,
        boolean auditsTruncated,
        List<String> limitations
    ) {}

    public ReportAnalysis analysis(UUID reportId) {
        var report = report(reportId);
        var execution = execution(report.executionId());
        var definition = experiment(report.experimentId());
        var target = jdbc.queryForMap(
            "SELECT name,target_type FROM targets WHERE id=?",
            report.targetId().toString()
        );
        var events = jdbc.query(
            "SELECT * FROM audit_logs WHERE experiment_id=? AND execution_id=? ORDER BY occurred_at,id LIMIT 101",
            publicAudit,
            report.experimentId().toString(),
            report.executionId().toString()
        );
        var linked = jdbc.query(
            "SELECT * FROM audit_logs WHERE id IN (?,?)",
            publicAudit,
            report.startAuditId().toString(),
            report.recoveryAuditId().toString()
        );
        var start = linked
            .stream()
            .filter(a -> a.id().equals(report.startAuditId()))
            .findFirst()
            .orElse(null);
        var recovery = linked
            .stream()
            .filter(a -> a.id().equals(report.recoveryAuditId()))
            .findFirst()
            .orElse(null);
        boolean matches =
            matchesReport(start, report) &&
            matchesReport(recovery, report) &&
            "START_EXPERIMENT".equals(start.operation()) &&
            Set.of(
                "DESTROY_EXPERIMENT",
                "AUTOMATIC_RECOVERY",
                "EMERGENCY_RECOVERY"
            ).contains(recovery.operation()) &&
            !start.occurredAt().isAfter(recovery.occurredAt()) &&
            execution.experimentId().equals(report.experimentId()) &&
            Objects.equals(
                execution.startedAt(),
                report.windows().get(1).start()
            ) &&
            Objects.equals(
                execution.finishedAt(),
                report.windows().get(1).end()
            );
        // Legacy reports retain their original conclusions. Missing version is not silently
        // upgraded to M5 quality, even if a historical row says OBSERVED/SUCCESS.
        boolean v2 = report
            .windows()
            .stream()
            .allMatch(w ->
                PrometheusReportObservationCollector.COLLECTION_VERSION.equals(
                    w.collectionVersion()
                )
            );
        boolean complete = report
            .windows()
            .stream()
            .allMatch(w -> w.metricsStatus() == ReportMetricsStatus.OBSERVED);
        boolean comparable =
            matches &&
            v2 &&
            complete &&
            "VERIFIED_LOCAL_DEMO".equals(report.bindingStatus().name());
        var limitations = new ArrayList<String>();
        limitations.add(
            "CPU/memory are NOT_COLLECTED unless separate same-subject persisted cgroup evidence exists; JVM metrics are not container metrics."
        );
        limitations.add(
            "HTTP metrics describe the bound order-service route, not proof that a fault occurred or active destroy caused recovery."
        );
        if (!matches) limitations.add(
            "Report audit/execution identity or persisted time window is missing/mismatched; comparison rejected."
        );
        if (!v2) limitations.add(
            "No M5 collector version recorded: legacy/NOT_COLLECTED values are preserved, not recertified."
        );
        if (!complete) limitations.add(
            "Missing or insufficient phase data is not zero. P95 and error comparison are unavailable."
        );
        if ("SIMULATED".equals(report.executionMode().name())) limitations.add(
            "SIMULATED lifecycle SUCCESS is NOT_VERIFIED physical recovery; percent is an input, not a measurement."
        );
        boolean truncated = events.size() > 100;
        if (truncated) limitations.add(
            "Audit display is limited to the first 100 events; use audit filters for remaining events."
        );
        return new ReportAnalysis(
            report,
            execution,
            definition.name(),
            (String) target.get("name"),
            (String) target.get("target_type"),
            matches ? start.parameters() : json.createObjectNode(),
            matches ? "START_AUDIT" : "UNKNOWN",
            matches ? "MATCHED" : "REJECTED",
            comparable ? "AVAILABLE" : "NOT_COMPARABLE",
            v2 ? "PROMETHEUS_HTTP_V2" : "UNKNOWN",
            report.metricsJob() == null
                ? "UNKNOWN"
                : "BOUND_ORDER_SERVICE_HTTP_NOT_CONTAINER_CPU",
            v2 && report.metricsJob() != null ? "order-service:8080" : null,
            execution.startedAt() != null && execution.finishedAt() != null
                ? Duration.between(
                      execution.startedAt(),
                      execution.finishedAt()
                  ).toMillis()
                : null,
            matches && !"SIMULATED".equals(report.executionMode().name())
                ? evidence(report.executionId(), report)
                : unknownEvidence(
                      report.executionId(),
                      "No physical recovery certification for simulated or mismatched report subjects."
                  ),
            events.stream().limit(100).toList(),
            truncated,
            List.copyOf(limitations)
        );
    }

    private static boolean matchesReport(
        PublicAudit audit,
        ExperimentReportResponse report
    ) {
        return (
            audit != null &&
            "SUCCESS".equals(audit.result()) &&
            report.experimentId().equals(audit.experimentId()) &&
            report.executionId().equals(audit.executionId()) &&
            report.targetId().equals(audit.targetId()) &&
            report.scenarioCode().equals(audit.scenarioCode())
        );
    }

    private static Instant at(ResultSet r, String column) throws SQLException {
        var t = r.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }

    private static UUID id(ResultSet r, String column) throws SQLException {
        var s = r.getString(column);
        return s == null ? null : UUID.fromString(s);
    }

    private JsonNode tree(String raw) {
        try {
            return raw == null ? json.nullNode() : json.readTree(raw);
        } catch (RuntimeException invalid) {
            return json.nullNode();
        }
    }

    private long count(String table) {
        return Objects.requireNonNull(
            jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class)
        );
    }

    private static String safeReason(String value) {
        return value == null
            ? null
            : value.matches("[A-Z][A-Z0-9_]{0,99}")
              ? value
              : "DETAILS_REDACTED";
    }

    private final RowMapper<ExperimentResponse> experiment = (r, n) ->
        new ExperimentResponse(
            id(r, "id"),
            r.getString("name"),
            r.getString("hypothesis"),
            id(r, "target_id"),
            id(r, "scenario_id"),
            r.getInt("duration_seconds"),
            publicParameters(tree(r.getString("parameters"))),
            ExperimentStatus.valueOf(r.getString("status")),
            r.getLong("version")
        );
    private final RowMapper<ExperimentExecutionResponse> execution = (r, n) ->
        new ExperimentExecutionResponse(
            id(r, "id"),
            id(r, "experiment_id"),
            r.getInt("attempt"),
            r.getString("idempotency_key"),
            ExperimentExecutionStatus.valueOf(r.getString("status")),
            r.getString("engine_experiment_id"),
            safeReason(r.getString("error_message")),
            at(r, "created_at"),
            at(r, "started_at"),
            at(r, "finished_at"),
            r.getLong("version")
        );

    private final RowMapper<PublicAudit> publicAudit = (r, n) ->
        new PublicAudit(
            id(r, "id"),
            r.getString("actor"),
            r.getString("operation"),
            id(r, "experiment_id"),
            id(r, "execution_id"),
            id(r, "target_id"),
            r.getString("scenario_code"),
            publicParameters(tree(r.getString("parameters"))),
            r.getString("result"),
            safeReason(r.getString("failure_code")),
            at(r, "occurred_at")
        );

    private static void bounds(int page, int size) {
        if (
            page < 0 || page > 10000 || size < 1 || size > 100
        ) throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "invalid page bounds"
        );
    }

    private static void enumFilter(
        String value,
        Class<? extends Enum<?>> type
    ) {
        if (
            value != null &&
            Arrays.stream(type.getEnumConstants()).noneMatch(e ->
                e.name().equals(value)
            )
        ) throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "invalid filter"
        );
    }

    private <T> Page<T> page(
        String table,
        String where,
        List<Object> args,
        String order,
        int page,
        int size,
        RowMapper<T> mapper
    ) {
        bounds(page, size);
        long total = Objects.requireNonNull(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + where,
                Long.class,
                args.toArray()
            )
        );
        var values = new ArrayList<>(args);
        values.add(size);
        values.add(page * size);
        return new Page<>(
            jdbc.query(
                "SELECT * FROM " + table + where + order + " LIMIT ? OFFSET ?",
                mapper,
                values.toArray()
            ),
            total,
            page,
            size
        );
    }

    public Overview overview() {
        var states = new LinkedHashMap<String, Long>();
        for (var s : ExperimentExecutionStatus.values())
            states.put(s.name(), 0L);
        jdbc.query(
            "SELECT status,COUNT(*) AS n FROM experiment_executions GROUP BY status",
            r -> {
                states.put(r.getString("status"), r.getLong("n"));
            }
        );
        long executions = count("experiment_executions");
        // There is no experiment created_at column. Recency is actual latest execution activity;
        // never pretend UUID/name order is a creation timestamp.
        var recent = jdbc.query(
            "SELECT e.* FROM experiments e LEFT JOIN (SELECT experiment_id,MAX(created_at) AS latest FROM experiment_executions GROUP BY experiment_id) x ON x.experiment_id=e.id ORDER BY CASE WHEN x.latest IS NULL THEN 1 ELSE 0 END,x.latest DESC,e.name,e.id LIMIT 5",
            experiment
        );
        return new Overview(
            count("experiments"),
            executions,
            count("targets"),
            count("experiment_reports"),
            states,
            executions == 0
                ? null
                : (100.0 * states.getOrDefault("SUCCESS", 0L)) / executions,
            "SUCCESS / all executions; not proof of causal provenance",
            recent,
            env.getProperty("chaoslab.engine", "fake"),
            env.getProperty("chaoslab.console.read-only", Boolean.class, false),
            !env.matchesProfiles("history-demo") &&
                (!env
                    .getProperty("chaoslab.security.viewer.username", "")
                    .isEmpty() ||
                    !env
                        .getProperty("chaoslab.security.admin.username", "")
                        .isEmpty())
        );
    }

    public Page<ExperimentResponse> experiments(
        int page,
        int size,
        String search,
        String status
    ) {
        enumFilter(status, ExperimentStatus.class);
        if (search.length() > 100) throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "search too long"
        );
        var args = new ArrayList<Object>();
        String where = " WHERE 1=1";
        if (!search.isBlank()) {
            where += " AND LOWER(name) LIKE ? ESCAPE '!'";
            args.add(
                "%" +
                    search
                        .toLowerCase(Locale.ROOT)
                        .replace("!", "!!")
                        .replace("%", "!%")
                        .replace("_", "!_") +
                    "%"
            );
        }
        if (status != null) {
            where += " AND status=?";
            args.add(status);
        }
        return page(
            "experiments",
            where,
            args,
            " ORDER BY name,id",
            page,
            size,
            experiment
        );
    }

    public Page<ExperimentExecutionResponse> executions(
        int page,
        int size,
        UUID experimentId,
        String status
    ) {
        enumFilter(status, ExperimentExecutionStatus.class);
        var args = new ArrayList<Object>();
        String where = " WHERE 1=1";
        if (experimentId != null) {
            where += " AND experiment_id=?";
            args.add(experimentId.toString());
        }
        if (status != null) {
            where += " AND status=?";
            args.add(status);
        }
        return page(
            "experiment_executions",
            where,
            args,
            " ORDER BY created_at DESC,id DESC",
            page,
            size,
            execution
        );
    }

    public ExperimentExecutionResponse execution(UUID executionId) {
        return jdbc
            .query(
                "SELECT * FROM experiment_executions WHERE id=?",
                execution,
                executionId.toString()
            )
            .stream()
            .findFirst()
            .orElseThrow(() ->
                new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "execution missing"
                )
            );
    }

    public ExperimentResponse experiment(UUID experimentId) {
        return jdbc
            .query(
                "SELECT * FROM experiments WHERE id=?",
                experiment,
                experimentId.toString()
            )
            .stream()
            .findFirst()
            .orElseThrow(() ->
                new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "experiment missing"
                )
            );
    }

    public ExperimentReportResponse report(UUID reportId) {
        return jdbc
            .query(
                "SELECT * FROM experiment_reports WHERE id=?",
                (r, n) ->
                    ExperimentReportResponse.from(
                        reports.findById(
                            id(r, "experiment_id"),
                            id(r, "execution_id"),
                            id(r, "id")
                        )
                    ),
                reportId.toString()
            )
            .stream()
            .findFirst()
            .orElseThrow(() ->
                new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "report missing"
                )
            );
    }

    public Page<PublicAudit> audits(
        int page,
        int size,
        UUID experimentId,
        String operation,
        String result,
        Instant from,
        Instant to
    ) {
        enumFilter(operation, AuditOperation.class);
        enumFilter(result, AuditResult.class);
        if (
            from != null && to != null && from.isAfter(to)
        ) throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "time range reversed"
        );
        var args = new ArrayList<Object>();
        String where = " WHERE 1=1";
        if (experimentId != null) {
            where += " AND experiment_id=?";
            args.add(experimentId.toString());
        }
        if (operation != null) {
            where += " AND operation=?";
            args.add(operation);
        }
        if (result != null) {
            where += " AND result=?";
            args.add(result);
        }
        if (from != null) {
            where += " AND occurred_at>=?";
            args.add(Timestamp.from(from));
        }
        if (to != null) {
            where += " AND occurred_at<=?";
            args.add(Timestamp.from(to));
        }
        return page(
            "audit_logs",
            where,
            args,
            " ORDER BY occurred_at DESC,id DESC",
            page,
            size,
            publicAudit
        );
    }

    /** Fixed PUBLIC field projection, not arbitrary audit JSON/root stderr/IP/path exposure. */
    private JsonNode publicParameters(JsonNode source) {
        var out = json.createObjectNode();
        for (String key : List.of(
            "percent",
            "cpuPercent",
            "baselinePercent",
            "usageUsec",
            "durationSeconds",
            "delayMs",
            "jitterMs",
            "version"
        ))
            if (
                source.path(key).isNumber() &&
                Double.isFinite(source.path(key).asDouble())
            ) out.set(key, source.path(key));
        for (String key : List.of(
            "nativeUid",
            "nodeId",
            "stateId",
            "containerId",
            "imageId",
            "toolSha256",
            "physicalRecovery",
            "nativeStatus",
            "residual",
            "health",
            "recoveryCause",
            "activeDestroyRequest",
            "observedAt",
            "engineObservedAt",
            "recoveryAttemptStartedAt"
        )) {
            String value = source.path(key).asText("");
            if (
                value.length() > 0 &&
                value.length() <= 100 &&
                value.matches("[a-zA-Z0-9:._+\\-]+")
            ) out.put(key, value);
        }
        return out;
    }

    public Page<ExperimentReportResponse> reports(
        int page,
        int size,
        UUID executionId
    ) {
        var args = new ArrayList<Object>();
        String where = "";
        if (executionId != null) {
            where = " WHERE execution_id=?";
            args.add(executionId.toString());
        }
        // Existing ownership-aware report reader preserves its original independent conclusions.
        return page(
            "experiment_reports",
            where,
            args,
            " ORDER BY generated_at DESC,id DESC",
            page,
            size,
            (r, n) ->
                ExperimentReportResponse.from(
                    reports.findById(
                        id(r, "experiment_id"),
                        id(r, "execution_id"),
                        id(r, "id")
                    )
                )
        );
    }

    private record AuditEvidence(UUID id, JsonNode data) {}

    private AuditEvidence matchingEvidence(
        UUID executionId,
        Map<String, Object> snapshot,
        String operation
    ) {
        var candidates = jdbc.query(
            "SELECT id,parameters FROM audit_logs WHERE execution_id=? AND target_id=? AND actor='BLADE_M1_CORE' AND operation=? AND result='SUCCESS' ORDER BY occurred_at DESC,id DESC LIMIT 100",
            (r, n) ->
                new AuditEvidence(id(r, "id"), tree(r.getString("parameters"))),
            executionId.toString(),
            snapshot.get("target_id"),
            operation
        );
        Map<String, String> keys = Map.of(
            "nativeUid",
            "blade_uid",
            "nodeId",
            "executor_instance_id",
            "stateId",
            "state_directory_id",
            "containerId",
            "container_id",
            "imageId",
            "image_id",
            "toolSha256",
            "tool_sha256"
        );
        return candidates
            .stream()
            .filter(
                a ->
                    a.data.path("version").asInt(-1) == 1 &&
                    keys
                        .entrySet()
                        .stream()
                        .allMatch(k ->
                            Objects.equals(
                                a.data.path(k.getKey()).asText(null),
                                snapshot.get(k.getValue())
                            )
                        )
            )
            .findFirst()
            .orElse(null);
    }

    private static Instant instant(JsonNode data, String key) {
        try {
            return Instant.parse(data.path(key).asText());
        } catch (RuntimeException unknown) {
            return null;
        }
    }

    private static Double number(JsonNode data, String key) {
        return data.path(key).isNumber() &&
            Double.isFinite(data.path(key).asDouble()) &&
            data.path(key).asDouble() >= 0
            ? data.path(key).asDouble()
            : null;
    }

    public Evidence evidence(UUID executionId) {
        return evidence(executionId, null);
    }

    private Evidence evidence(
        UUID executionId,
        ExperimentReportResponse expectedReport
    ) {
        execution(executionId);
        var rows = jdbc.queryForList(
            "SELECT * FROM blade_execution_snapshots WHERE execution_id=?",
            executionId.toString()
        );
        if (rows.isEmpty()) return unknownEvidence(
            executionId,
            "No persisted native evidence; execution SUCCESS alone is not recovery proof."
        );
        var snapshot = rows.getFirst();
        if (
            expectedReport != null &&
            (!expectedReport
                .targetId()
                .toString()
                .equals(snapshot.get("target_id")) ||
                (expectedReport.containerId() != null &&
                    !expectedReport
                        .containerId()
                        .equals(snapshot.get("container_id"))) ||
                (expectedReport.imageId() != null &&
                    !expectedReport.imageId().equals(snapshot.get("image_id"))))
        ) {
            return unknownEvidence(
                executionId,
                "Native snapshot target does not match this report; recovery correlation rejected."
            );
        }
        var cpu = matchingEvidence(executionId, snapshot, "M1_CPU_OBSERVATION");
        var recovered = matchingEvidence(
            executionId,
            snapshot,
            "M1_PHYSICAL_RECOVERY"
        );
        var c = cpu == null ? json.nullNode() : cpu.data;
        var p = recovered == null ? json.nullNode() : recovered.data;
        boolean verified =
            "VERIFIED".equals(p.path("physicalRecovery").asText()) &&
            "Destroyed".equals(p.path("nativeStatus").asText()) &&
            "CLEAR".equals(p.path("residual").asText()) &&
            "HEALTHY".equals(p.path("health").asText()) &&
            instant(p, "observedAt") != null &&
            instant(p, "engineObservedAt") != null;
        return new Evidence(
            executionId,
            (String) snapshot.get("blade_uid"),
            (String) snapshot.get("snapshot_format"),
            number(c, "baselinePercent"),
            number(c, "cpuPercent"),
            null,
            verified ? "Destroyed" : "UNKNOWN",
            verified ? "VERIFIED" : "UNKNOWN",
            verified ? "CLEAR" : "UNKNOWN",
            verified ? "HEALTHY" : "UNKNOWN",
            verified ? "VERIFIED" : "UNKNOWN",
            "UNKNOWN",
            instant(c, "observedAt"),
            instant(p, "recoveryAttemptStartedAt"),
            instant(p, "engineObservedAt"),
            instant(p, "observedAt"),
            cpu == null ? null : cpu.id,
            recovered == null ? null : recovered.id,
            "Stored same-subject assessment, not a live probe. After CPU was not persisted in recovery audit; HEALTHY is not a numerical sample. Causality remains UNKNOWN."
        );
    }

    private static Evidence unknownEvidence(UUID executionId, String note) {
        return new Evidence(
            executionId,
            null,
            null,
            null,
            null,
            null,
            "UNKNOWN",
            "UNKNOWN",
            "UNKNOWN",
            "UNKNOWN",
            "UNKNOWN",
            "UNKNOWN",
            null,
            null,
            null,
            null,
            null,
            null,
            note
        );
    }
}
