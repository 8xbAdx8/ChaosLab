package com.chaoslab.engine.infrastructure.blade;

import java.time.Instant;
import java.util.List;

/** Evidence inventory only: no entry authorizes a status/destroy call or releases capacity. */
public record BladeRecoveryInventory(List<Entry> entries, String nextCursor, Instant checkedAt) {
    public BladeRecoveryInventory { entries = List.copyOf(entries); }

    public enum Disposition { MANUAL_INTERVENTION, LIVE_VERIFICATION_REQUIRED }
    public enum Reason { INVALID_SNAPSHOT, MISSING_UID, UNVERIFIED_LIVE_IDENTITY }

    // Null deadline/overdue means the persisted evidence could not be validated, not "not overdue".
    public record Entry(String executionId, Disposition disposition, Reason reason,
                        Instant recoveryDeadline, Boolean overdue) {}
}
