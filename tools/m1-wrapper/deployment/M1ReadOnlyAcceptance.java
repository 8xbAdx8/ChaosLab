package com.chaoslab.engine.infrastructure.blade;

import com.chaoslab.ChaosLabApplication;
import com.chaoslab.engine.application.model.ReadyExperimentRequest;
import com.chaoslab.engine.application.port.ChaosEngine;
import com.chaoslab.safety.application.model.VerifiedDockerTarget;
import com.chaoslab.target.application.port.TargetRepository;
import com.chaoslab.target.domain.*;
import org.flywaydb.core.Flyway;
import org.springframework.boot.SpringApplication;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import java.nio.channels.SocketChannel;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** One-shot deployment acceptance only. No controller, mock, bean override or native UID.
 * Uses the unchanged 698a905 classes and their actual configured Spring beans.
 * Reflective calls expose existing private read-only seams without changing that artifact.
 */
public final class M1ReadOnlyAcceptance {
    private static void require(boolean value, String label) {
        if (!value) throw new IllegalStateException(label);
    }
    public static void main(String[] args) throws Throwable {
        var result = new LinkedHashMap<String,Object>();
        result.put("commit", "698a905322104063ae130f810999723b72d9aca2");
        result.put("realCreateInvoked", false);
        var context = SpringApplication.run(ChaosLabApplication.class, args);
        try {
            require(Runtime.version().feature() == 21, "Java 21 required");
            var status = Files.readString(Path.of("/proc/self/status"));
            var uid = status.lines().filter(s -> s.startsWith("Uid:")).findFirst().orElseThrow();
            var gid = status.lines().filter(s -> s.startsWith("Gid:")).findFirst().orElseThrow();
            require(Arrays.stream(uid.split("\\s+")).skip(1).allMatch("999"::equals), "ordinary chaoslab UID required");
            require(Arrays.stream(gid.split("\\s+")).skip(1).allMatch("987"::equals), "chaoslab GID required");
            for (var cap : List.of("CapEff:", "CapPrm:", "CapAmb:"))
                require(status.lines().filter(s -> s.startsWith(cap)).findFirst().orElseThrow().trim().endsWith("0000000000000000"), "capabilities forbidden");
            result.put("javaVersion", System.getProperty("java.runtime.version"));
            result.put("pid", ProcessHandle.current().pid());
            result.put("uid", 999); result.put("gid", 987);
            var env = context.getEnvironment();
            require("blade".equals(env.getProperty("chaoslab.engine")), "real engine required");
            require("127.0.0.1".equals(env.getProperty("server.address")), "HTTP must be loopback");
            require(context.getBeansOfType(ChaosEngine.class).size() == 1, "exactly one engine");
            var engine = context.getBean(ChaosBladeEngine.class);
            require(context.getBeansOfType(com.chaoslab.execution.infrastructure.scheduling.AutomaticExperimentRecoveryJob.class).isEmpty(), "no real recovery scheduler");
            result.put("adapterClass", engine.getClass().getName());
            var jdbc = context.getBean(JdbcTemplate.class);
            try (var conn = Objects.requireNonNull(jdbc.getDataSource()).getConnection()) {
                require("MySQL".equals(conn.getMetaData().getDatabaseProductName()), "not MySQL");
                require("chaoslab_m1".equals(conn.getCatalog()), "wrong database");
                require(conn.getMetaData().getURL().startsWith("jdbc:mysql://127.0.0.1:3306/chaoslab_m1?"), "non-local database");
                result.put("databaseProduct", conn.getMetaData().getDatabaseProductName());
                result.put("databaseVersion", conn.getMetaData().getDatabaseProductVersion());
                result.put("databaseScope", "127.0.0.1:3306/chaoslab_m1");
            }
            require("chaoslab_m1@127.0.0.1".equals(jdbc.queryForObject("SELECT CURRENT_USER()",String.class)), "wrong DB principal");
            var flyway = context.getBean(Flyway.class);
            flyway.validate();
            require(flyway.info().applied().length == 11 && "11".equals(flyway.info().current().getVersion().toString()), "Flyway V1-V11 required");
            result.put("flyway", "V1-V11 validated");
            var targets = context.getBean(TargetRepository.class);
            var tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            UUID targetId = UUID.fromString("55555555-5555-4555-8555-555555555555");
            require(targets.findAll().isEmpty(), "fresh M1 database required");
            tx.execute(s -> targets.save(Target.register(targetId,"order-service",TargetType.DOCKER_CONTAINER,TargetEnvironment.CHAOS_LAB)));
            require(targets.findById(targetId).isPresent(), "repository commit not visible");
            UUID rollback = UUID.randomUUID();
            tx.execute(s -> { targets.save(Target.register(rollback,"m1-rollback-probe",TargetType.DOCKER_CONTAINER,TargetEnvironment.CHAOS_LAB)); s.setRollbackOnly(); return null; });
            require(targets.findById(rollback).isEmpty(), "transaction rollback failed");
            result.put("repositoryCommitRollback", "PASS; only target metadata, no Experiment");

            // Actual adapter private read-only method -> configured verifier -> real channel.
            var fresh = ChaosBladeEngine.class.getDeclaredMethod("fresh", UUID.class);
            fresh.setAccessible(true);
            var verified = (VerifiedDockerTarget)fresh.invoke(engine,targetId);
            var channelField = ChaosBladeEngine.class.getDeclaredField("channel");
            channelField.setAccessible(true);
            var channel = (BladeProcessChannel)channelField.get(engine);
            require(channel == context.getBean(BladeProcessChannel.class), "not actual configured channel");
            var policy = channel.preflight();
            require("REAL".equals(policy.path("deployment").asText()), "wrong deployment");
            require(verified.containerId().equals(policy.path("containerId").asText()) && verified.imageId().equals(policy.path("imageId").asText()), "target identity mismatch");
            var deployment = context.getBean(DockerCpuCommandPlan.Deployment.class);
            // In-memory pure validation model ONLY: no Experiment or journal write, no UID.
            var request = new ReadyExperimentRequest(UUID.randomUUID(),UUID.randomUUID(),targetId,"CPU_LOAD",10,"{\"percent\":10}",verified);
            var plan = DockerCpuCommandPlan.from(request,verified,deployment);
            var intent = BladeExecutionSnapshot.intent(plan,env.getRequiredProperty("chaoslab.blade.node-id"),
                    env.getRequiredProperty("chaoslab.blade.state-id"),env.getRequiredProperty("chaoslab.blade.tool-version"),
                    env.getRequiredProperty("chaoslab.blade.cli-sha256"),Instant.now());
            require(intent.uid() == null,"no native UID permitted");
            require(context.getBean(BladeLocalIdentityVerifier.class).verifyBeforeCreate(intent,plan)
                    == BladeLocalIdentityVerifier.Result.LOCAL_IDENTITY_MATCHED_NEEDS_TARGET_CHECK,"tool identity mismatch");
            result.put("targetIdentity", "MATCHED through actual ChaosBladeEngine.fresh");
            result.put("toolIdentity", "MATCHED; UID-free local verifier");

            // Standalone observe requires an experiment binding, which must not be fabricated.
            // The existing preflight invokes the same real root observeTarget without a binding.
            var transport = BladeProcessChannel.class.getDeclaredMethod("transport",String.class,java.util.function.BooleanSupplier.class);
            transport.setAccessible(true);
            String raw = (String)transport.invoke(channel,"{\"operation\":\"preflight\"}",(java.util.function.BooleanSupplier)() -> false);
            require(BladeProcessChannel.decodePreflight(raw).equals(policy),"policy changed");
            var root = JsonMapper.builder().build().readTree(raw);
            var observation = root.path("observation");
            require(observation.path("probeReady").asBoolean() && "CLEAR".equals(observation.path("residual").asText()),"residual not ready");
            require("UNKNOWN".equals(observation.path("health").asText()),"no-binding health cannot be recovered");
            require(observation.path("cpuPercent").isNumber() && observation.path("cpuPercent").asDouble() <= 1,"CPU baseline not idle");
            require(verified.containerId().equals(observation.path("containerId").asText()) && verified.imageId().equals(observation.path("imageId").asText())
                    && env.getRequiredProperty("chaoslab.blade.node-id").equals(observation.path("nodeId").asText()),"observation identity mismatch");
            result.put("policy",policy); result.put("policyDigest",root.path("policyDigest").asText());
            result.put("observation",observation);
            result.put("observationRoute","actual Channel.transport -> sudo -> root preflight -> observeTarget(no binding)");
            boolean dockerDenied = false;
            try (var socket = SocketChannel.open(StandardProtocolFamily.UNIX)) {
                socket.connect(UnixDomainSocketAddress.of("/var/run/docker.sock"));
            } catch (java.io.IOException e) { dockerDenied = String.valueOf(e.getMessage()).contains("Permission denied"); }
            require(dockerDenied,"Docker socket access not explicitly denied");
            result.put("directDockerSocket","Permission denied");
            for (var path : List.of("/usr/local/libexec/chaoslab-m1-wrapper",deployment.executable().toString(),
                    "/etc/chaoslab-m1/policy.json",deployment.stateDirectory().toString()))
                require(!Files.isWritable(Path.of(path)),"ordinary user can modify trusted deployment");
            for (var table : List.of("experiments","experiment_executions","blade_execution_snapshots"))
                require(jdbc.queryForObject("SELECT count(*) FROM "+table,Long.class)==0,"experiment/UID state created");
            result.put("experimentRows",0); result.put("executionRows",0); result.put("nativeUidRows",0);
            result.put("springBoot","RUNNING; actual production beans, unchanged artifact");
            result.put("passed",true);
            Files.writeString(Path.of("/var/lib/chaoslab-backend-m1/java-readonly-result.json"),JsonMapper.builder().build().writeValueAsString(result),StandardOpenOption.CREATE_NEW);
            System.out.println("M1 JAVA READONLY ACCEPTANCE PASS; REAL EXECUTION NOT AUTHORIZED");
            // Keep loopback HTTP server alive as the ordinary backend, without enabling recovery job.
        } catch (Throwable failure) {
            result.put("passed",false); result.put("blocker",failure.getClass().getSimpleName());
            Files.writeString(Path.of("/var/lib/chaoslab-backend-m1/java-readonly-result.json"),JsonMapper.builder().build().writeValueAsString(result),StandardOpenOption.CREATE_NEW);
            context.close();
            throw failure;
        }
    }
}
