package com.chaoslab.engine.infrastructure.blade;

import com.chaoslab.safety.application.model.VerifiedDockerTarget;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Immutable intent and append-only M1 observations in the existing audit store. */
@Repository
@Transactional(readOnly = true)
public class JdbcBladeExecutionJournal {
    private final JdbcTemplate jdbc;
    private final com.chaoslab.audit.application.port.AuditLogRepository audits;

    @org.springframework.beans.factory.annotation.Autowired
    public JdbcBladeExecutionJournal(JdbcTemplate jdbc, com.chaoslab.audit.application.port.AuditLogRepository audits) {
        this.jdbc = jdbc; this.audits = java.util.Objects.requireNonNull(audits);
    }
    /** Read-only reloading seam; it cannot append evidence. */
    JdbcBladeExecutionJournal(JdbcTemplate jdbc) { this.jdbc = jdbc; this.audits = null; }

    /** A new immutable intent must commit before dispatch; duplicate inserts fail closed. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordIntent(BladeExecutionSnapshot snapshot) {
        if (snapshot.uid() != null && !BladeExecutionSnapshot.CRI_CPU_V1.equals(snapshot.format()))
            throw new IllegalArgumentException("legacy intent must not contain a UID");
        int inserted = jdbc.update("""
                INSERT INTO blade_execution_snapshots
                (execution_id, snapshot_format, target_id, container_id, image_id, metrics_job,
                 executor_instance_id, state_directory_id, tool_version, tool_sha256,
                 cpu_percent, duration_seconds, recorded_at, recovery_deadline, blade_uid)
                SELECT x.id, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
                FROM experiment_executions x JOIN experiments e ON e.id = x.experiment_id
                JOIN targets t ON t.id = e.target_id JOIN fault_scenarios s ON s.id = e.scenario_id
                WHERE x.id = ? AND x.status = 'PREPARING' AND e.target_id = ?
                AND t.target_type = 'DOCKER_CONTAINER' AND s.code = 'CPU_LOAD' AND e.duration_seconds = ?
                """, snapshot.format(), snapshot.target().targetId().toString(), snapshot.target().containerId(), snapshot.target().imageId(),
                snapshot.target().metricsJob(), snapshot.executorInstanceId(), snapshot.stateDirectoryId(),
                snapshot.toolVersion(), snapshot.toolSha256(), snapshot.cpuPercent(), snapshot.durationSeconds(),
                Timestamp.from(snapshot.recordedAt()), Timestamp.from(snapshot.recoveryDeadline()),
                snapshot.uid(),
                snapshot.executionId().toString(), snapshot.target().targetId().toString(), snapshot.durationSeconds());
        if (inserted != 1) throw new IllegalStateException("intent requires a matching committed preparing execution");
    }

    /** Write-once UID: repeated identical evidence is idempotent, conflicting evidence is rejected. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordUid(BladeRecoveryHandle handle, String stateDirectoryId) {
        BladeExecutionSnapshot saved = jdbc.query(
                "SELECT * FROM blade_execution_snapshots WHERE execution_id = ? FOR UPDATE",
                this::map, handle.executionId().toString()).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("execution intent is missing"));
        if (!saved.executorInstanceId().equals(handle.executorInstanceId())
                || !saved.stateDirectoryId().equals(stateDirectoryId) || !saved.target().equals(handle.target())
                || !saved.format().equals(handle.format())) {
            throw new IllegalStateException("recovery identity does not match the persisted intent");
        }
        if (saved.uid() != null) {
            if (!saved.uid().equals(handle.uid())) throw new IllegalStateException("recovery UID cannot change");
            return;
        }
        jdbc.update("UPDATE blade_execution_snapshots SET blade_uid = ? WHERE execution_id = ?",
                handle.uid(), handle.executionId().toString());
    }

    public Optional<BladeExecutionSnapshot> findByExecutionId(UUID executionId) {
        return jdbc.query("SELECT * FROM blade_execution_snapshots WHERE execution_id = ?",
                this::map, executionId.toString()).stream().findFirst();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordCpuFault(BladeExecutionSnapshot saved, BladeProcessChannel.RecoveryObservation observed) {
        if (!validCpuFault(saved, observed, Instant.now())) throw new IllegalStateException("CPU fault evidence unavailable");
        var data = evidenceSubject(saved);
        data.put("baselinePercent", observed.cpu().baseline());
        data.put("cpuPercent", observed.cpu().percent());
        data.put("usageUsec", observed.cpu().usageUsec());
        data.put("observedAt", observed.observedAt().toString());
        appendObservation(saved, "M1_CPU_OBSERVATION", "PREPARING", data);
    }

    /** Durable during evidence must exist before recovery can release occupancy. */
    public boolean hasCpuFault(BladeExecutionSnapshot saved, Instant recoveryStarted) {
        for (String raw : jdbc.queryForList("SELECT parameters FROM audit_logs WHERE execution_id=? AND target_id=? "
                + "AND actor='BLADE_M1_CORE' AND operation='M1_CPU_OBSERVATION' AND result='SUCCESS'",
                String.class, saved.executionId().toString(), saved.target().targetId().toString())) {
            try {
                var data = json().readTree(raw);
                var subject = evidenceSubject(saved);
                if (!data.isObject() || data.size() != subject.size()+4) continue;
                boolean matched = true;
                for (String key : subject.propertyNames()) if (!subject.path(key).equals(data.path(key))) matched = false;
                if (!matched || !data.path("cpuPercent").isNumber() || !data.path("baselinePercent").isNumber()
                        || !data.path("usageUsec").isIntegralNumber() || !data.path("usageUsec").canConvertToLong()) continue;
                var observed = new BladeProcessChannel.RecoveryObservation(saved.recoveryHandle().orElseThrow(),
                        Instant.parse(data.path("observedAt").asText()), null, null,
                        new BladeProcessChannel.CpuEvidence(data.path("cpuPercent").asDouble(),
                                data.path("baselinePercent").asDouble(), data.path("usageUsec").asLong()));
                if (!observed.observedAt().isAfter(recoveryStarted) && validCpuFault(saved, observed, observed.observedAt())) return true;
            } catch (RuntimeException invalid) { /* Missing/corrupt evidence never certifies an effect. */ }
        }
        return false;
    }

