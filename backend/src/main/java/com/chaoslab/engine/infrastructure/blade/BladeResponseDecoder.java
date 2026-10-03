package com.chaoslab.engine.infrastructure.blade;

import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.Set;

/** Pure versioned dialect: locked api3 CRI by default; explicit legacy snapshot decoding. */
public final class BladeResponseDecoder {
    private final boolean cri;
    public BladeResponseDecoder() { this(BladeExecutionSnapshot.CRI_CPU_V1); }
    public BladeResponseDecoder(String snapshotFormat) {
        if (!BladeExecutionSnapshot.CRI_CPU_V1.equals(snapshotFormat)
                && !BladeExecutionSnapshot.DOCKER_CPU_V1.equals(snapshotFormat))
            throw new IllegalArgumentException("unknown snapshot format");
        cri = BladeExecutionSnapshot.CRI_CPU_V1.equals(snapshotFormat);
    }
    private static final int MAX_BYTES = 64 * 1024;
    private static final Set<String> ENVELOPE = Set.of("code", "success", "error", "result");
    private static final Set<String> STATUS_FIELDS = Set.of(
            "Uid", "Command", "SubCommand", "Flag", "Status", "Error", "CreateTime", "UpdateTime");
    private static final JsonMapper JSON = JsonMapper.builder(JsonFactory.builder()
                    .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(16)
                            .maxStringLength(MAX_BYTES).maxNumberLength(32).build())
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    public enum Failure { TRANSPORT_UNCERTAIN, INVALID_RESPONSE, TOOL_REPORTED_FAILURE, DATA_NOT_FOUND }

    /** Never contains raw process output, which may contain secrets or log-control characters. */
    public static final class DecodeException extends IllegalArgumentException {
        private final Failure failure;

        private DecodeException(Failure failure) {
            super("Blade response rejected: " + failure);
            this.failure = failure;
        }

        public Failure failure() { return failure; }
    }

    public record CreateReceipt(String uid) { }

    /** Feed to the recovery contract with independently verified node and target identity. */
    public record StatusObservation(String uid, BladeRecoveryContract.ObservedStatus status) { }

    /** CLI acknowledgement only, deliberately contains no recovered flag. */
    public enum DestroyAcknowledgement { REQUIRES_STATUS_CHECK }

    public CreateReceipt decodeCreate(ProcessRunResult process) {
        return new CreateReceipt(uid(envelope(process, true).get("result")));
    }

    public StatusObservation decodeStatus(ProcessRunResult process) {
        JsonNode result = envelope(process).get("result");
        if (result == null || !result.isObject() || !result.propertyNames().equals(STATUS_FIELDS)) {
            throw rejected(Failure.INVALID_RESPONSE);
        }
        for (String field : STATUS_FIELDS) {
            if (!result.get(field).isString()) throw rejected(Failure.INVALID_RESPONSE);
        }
        if (!(cri ? "cri" : "docker").equals(result.get("Command").stringValue())
                || !(cri ? "cpu fullload" : "cpu load").equals(result.get("SubCommand").stringValue())) {
            throw rejected(Failure.INVALID_RESPONSE);
        }
        String uid = uid(result.get("Uid"));
        BladeRecoveryContract.ObservedStatus status = switch (result.get("Status").stringValue()) {
            case "Created" -> BladeRecoveryContract.ObservedStatus.CREATED;
            case "Success" -> BladeRecoveryContract.ObservedStatus.SUCCESS;
            case "Error" -> BladeRecoveryContract.ObservedStatus.ERROR;
            case "Destroyed" -> BladeRecoveryContract.ObservedStatus.DESTROYED;
            default -> throw rejected(Failure.INVALID_RESPONSE);
        };
        return new StatusObservation(uid, status);
    }

    public DestroyAcknowledgement decodeDestroy(ProcessRunResult process) {
        JsonNode result = envelope(process).get("result");
        // Upstream returns either an experiment model or an already-destroyed description.
        // Neither is evidence that this request's saved UID has reached Destroyed.
        if (result == null || !(result.isObject() && !result.isEmpty()
                || result.isString() && !result.stringValue().isBlank())) {
            throw rejected(Failure.INVALID_RESPONSE);
        }
        if (cri) {
            if (result.isString()) {
                if (!result.stringValue().matches("command: cri cpu fullload .+, destroy time: .+"))
                    throw rejected(Failure.INVALID_RESPONSE);
            } else {
                Set<String> required = Set.of("target", "action", "flags", "ActionProcessHang");
                // destroy.go rebuilds via ConvertCommandsToExpModel: scope/programs/categories omitted,
                // ActionProcessHang defaults false. This is not the create-time model.
                if (!result.propertyNames().equals(required)
                        || !"cpu".equals(result.path("target").asString())
                        || !"fullload".equals(result.path("action").asString())
                        || !result.path("flags").isObject() || result.path("flags").isEmpty()
                        || !result.path("ActionProcessHang").isBoolean() || result.path("ActionProcessHang").booleanValue())
                    throw rejected(Failure.INVALID_RESPONSE);
                for (JsonNode flag : result.get("flags"))
                    if (!flag.isString()) throw rejected(Failure.INVALID_RESPONSE);
            }
        }
        return DestroyAcknowledgement.REQUIRES_STATUS_CHECK;
    }

    private JsonNode envelope(ProcessRunResult process) {
        return envelope(process, false);
    }

    private JsonNode envelope(ProcessRunResult process, boolean create) {
        if (process == null || process.outcome() != (create ? ProcessRunResult.Outcome.HANDOFF : ProcessRunResult.Outcome.EXITED)
                || !Integer.valueOf(0).equals(process.exitCode()) || process.cleanupComplete() == create) {
            throw rejected(Failure.TRANSPORT_UNCERTAIN);
        }
        String stdout = process.stdout();
        String stderr = process.stderr();
        if (stdout == null || stderr == null || stdout.isBlank()
                || stdout.length() > MAX_BYTES || stderr.length() > MAX_BYTES
                || stdout.getBytes(StandardCharsets.UTF_8).length
                + stderr.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES
                || !stderr.isBlank() || stdout.indexOf('\uFFFD') >= 0) {
            throw rejected(Failure.INVALID_RESPONSE);
        }
        JsonNode root;
        try {
            root = JSON.readTree(stdout);
        } catch (RuntimeException exception) {
            throw rejected(Failure.INVALID_RESPONSE);
        }
        if (root == null || !root.isObject() || !ENVELOPE.containsAll(root.propertyNames())
                || !root.has("code") || !root.get("code").isIntegralNumber()
                || !root.get("code").canConvertToInt()
                || !root.has("success") || !root.get("success").isBoolean()
                || root.has("error") && !root.get("error").isString()) {
            throw rejected(Failure.INVALID_RESPONSE);
        }
        int code = root.get("code").intValue();
        boolean success = root.get("success").booleanValue();
        if (success != (code == 200) || code <= 0) throw rejected(Failure.INVALID_RESPONSE);
        if (!success) {
            throw rejected(code == 67002 ? Failure.DATA_NOT_FOUND : Failure.TOOL_REPORTED_FAILURE);
        }
        if (root.has("error") && !root.get("error").stringValue().isEmpty()) {
            throw rejected(Failure.INVALID_RESPONSE);
        }
        return root;
    }

    private String uid(JsonNode node) {
        if (node == null || !node.isString() || !node.stringValue().matches(cri ? "[0-9a-f]{16}" : "[0-9a-f]{16,64}")) {
            throw rejected(Failure.INVALID_RESPONSE);
        }
        return node.stringValue();
    }

    private DecodeException rejected(Failure failure) { return new DecodeException(failure); }
}
