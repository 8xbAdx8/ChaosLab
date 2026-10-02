package com.chaoslab.engine.interfaces.rest;

import com.chaoslab.engine.infrastructure.blade.BladeRecoveryInventory;
import com.chaoslab.engine.infrastructure.blade.JdbcBladeExecutionJournal;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;

/** Read-only operator inspection; intentionally not wired to the recovery scheduler. */
@RestController
public class BladeRecoveryInventoryController {
    private final JdbcBladeExecutionJournal journal;
    private final Clock clock;

    public BladeRecoveryInventoryController(JdbcBladeExecutionJournal journal, Clock clock) {
        this.journal = journal;
        this.clock = clock;
    }

    @GetMapping("/api/v1/blade/recovery-inventory")
    public BladeRecoveryInventory inventory(@RequestParam(required = false) String after,
                                             @RequestParam(defaultValue = "50") int limit) {
        if (limit < 1 || limit > 100 || (after != null && after.length() > 36)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid inventory page");
        }
        return journal.inventory(after, limit, clock.instant());
    }
}