    static boolean validCpuFault(BladeExecutionSnapshot saved, BladeProcessChannel.RecoveryObservation observed, Instant now) {
        return observed != null && saved.recoveryHandle().orElseThrow().equals(observed.subject()) && observed.cpu() != null
                && observed.observedAt() != null && !observed.observedAt().isBefore(saved.recordedAt())
                && !observed.observedAt().isAfter(now) && java.time.Duration.between(observed.observedAt(), now).compareTo(java.time.Duration.ofSeconds(10)) <= 0
                // Fixed count=1; tolerate sampling jitter, never accept an idle or unrelated high-load sample.
                && observed.cpu().percent() >= saved.cpuPercent()*0.5 && observed.cpu().percent() <= saved.cpuPercent()*1.5
                && observed.cpu().usageUsec() > 0
                && observed.cpu().percent() > observed.cpu().baseline()+1;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordPhysicalRecovery(BladeExecutionSnapshot saved, Instant recoveryStarted, Instant engineObservedAt,
            BladeProcessChannel.RecoveryObservation observed) {
        var handle = saved.recoveryHandle().orElseThrow();
        if (observed == null || !hasCpuFault(saved, recoveryStarted) || BladeRecoveryEvidenceValidator.assess(handle,
                recoveryStarted, Instant.now(), java.time.Duration.ofSeconds(10),
                new BladeRecoveryEvidenceValidator.Observation<>(handle, engineObservedAt, BladeRecoveryContract.Decision.CONFIRMED_RECOVERED),
                new BladeRecoveryEvidenceValidator.Observation<>(observed.subject(), observed.observedAt(), observed.residual()),
                new BladeRecoveryEvidenceValidator.Observation<>(observed.subject(), observed.observedAt(), observed.health()))
                != BladeRecoveryEvidenceGate.Outcome.VERIFIED) throw new IllegalStateException("recovery audit evidence rejected");
        var data = evidenceSubject(saved);
        data.put("physicalRecovery", "VERIFIED");
        data.put("activeDestroyRequest", "ACKNOWLEDGED");
        data.put("recoveryCause", "UNKNOWN");
        data.put("nativeStatus", "Destroyed"); data.put("residual", observed.residual().name()); data.put("health", observed.health().name());
        data.put("recoveryAttemptStartedAt", recoveryStarted.toString()); data.put("engineObservedAt", engineObservedAt.toString());
        data.put("observedAt", observed.observedAt().toString());
        // This is the adapter assessment, not a claim that the later SUCCESS transaction committed.
        appendObservation(saved, "M1_PHYSICAL_RECOVERY", "DESTROYING", data);
    }

    private void appendObservation(BladeExecutionSnapshot saved, String operation, String requiredStatus,
            tools.jackson.databind.node.ObjectNode data) {
        var owners = jdbc.queryForList("""
                SELECT x.experiment_id
                FROM experiment_executions x JOIN experiments e ON e.id=x.experiment_id
                WHERE x.id=? AND x.status=? AND e.target_id=?
                """, String.class, saved.executionId().toString(), requiredStatus, saved.target().targetId().toString());
        if (owners.size() != 1 || audits == null) throw new IllegalStateException("observation requires matching committed execution");
        audits.append(new com.chaoslab.audit.domain.AuditLog(UUID.randomUUID(), "BLADE_M1_CORE",
                com.chaoslab.audit.domain.AuditOperation.valueOf(operation), UUID.fromString(owners.getFirst()),
                saved.executionId(), saved.target().targetId(), "CPU_LOAD", data.toString(), null,
                com.chaoslab.audit.domain.AuditResult.SUCCESS, null, Instant.now()));
    }

    private static tools.jackson.databind.node.ObjectNode evidenceSubject(BladeExecutionSnapshot saved) {
        var data = json().createObjectNode();
        data.put("version", 1); data.put("nativeUid", saved.uid());
        data.put("nodeId", saved.executorInstanceId()); data.put("stateId", saved.stateDirectoryId());
        data.put("toolSha256", saved.toolSha256()); data.put("containerId", saved.target().containerId());
        data.put("imageId", saved.target().imageId());
        return data;
    }

    private static tools.jackson.databind.json.JsonMapper json() {
        return tools.jackson.databind.json.JsonMapper.builder().enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    }

    /** Keyset page over ALL journal rows, including platform-terminal executions. No external calls. */
    public BladeRecoveryInventory inventory(String after, int limit, Instant now) {
        if (limit < 1 || limit > 100 || (after != null && after.length() > 36)) {
            throw new IllegalArgumentException("invalid inventory page");
        }
        java.util.Objects.requireNonNull(now);
        var rows = jdbc.query("SELECT * FROM blade_execution_snapshots WHERE execution_id > ? "
                        + "ORDER BY execution_id LIMIT ?", (row, index) -> {
                    String id = row.getString("execution_id");
                    BladeExecutionSnapshot snapshot;
                    try {
                        snapshot = map(row, index);
                    } catch (IllegalArgumentException | IllegalStateException | NullPointerException invalid) {
                        // Do not expose raw persisted data/errors, or hide subsequent valid rows.
                        return new BladeRecoveryInventory.Entry(id,
                                BladeRecoveryInventory.Disposition.MANUAL_INTERVENTION,
                                BladeRecoveryInventory.Reason.INVALID_SNAPSHOT, null, null);
                    }
                    boolean missingUid = snapshot.uid() == null;
                    return new BladeRecoveryInventory.Entry(id,
                            missingUid ? BladeRecoveryInventory.Disposition.MANUAL_INTERVENTION
                                    : BladeRecoveryInventory.Disposition.LIVE_VERIFICATION_REQUIRED,
                            missingUid ? BladeRecoveryInventory.Reason.MISSING_UID
                                    : BladeRecoveryInventory.Reason.UNVERIFIED_LIVE_IDENTITY,
                            snapshot.recoveryDeadline(), !now.isBefore(snapshot.recoveryDeadline()));
                }, after == null ? "" : after, limit + 1);
        boolean more = rows.size() > limit;
        var page = rows.subList(0, Math.min(rows.size(), limit));
        return new BladeRecoveryInventory(page, more ? page.getLast().executionId() : null, now);
    }

    private BladeExecutionSnapshot map(ResultSet row, int index) throws SQLException {
        if (!"DOCKER_CPU_V1".equals(row.getString("snapshot_format")) && !"CRI_CPU_V1".equals(row.getString("snapshot_format"))) {
            throw new IllegalStateException("unsupported Blade snapshot format");
        }
        return new BladeExecutionSnapshot(UUID.fromString(row.getString("execution_id")),
                new VerifiedDockerTarget(UUID.fromString(row.getString("target_id")), row.getString("container_id"),
                        row.getString("image_id"), row.getString("metrics_job")),
                row.getString("executor_instance_id"), row.getString("state_directory_id"), row.getString("tool_version"),
                row.getString("tool_sha256"), row.getInt("cpu_percent"), row.getInt("duration_seconds"),
                row.getTimestamp("recorded_at").toInstant(), row.getTimestamp("recovery_deadline").toInstant(),
                row.getString("blade_uid"), row.getString("snapshot_format"));
    }
}
