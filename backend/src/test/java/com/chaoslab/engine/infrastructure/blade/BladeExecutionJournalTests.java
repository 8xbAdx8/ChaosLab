package com.chaoslab.engine.infrastructure.blade;

import com.chaoslab.engine.application.model.ReadyExperimentRequest;
import com.chaoslab.execution.application.port.ExperimentExecutionRepository;
import com.chaoslab.execution.domain.ExperimentExecution;
import com.chaoslab.experiment.application.port.ExperimentRepository;
import com.chaoslab.experiment.domain.Experiment;
import com.chaoslab.safety.application.model.VerifiedDockerTarget;
import com.chaoslab.target.application.port.TargetRepository;
import com.chaoslab.target.domain.Target;
import com.chaoslab.target.domain.TargetEnvironment;
import com.chaoslab.target.domain.TargetType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

/** Real commits, no enclosing test transaction. No Blade executable or Docker daemon required. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:blade-journal;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE")
class BladeExecutionJournalTests {
    private final String node = "node-" + UUID.randomUUID();
    @Autowired JdbcBladeExecutionJournal journal;
    @Autowired ExperimentExecutionRepository executions;
    @Autowired ExperimentRepository experiments;
    @Autowired TargetRepository targets;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    void commitsAndReloadsSnapshotAndWriteOnceRecoveryHandle() {
        var intent = intent();
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            journal.recordIntent(intent);
            tx.setRollbackOnly();
        });
        var stored = journal.findByExecutionId(intent.executionId()).orElseThrow();
        assertThat(stored).isEqualTo(intent);
        assertThat(stored.recoveryHandle()).isEmpty();
        assertThat(stored.createArguments()).containsExactlyElementsOf(intent.createArguments());
        var handle = handle(intent, "0123456789abcdef");
        journal.recordUid(handle, intent.stateDirectoryId());
        journal.recordUid(handle, intent.stateDirectoryId());
        // A newly constructed reader must see the persisted evidence, not an object cache.
        var reloaded = new JdbcBladeExecutionJournal(jdbc).findByExecutionId(intent.executionId()).orElseThrow();
        assertThat(reloaded.recoveryHandle()).contains(handle);
        assertThat(reloaded.recoveryDeadline()).isEqualTo(intent.recordedAt().plusSeconds(30));
        assertThatThrownBy(() -> journal.recordUid(handle(intent, "fedcba9876543210"), intent.stateDirectoryId()))
                .isInstanceOf(RuntimeException.class);
        assertThat(journal.findByExecutionId(intent.executionId()).orElseThrow().uid()).isEqualTo(handle.uid());
    }

    @Test
    void duplicateIntentCannotOverwriteOriginalEvidence() {
        var intent = intent();
        journal.recordIntent(intent);
        assertThatThrownBy(() -> journal.recordIntent(intent)).isInstanceOf(DataAccessException.class);
        assertThat(journal.findByExecutionId(intent.executionId())).contains(intent);
    }

    @Test
    void rejectsMissingIntentAndMismatchedRecoveryOwnership() {
        var intent = intent();
        var handle = handle(intent, "0123456789abcdef");
        assertThatThrownBy(() -> journal.recordUid(handle, "state-1")).isInstanceOf(RuntimeException.class);
        journal.recordIntent(intent);
        assertThatThrownBy(() -> journal.recordUid(handle, "different-state")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> journal.recordUid(new BladeRecoveryHandle(intent.executionId(), "different-node",
                intent.target(), handle.uid()), "state-1")).isInstanceOf(RuntimeException.class);
        var otherTarget = new VerifiedDockerTarget(intent.target().targetId(), "c".repeat(64),
                intent.target().imageId(), "order-service");
        assertThatThrownBy(() -> journal.recordUid(new BladeRecoveryHandle(intent.executionId(),
                intent.executorInstanceId(), otherTarget, handle.uid()), "state-1")).isInstanceOf(RuntimeException.class);
        assertThat(journal.findByExecutionId(intent.executionId()).orElseThrow().recoveryHandle()).isEmpty();
    }

    @Test
    void rejectsIntentForAnExecutionThatHasLeftPreparing() {
        var intent = intent();
        var execution = executions.findById(intent.executionId()).orElseThrow();
        executions.update(execution.markCreateUncertain());
        assertThatThrownBy(() -> journal.recordIntent(intent)).isInstanceOf(RuntimeException.class);
        assertThat(journal.findByExecutionId(intent.executionId())).isEmpty();
    }

    @Test
    void sameNativeUidCannotBelongToTwoExecutionsOnOneStateDirectory() {
        var first = intent();
        var second = intent();
        journal.recordIntent(first);
        journal.recordIntent(second);
        journal.recordUid(handle(first, "0123456789abcdef"), "state-1");
        assertThatThrownBy(() -> journal.recordUid(handle(second, "0123456789abcdef"), "state-1"))
                .isInstanceOf(DataAccessException.class);
        assertThat(journal.findByExecutionId(second.executionId()).orElseThrow().uid()).isNull();
    }

    @Test
    void competingUidWritesCannotReplaceEachOther() throws Exception {
        var intent = intent();
        journal.recordIntent(intent);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> tryBind(intent, "1111111111111111"));
            var second = workers.submit(() -> tryBind(intent, "2222222222222222"));
            assertThat(first.get(5, TimeUnit.SECONDS) ^ second.get(5, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(journal.findByExecutionId(intent.executionId()).orElseThrow().uid())
                .isIn("1111111111111111", "2222222222222222");
    }

    @Test
    void rejectsUnsupportedPersistedFormatRatherThanGuessingCommands() {
        var intent = intent();
        journal.recordIntent(intent);
        jdbc.update("UPDATE blade_execution_snapshots SET snapshot_format = 'UNKNOWN' WHERE execution_id = ?",
                intent.executionId().toString());
        assertThatThrownBy(() -> journal.findByExecutionId(intent.executionId())).isInstanceOf(RuntimeException.class);
    }

    @Test
    void snapshotRejectsInvalidProvenanceLimitsAndDeadline() {
        var intent = intent();
        assertThatThrownBy(() -> new BladeExecutionSnapshot(intent.executionId(), intent.target(), "--node", "state-1",
                "1.7.4", "d".repeat(64), 20, 30, intent.recordedAt(), intent.recoveryDeadline(), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BladeExecutionSnapshot(intent.executionId(), intent.target(), "node-1", "state-1",
                "1.7.4", "invalid-sha", 20, 30, intent.recordedAt(), intent.recoveryDeadline(), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BladeExecutionSnapshot(intent.executionId(), intent.target(), "node-1", "state-1",
                "1.7.4", "d".repeat(64), 41, 30, intent.recordedAt(), intent.recoveryDeadline(), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BladeExecutionSnapshot(intent.executionId(), intent.target(), "node-1", "state-1",
                "1.7.4", "d".repeat(64), 20, 30, intent.recordedAt(), intent.recordedAt(), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private boolean tryBind(BladeExecutionSnapshot intent, String uid) {
        try {
            journal.recordUid(handle(intent, uid), "state-1");
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    @Test
    void inventorySurvivesNewReaderAndIsReadOnlyWithBoundedPages() {
        var missing = intent();
        var bound = intent();
        var corrupt = intent();
        journal.recordIntent(missing);
        journal.recordIntent(bound);
        journal.recordIntent(corrupt);
        journal.recordUid(handle(bound, "abcdef0123456789"), "state-1");
        // Even a platform-terminal row must not disappear: it is not proof of native recovery.
        jdbc.update("UPDATE experiment_executions SET status = 'FAILED' WHERE id = ?", bound.executionId().toString());
        jdbc.update("UPDATE blade_execution_snapshots SET snapshot_format = 'UNKNOWN' WHERE execution_id = ?",
                corrupt.executionId().toString());
        var beforeSnapshots = jdbc.queryForList("SELECT * FROM blade_execution_snapshots ORDER BY execution_id");
        var beforeExecutions = jdbc.queryForList("SELECT * FROM experiment_executions ORDER BY id");
        var now = bound.recoveryDeadline();
        var entries = new java.util.ArrayList<BladeRecoveryInventory.Entry>();
        var reader = new JdbcBladeExecutionJournal(jdbc);
        String cursor = null;
        int pages = 0;
        do {
            var page = reader.inventory(cursor, 2, now);
            assertThat(page.entries()).hasSizeLessThanOrEqualTo(2);
            assertThat(page.checkedAt()).isEqualTo(now);
            entries.addAll(page.entries());
            cursor = page.nextCursor();
            assertThat(++pages).isLessThan(100);
        } while (cursor != null);
        assertThat(entries).extracting(BladeRecoveryInventory.Entry::executionId).doesNotHaveDuplicates().isSorted();
        assertThat(entries).contains(
                new BladeRecoveryInventory.Entry(missing.executionId().toString(),
                        BladeRecoveryInventory.Disposition.MANUAL_INTERVENTION,
                        BladeRecoveryInventory.Reason.MISSING_UID, missing.recoveryDeadline(),
                        !now.isBefore(missing.recoveryDeadline())),
                new BladeRecoveryInventory.Entry(bound.executionId().toString(),
                        BladeRecoveryInventory.Disposition.LIVE_VERIFICATION_REQUIRED,
                        BladeRecoveryInventory.Reason.UNVERIFIED_LIVE_IDENTITY, bound.recoveryDeadline(), true),
                new BladeRecoveryInventory.Entry(corrupt.executionId().toString(),
                        BladeRecoveryInventory.Disposition.MANUAL_INTERVENTION,
                        BladeRecoveryInventory.Reason.INVALID_SNAPSHOT, null, null));
        assertThat(jdbc.queryForList("SELECT * FROM blade_execution_snapshots ORDER BY execution_id")).isEqualTo(beforeSnapshots);
        assertThat(jdbc.queryForList("SELECT * FROM experiment_executions ORDER BY id")).isEqualTo(beforeExecutions);
    }

    @Test
    void inventoryRejectsInvalidBoundsAndReturnsEmptyFinalPage() {
        assertThatThrownBy(() -> journal.inventory(null, 0, Instant.now())).isInstanceOf(DataAccessException.class)
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> journal.inventory(null, 101, Instant.now())).isInstanceOf(DataAccessException.class)
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> journal.inventory("x".repeat(37), 1, Instant.now())).isInstanceOf(DataAccessException.class)
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
        var page = journal.inventory("ffffffff-ffff-ffff-ffff-ffffffffffff", 100, Instant.now());
        assertThat(page.entries()).isEmpty();
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    void inventoryDoesNotTreatFutureDeadlineAsRecovered() {
        var saved = intent();
        journal.recordIntent(saved);
        journal.recordUid(handle(saved, "abcdef0123456789"), "state-1");
        var entry = journal.inventory(null, 100, saved.recordedAt()).entries().stream()
                .filter(item -> item.executionId().equals(saved.executionId().toString())).findFirst().orElseThrow();
        assertThat(entry.overdue()).isFalse();
        assertThat(entry.disposition()).isEqualTo(BladeRecoveryInventory.Disposition.LIVE_VERIFICATION_REQUIRED);
    }

    private BladeRecoveryHandle handle(BladeExecutionSnapshot intent, String uid) {
        return new BladeRecoveryHandle(intent.executionId(), intent.executorInstanceId(), intent.target(), uid, intent.format());
    }

    @Test
    void storedFormatsStayDistinctAndCannotBindCrossDialectUid() {
        var cri = intent();
        journal.recordIntent(cri);
        assertThat(journal.findByExecutionId(cri.executionId()).orElseThrow().format()).isEqualTo("CRI_CPU_V1");
        assertThatThrownBy(() -> journal.recordUid(new BladeRecoveryHandle(cri.executionId(),
                cri.executorInstanceId(), cri.target(), "0123456789abcdef"), cri.stateDirectoryId()))
                .isInstanceOf(Exception.class);
        var template = intent();
        var legacy = new BladeExecutionSnapshot(template.executionId(), template.target(), template.executorInstanceId(),
                template.stateDirectoryId(), template.toolVersion(), template.toolSha256(), template.cpuPercent(),
                template.durationSeconds(), template.recordedAt(), template.recoveryDeadline(), null);
        journal.recordIntent(legacy);
        journal.recordUid(handle(legacy, "a".repeat(32)), legacy.stateDirectoryId());
        var read = journal.findByExecutionId(legacy.executionId()).orElseThrow();
        assertThat(read.format()).isEqualTo("DOCKER_CPU_V1");
        assertThat(read.createArguments()).containsSubsequence("create", "docker", "cpu", "load");
        assertThat(read.recoveryHandle().orElseThrow().statusArguments()).doesNotContain("--type");
        jdbc.update("UPDATE blade_execution_snapshots SET snapshot_format = 'FUTURE_V9' WHERE execution_id = ?",
                legacy.executionId().toString());
        assertThatThrownBy(() -> journal.findByExecutionId(legacy.executionId())).isInstanceOf(Exception.class);
    }

    private BladeExecutionSnapshot intent() {
        UUID targetId = UUID.randomUUID();
        targets.save(Target.register(targetId, "journal-" + targetId, TargetType.DOCKER_CONTAINER,
                TargetEnvironment.CHAOS_LAB));
        var experiment = experiments.insert(Experiment.create(UUID.randomUUID(), "journal test", "available", targetId,
                UUID.fromString("00000000-0000-0000-0000-000000000101"), 30, "{\"percent\":20}").validate().ready());
        var execution = executions.insert(ExperimentExecution.prepare(UUID.randomUUID(), experiment.getId(), 1, "key", Instant.now()));
        var target = new VerifiedDockerTarget(targetId, "a".repeat(64), "sha256:" + "b".repeat(64), "order-service");
        var plan = DockerCpuCommandPlan.from(new ReadyExperimentRequest(execution.getId(), experiment.getId(), targetId,
                "CPU_LOAD", 30, "{\"percent\":20}", target), target);
        return BladeExecutionSnapshot.intent(plan, node, "state-1", "1.7.4", "d".repeat(64), Instant.now());
    }
}
