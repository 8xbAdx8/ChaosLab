package com.chaoslab.safety.infrastructure.policy;

import com.chaoslab.safety.application.model.TargetIdentityVerification;
import com.chaoslab.safety.application.model.VerifiedDockerTarget;
import com.chaoslab.safety.application.port.TargetIdentityVerifier;
import com.chaoslab.target.application.port.TargetRepository;
import com.chaoslab.target.domain.Target;
import com.chaoslab.target.domain.TargetEnvironment;
import com.chaoslab.target.domain.TargetType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

@Component
public class LocalDemoTargetIdentityVerifier implements TargetIdentityVerifier {

    private static final int MAX_OUTPUT_BYTES = 16 * 1024;

    private final TargetRepository targetRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final String scriptPath;

    public LocalDemoTargetIdentityVerifier(
            TargetRepository targetRepository,
            ObjectMapper objectMapper,
            Clock clock,
            @Value("${chaoslab.demo.binding-script:}") String scriptPath
    ) {
        this.targetRepository = Objects.requireNonNull(targetRepository);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.clock = Objects.requireNonNull(clock);
        this.scriptPath = Objects.requireNonNull(scriptPath).trim();
    }

    @Override
    public TargetIdentityVerification verify(Target target) {
        return verifyForWindow(target, clock.instant());
    }

    @Override
    public TargetIdentityVerification verifyForWindow(Target target, Instant windowStart) {
        Objects.requireNonNull(target, "target must not be null");
        Objects.requireNonNull(windowStart, "windowStart must not be null");
        if (target.getType() != TargetType.DOCKER_CONTAINER
                || target.getEnvironment() != TargetEnvironment.CHAOS_LAB
                || !target.isEnabled()
                || !"order-service".equals(target.getName())) {
            return TargetIdentityVerification.rejected("only the local Demo order container is allowed");
        }
        Path script;
        try {
            script = Path.of(scriptPath).toAbsolutePath().normalize();
        } catch (RuntimeException exception) {
            return TargetIdentityVerification.rejected("local Demo binding verifier path is invalid");
        }
        if (scriptPath.isBlank() || !Files.isRegularFile(script)) {
            return TargetIdentityVerification.rejected("local Demo binding verifier is not configured");
        }
        long aliases = targetRepository.findAll().stream()
                .filter(candidate -> "order-service".equals(candidate.getName()))
                .count();
        if (aliases != 1) {
            return TargetIdentityVerification.rejected("order-service Target alias is not unique");
        }
        List<String> command = new ArrayList<>();
        if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
            command.add("py");
            command.add("-3");
        } else {
            command.add("python3");
        }
        command.add(script.toString());
        command.addAll(List.of(
                "--target-id", target.getId().toString(),
                "--name", target.getName(),
                "--type", target.getType().name(),
                "--environment", target.getEnvironment().name(),
                "--enabled", "true",
                "--alias-count", "1",
                "--window-start", windowStart.toString()
        ));
        try {
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return TargetIdentityVerification.rejected("local Demo identity check timed out");
            }
            byte[] output = process.getInputStream().readNBytes(MAX_OUTPUT_BYTES + 1);
            if (process.exitValue() != 0 || output.length > MAX_OUTPUT_BYTES) {
                return TargetIdentityVerification.rejected("local Demo identity check failed");
            }
            JsonNode result = objectMapper.readTree(new String(output, StandardCharsets.UTF_8));
            if (!"verified_local_demo".equals(text(result, "status"))
                    || !target.getId().toString().equals(text(result, "target_id"))) {
                return TargetIdentityVerification.rejected("local Demo identity response did not match Target");
            }
            return TargetIdentityVerification.verified(new VerifiedDockerTarget(
                    target.getId(),
                    text(result, "container_id"),
                    text(result, "image_id"),
                    text(result, "job")
            ));
        } catch (IOException | InterruptedException | RuntimeException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return TargetIdentityVerification.rejected("local Demo identity check is unavailable");
        }
    }

    private String text(JsonNode root, String field) {
        if (root == null) {
            return null;
        }
        JsonNode value = root.get(field);
        return value == null ? null : value.asText();
    }
}
