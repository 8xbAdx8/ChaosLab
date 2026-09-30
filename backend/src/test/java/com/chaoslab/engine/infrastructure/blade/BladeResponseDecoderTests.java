package com.chaoslab.engine.infrastructure.blade;

import com.chaoslab.safety.application.model.VerifiedDockerTarget;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;

import static com.chaoslab.engine.infrastructure.blade.BladeResponseDecoder.Failure.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BladeResponseDecoderTests {
    private static final String UID = "0123456789abcdef";
    private static final String CREATE = "{\"code\":200,\"success\":true,\"result\":\"" + UID + "\"}";
    private final BladeResponseDecoder decoder = new BladeResponseDecoder();

    @Test
    void readsCreateUidWithoutClaimingRecovery() {
        assertThat(decoder.decodeCreate(ok(" \n" + CREATE + "\n")).uid()).isEqualTo(UID);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Created", "Success", "Error", "Destroyed"})
    void readsExactCandidateStatusDialect(String status) {
        var observation = decoder.decodeStatus(ok(status(status)));
        assertThat(observation.uid()).isEqualTo(UID);
        assertThat(observation.status().name()).isEqualTo(status.toUpperCase(java.util.Locale.ROOT));
    }

    @Test
    void mismatchingUidCannotConfirmRecovery() {
        var target = new VerifiedDockerTarget(UUID.randomUUID(), "a".repeat(64),
                "sha256:" + "b".repeat(64), "order-service");
        var handle = new BladeRecoveryHandle(UUID.randomUUID(), "runner-1", target, UID);
        var observation = decoder.decodeStatus(ok(status("Destroyed").replace(UID, "fedcba9876543210")));
        assertThat(BladeRecoveryContract.decide(handle, "runner-1", target,
                observation.uid(), observation.status()))
                .isEqualTo(BladeRecoveryContract.Decision.MANUAL_INTERVENTION);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"already destroyed description\"", "{\"target\":\"cpu\"}"})
    void destroyAcknowledgementAlwaysRequiresStatusCheck(String result) {
        assertThat(decoder.decodeDestroy(ok("{\"code\":200,\"success\":true,\"result\":" + result + "}")))
                .isEqualTo(BladeResponseDecoder.DestroyAcknowledgement.REQUIRES_STATUS_CHECK);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "null", "[]", "{}", "true", "not-json",
            "{\"code\":\"200\",\"success\":true}", "{\"code\":200.0,\"success\":true}",
            "{\"code\":200,\"success\":\"true\"}", "{\"code\":200,\"success\":false}",
            "{\"code\":67002,\"success\":true}", "{\"code\":200,\"success\":true,\"result\":null}",
            "{\"code\":200,\"success\":true,\"result\":123}",
            "{\"code\":200,\"success\":true,\"result\":\"--all\"}",
            "{\"code\":200,\"success\":true,\"result\":\"ABCDEF0123456789\"}"})
    void rejectsMalformedOrCoercedCreateResponses(String value) {
        reject(ok(value), INVALID_RESPONSE);
    }

    @Test
    void rejectsDuplicatesTrailingDocumentsLogsAndUnknownFields() {
        for (String value : new String[] { CREATE + CREATE, "log\n" + CREATE,
                CREATE.replace("200", "200,\"code\":200"),
                CREATE.replace("200", "200,\"extra\":1"),
                CREATE.replace("200", "200,\"error\":\"failure\""),
                CREATE.replace("200", "200,\"error\":null"),
                CREATE.replace(UID, "\uFFFD" + UID) }) {
            reject(ok(value), INVALID_RESPONSE);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"destroyed", "DESTROYED", "Running", "Revoked", "Unknown", ""})
    void unknownStatusesNeverBecomeDestroyed(String value) {
        assertThatThrownBy(() -> decoder.decodeStatus(ok(status(value))))
                .isInstanceOf(BladeResponseDecoder.DecodeException.class);
    }

    @Test
    void rejectsWrongStatusShapeAndPreparationRecords() {
        for (String value : new String[] {
                status("Destroyed").replace("\"Uid\"", "\"uid\""),
                status("Destroyed").replace("\"Status\":\"Destroyed\"", "\"Status\":true"),
                status("Destroyed").replace("\"Status\":", "\"Status\":\"Success\",\"Status\":"),
                status("Destroyed").replace("docker", "jvm"),
                status("Destroyed").replace("cpu load", "network delay"),
                status("Destroyed").replace("\"Flag\":\"\",", ""),
                "{\"code\":200,\"success\":true,\"result\":[]}" }) {
            assertThatThrownBy(() -> decoder.decodeStatus(ok(value)))
                    .isInstanceOf(BladeResponseDecoder.DecodeException.class);
        }
    }

    @ParameterizedTest
    @EnumSource(value = ProcessRunResult.Outcome.class, names = "EXITED", mode = EnumSource.Mode.EXCLUDE)
    void incompleteTransportCannotSupplyASuccess(ProcessRunResult.Outcome outcome) {
        var process = new ProcessRunResult(outcome, 0, CREATE, "", true);
        reject(process, TRANSPORT_UNCERTAIN);
        assertThatThrownBy(() -> decoder.decodeStatus(process)).isInstanceOf(BladeResponseDecoder.DecodeException.class);
        assertThatThrownBy(() -> decoder.decodeDestroy(process)).isInstanceOf(BladeResponseDecoder.DecodeException.class);
    }

    @Test
    void rejectsNonzeroExitMissingExitAndIncompleteCleanup() {
        reject(new ProcessRunResult(ProcessRunResult.Outcome.EXITED, 1, CREATE, "", true), TRANSPORT_UNCERTAIN);
        reject(new ProcessRunResult(ProcessRunResult.Outcome.EXITED, null, CREATE, "", true), TRANSPORT_UNCERTAIN);
        reject(new ProcessRunResult(ProcessRunResult.Outcome.EXITED, 0, CREATE, "", false), TRANSPORT_UNCERTAIN);
        reject(null, TRANSPORT_UNCERTAIN);
    }

    @Test
    void refusesAmbiguousStderrOversizeAndExcessiveNesting() {
        reject(new ProcessRunResult(ProcessRunResult.Outcome.EXITED, 0, CREATE, "warning", true), INVALID_RESPONSE);
        reject(ok(CREATE + " ".repeat(65536)), INVALID_RESPONSE);
        reject(ok(CREATE.replace(UID, "汉".repeat(23000))), INVALID_RESPONSE);
        reject(ok("[".repeat(20) + "0" + "]".repeat(20)), INVALID_RESPONSE);
    }

    @Test
    void recognizesNotFoundOnlyAsFailureAndNeverLeaksRawError() {
        reject(ok("{\"code\":67002,\"success\":false,\"error\":\"secret-token\"}"), DATA_NOT_FOUND);
        reject(ok("{\"code\":67001,\"success\":false,\"error\":\"secret-token\"}"), TOOL_REPORTED_FAILURE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "false", "[]", "{}", "\"\""})
    void emptyOrWrongDestroyPayloadIsNotAnAcknowledgement(String value) {
        assertThatThrownBy(() -> decoder.decodeDestroy(ok("{\"code\":200,\"success\":true,\"result\":" + value + "}")))
                .isInstanceOf(BladeResponseDecoder.DecodeException.class);
    }

    private void reject(ProcessRunResult process, BladeResponseDecoder.Failure failure) {
        assertThatThrownBy(() -> decoder.decodeCreate(process))
                .isInstanceOfSatisfying(BladeResponseDecoder.DecodeException.class,
                        exception -> assertThat(exception.failure()).isEqualTo(failure))
                .hasMessage("Blade response rejected: " + failure);
    }

    private static ProcessRunResult ok(String stdout) {
        return new ProcessRunResult(ProcessRunResult.Outcome.EXITED, 0, stdout, "", true);
    }

    // Synthetic fixture shaped from v1.7.4 data/experiment.go, not captured live output.
    private static String status(String status) {
        return "{\"code\":200,\"success\":true,\"result\":{\"Uid\":\"" + UID
                + "\",\"Command\":\"docker\",\"SubCommand\":\"cpu load\",\"Flag\":\"\",\"Status\":\""
                + status + "\",\"Error\":\"\",\"CreateTime\":\"\",\"UpdateTime\":\"\"}}";
    }
}
