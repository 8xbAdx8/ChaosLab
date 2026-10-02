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

/** Storage only. Neither the Fake engine nor a real executor calls this journal yet. */
@Repository
@Transactional(readOnly = true)
public class JdbcBladeExecutionJournal {
    private final JdbcTemplate jdbc;

    public JdbcBladeExecutionJournal(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** A new immutable intent must commit before dispatch; duplicate inserts fail closed. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordIntent(BladeExecutionSnapshot snapshot) {
        if (snapshot.uid() != null) throw new IllegalArgumentException("intent must not contain a UID");
        int inserted = jdbc.update("""
                INSERT INTO blade_execution_snapshots
                (execution_id, snapshot_format, target_id, container_id, image_id, metrics_job,
                 executor_instance_id, state_directory_id, tool_version, tool_sha256,
                 cpu_percent, duration_seconds, recorded_at, recovery_deadline)
                SELECT x.id, 'DOCKER_CPU_V1', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
                FROM experiment_executions x JOIN experiments e ON e.id = x.experiment_id
                JOIN targets t ON t.id = e.target_id JOIN fault_scenarios s ON s.id = e.scenario_id
                WHERE x.id = ? AND x.status = 'PREPARING' AND e.target_id = ?
                AND t.target_type = 'DOCKER_CONTAINER' AND s.code = 'CPU_LOAD' AND e.duration_seconds = ?
                """, snapshot.target().targetId().toString(), snapshot.target().containerId(), snapshot.target().imageId(),
                snapshot.target().metricsJob(), snapshot.executorInstanceId(), snapshot.stateDirectoryId(),
                snapshot.toolVersion(), snapshot.toolSha256(), snapshot.cpuPercent(), snapshot.durationSeconds(),
                Timestamp.from(snapshot.recordedAt()), Timestamp.from(snapshot.recoveryDeadline()),
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
                || !saved.stateDirectoryId().equals(stateDirectoryId) || !saved.target().equals(handle.target())) {
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
        if (!"DOCKER_CPU_V1".equals(row.getString("snapshot_format"))) {
            throw new IllegalStateException("unsupported Blade snapshot format");
        }
        return new BladeExecutionSnapshot(UUID.fromString(row.getString("execution_id")),
                new VerifiedDockerTarget(UUID.fromString(row.getString("target_id")), row.getString("container_id"),
                        row.getString("image_id"), row.getString("metrics_job")),
                row.getString("executor_instance_id"), row.getString("state_directory_id"), row.getString("tool_version"),
                row.getString("tool_sha256"), row.getInt("cpu_percent"), row.getInt("duration_seconds"),
                row.getTimestamp("recorded_at").toInstant(), row.getTimestamp("recovery_deadline").toInstant(),
                row.getString("blade_uid"));
    }
}
