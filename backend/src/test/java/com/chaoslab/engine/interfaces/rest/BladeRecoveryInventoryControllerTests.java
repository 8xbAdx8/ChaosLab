package com.chaoslab.engine.interfaces.rest;

import com.chaoslab.engine.infrastructure.blade.BladeRecoveryInventory;
import com.chaoslab.engine.infrastructure.blade.JdbcBladeExecutionJournal;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class BladeRecoveryInventoryControllerTests {
    private final JdbcBladeExecutionJournal journal = mock(JdbcBladeExecutionJournal.class);
    private final Instant now = Instant.parse("2026-10-02T00:00:00Z");
    private final org.springframework.test.web.servlet.MockMvc mvc = MockMvcBuilders.standaloneSetup(
            new BladeRecoveryInventoryController(journal, Clock.fixed(now, ZoneOffset.UTC))).build();
    private static final String PATH = "/api/v1/blade/recovery-inventory";

    @Test
    void getUsesFixedClockAndDefaultBound() throws Exception {
        when(journal.inventory(null, 50, now)).thenReturn(new BladeRecoveryInventory(List.of(), null, now));
        mvc.perform(get(PATH)).andExpect(status().isOk()).andExpect(jsonPath("$.entries").isEmpty());
        verify(journal).inventory(null, 50, now);
        verifyNoMoreInteractions(journal);
    }

    @Test
    void cursorAndLimitArePassedWithoutChangingEvidence() throws Exception {
        when(journal.inventory("cursor", 1, now)).thenReturn(new BladeRecoveryInventory(List.of(), null, now));
        mvc.perform(get(PATH).param("after", "cursor").param("limit", "1")).andExpect(status().isOk());
        verify(journal).inventory("cursor", 1, now);
        verifyNoMoreInteractions(journal);
    }

    @Test
    void invalidRequestsAndWritesNeverReachJournal() throws Exception {
        for (String limit : List.of("0", "101", "-1", "oops")) {
            mvc.perform(get(PATH).param("limit", limit)).andExpect(status().isBadRequest());
        }
        mvc.perform(get(PATH).param("after", "x".repeat(37))).andExpect(status().isBadRequest());
        mvc.perform(post(PATH)).andExpect(status().isMethodNotAllowed());
        verifyNoInteractions(journal);
    }
}
