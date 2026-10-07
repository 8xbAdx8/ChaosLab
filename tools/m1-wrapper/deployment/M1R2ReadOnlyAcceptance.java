package com.chaoslab.engine.infrastructure.blade;

import com.chaoslab.ChaosLabApplication;
import com.chaoslab.engine.application.EngineRecoveryException;
import com.chaoslab.engine.application.model.*;
import com.chaoslab.engine.application.port.ChaosEngine;
import com.chaoslab.safety.application.model.*;
import com.chaoslab.safety.application.port.TargetIdentityVerifier;
import com.chaoslab.target.application.port.TargetRepository;
import com.chaoslab.target.domain.*;
import org.flywaydb.core.Flyway;
import org.springframework.boot.SpringApplication;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import java.net.*;
import java.nio.channels.SocketChannel;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** One-shot R2 acceptance entry, NOT a controller, recovery service or alternate engine.
 * The actual Spring beans do only real preflight. A separate in-memory fixture calls the
 * SAME loaded a1cd739 engine class with mocked external ports; no binding/UID is persisted.
 * Mockito and its agent are diagnostic-only sidecars, not changes to backend.jar.
 */
public final class M1R2ReadOnlyAcceptance {
    private static final String COMMIT = "a1cd7394cf945390b257ef2e65007da9c9ac395f";
    private static final Path REPORT = Path.of("/var/lib/chaoslab-backend-m1-r2/java-readonly-result.json");
    private static void require(boolean ok, String label) { if (!ok) throw new IllegalStateException(label); }
    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    private static ProcessRunResult strict(String json) {
        return new ProcessRunResult(ProcessRunResult.Outcome.EXITED, 0, json, "", true);
    }
    private static ProcessRunResult destroyed(String uid) {
        return strict("{\"code\":200,\"success\":true,\"result\":{\"Uid\":\""+uid+"\",\"Command\":\"cri\",\"SubCommand\":\"cpu fullload\",\"Flag\":\"\",\"Status\":\"Destroyed\",\"Error\":\"\",\"CreateTime\":\"\",\"UpdateTime\":\"\"}}");
    }
    private static Map<String,Object> settlingFixture(DockerCpuCommandPlan.Deployment dep, VerifiedDockerTarget identity,
                                                     String mode) throws Exception {
        var target = Target.register(identity.targetId(), "order-service", TargetType.DOCKER_CONTAINER, TargetEnvironment.CHAOS_LAB);
        var targets = mock(TargetRepository.class);
        var verifier = mock(TargetIdentityVerifier.class);
        var local = mock(BladeLocalIdentityVerifier.class);
        var journal = mock(JdbcBladeExecutionJournal.class);
        var channel = mock(BladeProcessChannel.class);
        var executionId = UUID.randomUUID();
        var uid = "0123456789abcdef"; // MEMORY fixture only; never dispatched or persisted
        var now = Instant.now();
        var saved = new BladeExecutionSnapshot(executionId, identity, "m1-executor", "m1-real-state-r2", "api3-identified",
                "c".repeat(64), 10, 10, now, now.plusSeconds(10), uid, BladeExecutionSnapshot.CRI_CPU_V1);
        when(targets.findById(identity.targetId())).thenReturn(Optional.of(target));
        when(verifier.verify(target)).thenReturn(TargetIdentityVerification.verified(identity));
        when(local.verify(saved)).thenReturn(BladeLocalIdentityVerifier.Result.LOCAL_IDENTITY_MATCHED_NEEDS_TARGET_CHECK);
        when(journal.findByExecutionId(executionId)).thenReturn(Optional.of(saved));
        when(channel.destroy(any(), any())).thenReturn(strict("{\"code\":200,\"success\":true,\"result\":\"command: cri cpu fullload --cpu-count=1, destroy time: fixture\"}"));
        var statuses = new AtomicInteger(); var observations = new AtomicInteger();
        when(channel.status(any(), any())).thenAnswer(call -> { statuses.incrementAndGet(); return destroyed(uid); });
        when(channel.observe(any())).thenAnswer(call -> {
            BladeRecoveryHandle handle = call.getArgument(0);
            int n = observations.getAndIncrement();
            if (mode.equals("wrong-uid")) handle = new BladeRecoveryHandle(handle.executionId(), handle.executorInstanceId(), handle.target(), "fedcba9876543210", handle.format());
            if (mode.equals("wrong-node")) handle = new BladeRecoveryHandle(handle.executionId(), "wrong-node", handle.target(), handle.uid(), handle.format());
            if (mode.equals("wrong-target")) handle = new BladeRecoveryHandle(handle.executionId(), handle.executorInstanceId(),
                    new VerifiedDockerTarget(identity.targetId(), "d".repeat(64), identity.imageId(), identity.metricsJob()), handle.uid(), handle.format());
            var residual = mode.equals("present") || (mode.equals("settles") && n == 0)
                    ? BladeRecoveryEvidenceGate.ResidualObservation.PRESENT
                    : mode.equals("unknown") ? BladeRecoveryEvidenceGate.ResidualObservation.UNKNOWN
                    : BladeRecoveryEvidenceGate.ResidualObservation.CLEAR;
            return new BladeProcessChannel.RecoveryObservation(handle, Instant.now(), residual, BladeRecoveryEvidenceGate.HealthObservation.HEALTHY);
        });
        // Public production constructor: fixed 15s window, not the short unit-test seam.
        var engine = new ChaosBladeEngine(dep, "m1-executor", "m1-real-state-r2", "api3-identified", "c".repeat(64),
                targets, verifier, local, journal, channel, Clock.systemUTC());
        var field = ChaosBladeEngine.class.getDeclaredField("settlingWindow"); field.setAccessible(true);
        require(Duration.ofSeconds(15).equals(field.get(engine)), "production 15s window missing");
        String outcome;
        long start = System.nanoTime();
        try {
            require(engine.destroy(new EngineExperimentId("blade-"+executionId)).status() == EngineStatus.DESTROYED, "fixture not verified");
            outcome = "VERIFIED";
        } catch (EngineRecoveryException failure) { outcome = failure.reason().name(); }
        long elapsedMs = (System.nanoTime()-start)/1_000_000;
        String expected = mode.equals("settles") ? "VERIFIED" : mode.equals("present") ? "RECOVERY_RESIDUAL_PRESENT"
                : mode.equals("unknown") ? "RECOVERY_EVIDENCE_INCOMPLETE" : "RECOVERY_IDENTITY_REJECTED";
        require(expected.equals(outcome), "fixture reason mismatch");
        verify(channel, times(1)).destroy(any(), any());
        verify(channel, never()).create(any(), anyString(), any());
        verify(channel, never()).create(any(), any());
        verify(journal, never()).recordIntent(any()); verify(journal, never()).recordUid(any(), anyString());
        if (mode.equals("settles")) {
            require(statuses.get() == 2 && observations.get() == 2 && elapsedMs < 15000, "settling did not reread fresh status/observe");
            var order = inOrder(channel);
            order.verify(channel).destroy(any(), any()); order.verify(channel).status(any(), any()); order.verify(channel).observe(any());
            order.verify(channel).status(any(), any()); order.verify(channel).observe(any());
        } else if (mode.equals("present") || mode.equals("unknown")) {
            require(statuses.get() > 1 && observations.get() > 1 && elapsedMs >= 14500 && elapsedMs < 25000, "fixture not bounded");
        } else require(statuses.get() == 1 && observations.get() == 1, "identity rejection was retried");
        return Map.of("outcome", outcome, "elapsedMs", elapsedMs, "windowSeconds", 15,
                "destroyCalls", 1, "createCalls", 0, "statusCalls", statuses.get(), "observeCalls", observations.get(), "persistedIntentCount", 0);
    }
    public static void main(String[] args) throws Throwable {
        var result = new LinkedHashMap<String,Object>();
        result.put("commit", COMMIT); result.put("realCreateInvoked", false);
        var context = SpringApplication.run(ChaosLabApplication.class, args);
        try {
            require(Runtime.version().feature() == 21, "Java21 required");
            var status = Files.readString(Path.of("/proc/self/status"));
            for (var key : List.of("Uid:", "Gid:")) {
                var line = status.lines().filter(s -> s.startsWith(key)).findFirst().orElseThrow();
                require(Arrays.stream(line.split("\\s+")).skip(1).allMatch((key.equals("Uid:") ? "999" : "987")::equals), "service identity mismatch");
            }
            require(status.lines().filter(s -> s.startsWith("Groups:")).findFirst().orElseThrow().trim().equals("Groups:\t987"), "privileged group inherited");
            for (var cap : List.of("CapEff:", "CapPrm:", "CapAmb:"))
                require(status.lines().filter(s -> s.startsWith(cap)).findFirst().orElseThrow().trim().endsWith("0000000000000000"), "capabilities forbidden");
            result.put("javaVersion", System.getProperty("java.runtime.version")); result.put("pid", ProcessHandle.current().pid());
            result.put("uid",999); result.put("gid",987);
            var env = context.getEnvironment();
            require("blade".equals(env.getProperty("chaoslab.engine")) && "127.0.0.1".equals(env.getProperty("server.address")), "backend boundary mismatch");
            require(context.getBeansOfType(ChaosEngine.class).size() == 1, "wrong engine count");
            require(context.getBeansOfType(com.chaoslab.execution.infrastructure.scheduling.AutomaticExperimentRecoveryJob.class).isEmpty(), "scheduler forbidden");
            var actualEngine = context.getBean(ChaosBladeEngine.class);
            result.put("adapterClass", actualEngine.getClass().getName());
            try (var resource = ChaosBladeEngine.class.getResourceAsStream("ChaosBladeEngine.class")) {
                result.put("engineClassSha256", sha(Objects.requireNonNull(resource).readAllBytes()));
            }
            result.put("engineCodeSource", ChaosBladeEngine.class.getProtectionDomain().getCodeSource().getLocation().toString());
            var jdbc = context.getBean(JdbcTemplate.class);
            try (var conn = Objects.requireNonNull(jdbc.getDataSource()).getConnection()) {
                require("MySQL".equals(conn.getMetaData().getDatabaseProductName()) && "chaoslab_m1_r2".equals(conn.getCatalog()), "wrong R2 database");
                require(conn.getMetaData().getURL().startsWith("jdbc:mysql://127.0.0.1:3306/chaoslab_m1_r2?"), "non-local DB");
                result.put("databaseProduct", "MySQL"); result.put("databaseVersion", conn.getMetaData().getDatabaseProductVersion());
            }
            require("chaoslab_m1_r2@127.0.0.1".equals(jdbc.queryForObject("SELECT CURRENT_USER()", String.class)), "wrong R2 principal");
            var flyway = context.getBean(Flyway.class); flyway.validate();
            require(flyway.info().applied().length == 11 && "11".equals(flyway.info().current().getVersion().toString()), "Flyway mismatch");
            result.put("flyway", "V1-V11 validated");
            var targets = context.getBean(TargetRepository.class);
            var tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            UUID targetId = UUID.fromString("55555555-5555-4555-8555-555555555555");
            require(targets.findAll().isEmpty(), "R2 target table not fresh");
            tx.execute(s -> targets.save(Target.register(targetId,"order-service",TargetType.DOCKER_CONTAINER,TargetEnvironment.CHAOS_LAB)));
            require(targets.findById(targetId).isPresent(), "repository commit not visible");
            UUID rollback = UUID.randomUUID();
            tx.execute(s -> { targets.save(Target.register(rollback,"r2-rollback-probe",TargetType.DOCKER_CONTAINER,TargetEnvironment.CHAOS_LAB)); s.setRollbackOnly(); return null; });
            require(targets.findById(rollback).isEmpty(), "rollback failed");
            require(jdbc.queryForObject("SELECT count(*) FROM fault_scenarios WHERE id='00000000-0000-0000-0000-000000000101' AND code='CPU_LOAD' AND enabled=true", Long.class)==1, "CPU metadata missing");
            result.put("repositoryCommitRollback", "PASS; R2 target metadata only");
            var fresh = ChaosBladeEngine.class.getDeclaredMethod("fresh", UUID.class); fresh.setAccessible(true);
            var verified = (VerifiedDockerTarget)fresh.invoke(actualEngine,targetId);
            var channel = context.getBean(BladeProcessChannel.class);
            var channelField = ChaosBladeEngine.class.getDeclaredField("channel"); channelField.setAccessible(true);
            require(channelField.get(actualEngine)==channel, "not actual production channel");
            var policy = channel.preflight();
            require("REAL".equals(policy.path("deployment").asText()) && "m1-real-state-r2".equals(policy.path("stateId").asText()), "R2 policy mismatch");
            var dep = context.getBean(DockerCpuCommandPlan.Deployment.class);
            var request = new ReadyExperimentRequest(UUID.randomUUID(),UUID.randomUUID(),targetId,"CPU_LOAD",10,"{\"percent\":10}",verified);
            var plan = DockerCpuCommandPlan.from(request,verified,dep);
            var intent = BladeExecutionSnapshot.intent(plan,env.getRequiredProperty("chaoslab.blade.node-id"),env.getRequiredProperty("chaoslab.blade.state-id"),
                    env.getRequiredProperty("chaoslab.blade.tool-version"),env.getRequiredProperty("chaoslab.blade.cli-sha256"),Instant.now());
            require(intent.uid()==null && context.getBean(BladeLocalIdentityVerifier.class).verifyBeforeCreate(intent,plan)
                    == BladeLocalIdentityVerifier.Result.LOCAL_IDENTITY_MATCHED_NEEDS_TARGET_CHECK, "UID-free tool identity rejected");
            var fixtureResults = new LinkedHashMap<String,Object>();
            for (var mode : List.of("settles", "wrong-uid", "wrong-target", "wrong-node", "present", "unknown"))
                fixtureResults.put(mode, settlingFixture(dep, verified, mode));
            result.put("harmlessIntegrationSeam", fixtureResults);
            result.put("fixtureScope", "Same loaded engine class; mocked external ports; no production bean overrides, DB journal or root dispatch");
            var transport = BladeProcessChannel.class.getDeclaredMethod("transport",String.class,java.util.function.BooleanSupplier.class); transport.setAccessible(true);
            String raw = (String)transport.invoke(channel,"{\"operation\":\"preflight\"}",(java.util.function.BooleanSupplier)() -> false);
            require(BladeProcessChannel.decodePreflight(raw).equals(policy), "policy changed");
            var root = JsonMapper.builder().build().readTree(raw); var o = root.path("observation");
            require(o.path("probeReady").asBoolean() && "CLEAR".equals(o.path("residual").asText()) && "UNKNOWN".equals(o.path("health").asText()), "readonly readiness mismatch");
            require(o.path("cpuPercent").isNumber() && o.path("cpuPercent").asDouble()<=1, "CPU baseline not idle");
            require(verified.containerId().equals(o.path("containerId").asText()) && verified.imageId().equals(o.path("imageId").asText())
                    && env.getRequiredProperty("chaoslab.blade.node-id").equals(o.path("nodeId").asText()), "observation subject mismatch");
            result.put("policy",policy); result.put("policyDigest",root.path("policyDigest").asText()); result.put("observation",o);
            result.put("observationRoute", "Actual Spring adapter/Channel -> sudo -> root preflight -> observeTarget(no binding)");
            boolean denied = false;
            try (var socket = SocketChannel.open(StandardProtocolFamily.UNIX)) { socket.connect(UnixDomainSocketAddress.of("/var/run/docker.sock")); }
            catch (java.io.IOException e) { denied=String.valueOf(e.getMessage()).contains("Permission denied"); }
            require(denied,"Docker access not denied"); result.put("directDockerSocket","Permission denied");
            for (var path : List.of("/usr/local/libexec/chaoslab-m1-wrapper",dep.executable().toString(),"/etc/chaoslab-m1/policy.json",dep.stateDirectory().toString()))
                require(!Files.isWritable(Path.of(path)),"trusted deployment writable");
            for (var table : List.of("experiments","experiment_executions","blade_execution_snapshots"))
                require(jdbc.queryForObject("SELECT count(*) FROM "+table,Long.class)==0,"R2 experimental state created");
            result.put("experimentalRows",0); result.put("passed",true);
            Files.writeString(REPORT,JsonMapper.builder().build().writeValueAsString(result),StandardOpenOption.CREATE_NEW);
            System.out.println("R2 JAVA READONLY PASS; SECOND M1 NOT AUTHORIZED");
        } catch (Throwable failure) {
            result.put("passed",false); result.put("blocker", failure.getClass().getSimpleName());
            Files.writeString(REPORT,JsonMapper.builder().build().writeValueAsString(result),StandardOpenOption.CREATE_NEW);
            context.close(); throw failure;
        }
    }
}
