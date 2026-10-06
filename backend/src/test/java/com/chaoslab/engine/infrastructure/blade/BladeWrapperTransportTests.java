package com.chaoslab.engine.infrastructure.blade;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class BladeWrapperTransportTests {
    private static final String UID = "0123456789abcdef";
    private static final String HANDOFF = """
            {"version":1,"code":"OK","outcome":"HANDOFF","exitCode":0,
             "cleanupComplete":false,"handoff":true,"nativeUid":"0123456789abcdef",
             "response":{"code":200,"success":true,"result":"0123456789abcdef"}}
            """;

    @Test void translatesOnlyMatchingPrivilegedHandoff() {
        var result = BladeProcessChannel.decodeWrapper(HANDOFF, "create-cpu", UID);
        assertThat(result.outcome()).isEqualTo(ProcessRunResult.Outcome.HANDOFF);
        assertThat(result.cleanupComplete()).isFalse();
        assertThat(new BladeResponseDecoder(BladeExecutionSnapshot.CRI_CPU_V1).decodeCreate(result).uid()).isEqualTo(UID);
    }

    @Test void unknownMalformedOrWrongUidNeverClaimsPrivilegedCleanup() {
        for (String value : java.util.List.of("", "{}", HANDOFF + "{}",
                HANDOFF.replace("0123456789abcdef", "fedcba9876543210"),
                HANDOFF.replace("\"cleanupComplete\":false", "\"cleanupComplete\":true"),
                HANDOFF.replace("\"version\":1", "\"version\":1,\"version\":1"))) {
            var result = BladeProcessChannel.decodeWrapper(value, "create-cpu", UID);
            assertThat(result.outcome()).isEqualTo(ProcessRunResult.Outcome.IO_FAILED);
            assertThat(result.cleanupComplete()).isFalse();
        }
    }

    @Test void statusDestroyRejectHandoff() {
        for (String operation : java.util.List.of("status", "destroy")) {
            assertThat(BladeProcessChannel.decodeWrapper(HANDOFF, operation, UID).outcome())
                    .isEqualTo(ProcessRunResult.Outcome.IO_FAILED);
            String strict = HANDOFF.replace("HANDOFF", "EXITED").replace("\"cleanupComplete\":false", "\"cleanupComplete\":true")
                    .replace("\"handoff\":true", "\"handoff\":false");
            assertThat(BladeProcessChannel.decodeWrapper(strict, operation, UID).outcome())
                    .isEqualTo(ProcessRunResult.Outcome.EXITED);
        }
    }
}
