package com.chaoslab.console;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(
    properties = {
        "chaoslab.console.read-only=true",
        "spring.datasource.url=jdbc:h2:mem:console-read;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
    }
)
@AutoConfigureMockMvc
@Transactional
class ConsoleReadApiTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    org.springframework.context.ApplicationContext context;

    private String[] seed() {
        String target = UUID.randomUUID().toString(),
            exp = UUID.randomUUID().toString(),
            exec = UUID.randomUUID().toString();
        jdbc.update(
            "INSERT INTO targets(id,name,target_type,environment,enabled) VALUES(?,?,'DOCKER_CONTAINER','CHAOS_LAB',true)",
            target,
            "console-test"
        );
        jdbc.update(
            "INSERT INTO experiments(id,name,hypothesis,target_id,scenario_id,duration_seconds,parameters,status,version) VALUES(?,?,'read fixture',?,'00000000-0000-0000-0000-000000000101',10,JSON_OBJECT('percent',10),'SUCCESS',0)",
            exp,
            "Console fixture",
            target
        );
        jdbc.update(
            "INSERT INTO experiment_executions(id,experiment_id,attempt,idempotency_key,status,created_at,started_at,finished_at,version) VALUES(?,?,1,'read-fixture','SUCCESS',TIMESTAMP '2026-10-10 00:00:00',TIMESTAMP '2026-10-10 00:00:05',TIMESTAMP '2026-10-10 00:00:20',0)",
            exec,
            exp
        );
        return new String[] { target, exp, exec };
    }

    @Test
    void overviewPaginationAndMissingProofAreHonest() throws Exception {
        var ids = seed();
        mvc.perform(get("/api/v1/console/overview"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.executionStates.SUCCESS").value(1))
            .andExpect(jsonPath("$.successRate").value(100.0))
            .andExpect(jsonPath("$.authenticationConfigured").value(false));
        mvc.perform(
            get("/api/v1/console/experiments")
                .param("search", "fixture")
                .param("size", "1")
        )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.items[0].parameters.percent").value(10));
        mvc.perform(
            get("/api/v1/console/executions").param("experimentId", ids[1])
        )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].id").value(ids[2]));
        mvc.perform(get("/api/v1/console/executions/" + ids[2] + "/evidence"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.physicalRecovery").value("UNKNOWN"))
            .andExpect(jsonPath("$.afterCpuPercent").isEmpty());
        mvc.perform(
            get("/api/v1/console/executions/" + UUID.randomUUID())
        ).andExpect(status().isNotFound());
    }

    @Test
    void queryBoundsAndInjectionRemainReadOnly() throws Exception {
        seed();
        for (String path : new String[] {
            "experiments",
            "executions",
            "audit-logs",
            "reports",
        })
            mvc.perform(
                get("/api/v1/console/" + path).param("size", "101")
            ).andExpect(status().isBadRequest());
        mvc.perform(
            get("/api/v1/console/experiments").param("search", "' OR 1=1 --")
        )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(0));
        mvc.perform(
            get("/api/v1/console/executions").param("status", "BOGUS")
        ).andExpect(status().isBadRequest());
        mvc.perform(
            get("/api/v1/console/audit-logs")
                .param("from", "2026-10-11T00:00:00Z")
                .param("to", "2026-10-10T00:00:00Z")
        ).andExpect(status().isBadRequest());
        assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM experiment_executions",
                Long.class
            )
        ).isEqualTo(1);
    }

    @Test
    void publicAuditProjectionOmitsSecretsPathsAndSourceIp() throws Exception {
        var ids = seed();
        jdbc.update(
            "INSERT INTO audit_logs(id,actor,operation,experiment_id,execution_id,target_id,parameters,result,failure_code,source_ip,occurred_at) VALUES(?,'ANONYMOUS','START_EXPERIMENT',?,?,?,JSON_OBJECT('percent',10,'password','secret','stderr','/root/private','executable','/root/blade'), 'FAILED','/root/private','127.0.0.1',CURRENT_TIMESTAMP)",
            UUID.randomUUID().toString(),
            ids[1],
            ids[2],
            ids[0]
        );
        mvc.perform(
            get("/api/v1/console/audit-logs")
                .param("experimentId", ids[1])
                .param("result", "FAILED")
        )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].parameters.percent").value(10))
            .andExpect(
                jsonPath("$.items[0].parameters.password").doesNotExist()
            )
            .andExpect(jsonPath("$.items[0].parameters.stderr").doesNotExist())
            .andExpect(jsonPath("$.items[0].sourceIp").doesNotExist())
            .andExpect(
                jsonPath("$.items[0].failureCode").value("DETAILS_REDACTED")
            );
    }

    @Test
    void evidenceRequiresSameSubjectAndNeverInventsAfterOrCause()
        throws Exception {
        var ids = seed();
        jdbc.update(
            "INSERT INTO blade_execution_snapshots VALUES(?,'CRI_CPU_V1',?,?,?,'order-service','console-node','console-state','api3',?,10,10,TIMESTAMP '2026-10-10 00:00:00',TIMESTAMP '2026-10-10 00:00:10','0123456789abcdef')",
            ids[2],
            ids[0],
            "a".repeat(64),
            "sha256:" + "b".repeat(64),
            "c".repeat(64)
        );
        String subject =
            "'version',1,'nativeUid','0123456789abcdef','nodeId','console-node','stateId','console-state','containerId','" +
            "a".repeat(64) +
            "','imageId','sha256:" +
            "b".repeat(64) +
            "','toolSha256','" +
            "c".repeat(64) +
            "'";
        jdbc.update(
            "INSERT INTO audit_logs(id,actor,operation,execution_id,target_id,parameters,result,occurred_at) VALUES(?,'BLADE_M1_CORE','M1_CPU_OBSERVATION',?,?,JSON_OBJECT(" +
                subject +
                ",'cpuPercent',10.91,'baselinePercent',0,'usageUsec',123,'observedAt','2026-10-10T00:00:05Z'),'SUCCESS',CURRENT_TIMESTAMP)",
            UUID.randomUUID().toString(),
            ids[2],
            ids[0]
        );
        String audit = UUID.randomUUID().toString();
        jdbc.update(
            "INSERT INTO audit_logs(id,actor,operation,execution_id,target_id,parameters,result,occurred_at) VALUES(?,'BLADE_M1_CORE','M1_PHYSICAL_RECOVERY',?,?,JSON_OBJECT(" +
                subject +
                ",'physicalRecovery','VERIFIED','nativeStatus','Destroyed','residual','CLEAR','health','HEALTHY','recoveryCause','UNKNOWN','observedAt','2026-10-10T00:00:18Z','engineObservedAt','2026-10-10T00:00:17Z'),'SUCCESS',CURRENT_TIMESTAMP)",
            audit,
            ids[2],
            ids[0]
        );
        mvc.perform(get("/api/v1/console/executions/" + ids[2] + "/evidence"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.duringCpuPercent").value(10.91))
            .andExpect(jsonPath("$.physicalRecovery").value("VERIFIED"))
            .andExpect(jsonPath("$.afterCpuPercent").isEmpty())
            .andExpect(jsonPath("$.recoveryCause").value("UNKNOWN"));
        jdbc.update(
            "UPDATE audit_logs SET parameters=JSON_OBJECT('version',1,'nodeId','wrong-node','physicalRecovery','VERIFIED') WHERE id=?",
            audit
        );
        mvc.perform(get("/api/v1/console/executions/" + ids[2] + "/evidence"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.physicalRecovery").value("UNKNOWN"));
    }

    @Test
    void consoleModeDeniesMutationsAndDisablesAutomaticRecovery()
        throws Exception {
        for (String path : new String[] {
            "/api/v1/experiments",
            "/api/v1/targets",
            "/api/v1/emergency-stop",
            "/api/v1/console/overview",
        })
            mvc.perform(
                post(path).contentType("application/json").content("{}")
            )
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CONSOLE_READ_ONLY"));
        assertThat(
            context.getBeansOfType(
                com.chaoslab.execution.infrastructure.scheduling.AutomaticExperimentRecoveryJob.class
            )
        ).isEmpty();
    }

    @Test
    void existingReportConclusionsAndAllWireContractsArePreserved()
        throws Exception {
        var ids = seed();
        String report = UUID.randomUUID().toString();
        jdbc.update(
            "INSERT INTO experiment_reports(id,experiment_id,execution_id,generation_key,target_id,scenario_code,start_audit_id,recovery_audit_id,started_at,finished_at,generated_at,execution_mode,metrics_status,conclusion_status,reason) VALUES(?,?,?,'console-fixture',?,'CPU_LOAD',?,?,TIMESTAMP '2026-10-10 00:00:05',TIMESTAMP '2026-10-10 00:00:20',CURRENT_TIMESTAMP,'UNVERIFIED','NOT_COLLECTED','INSUFFICIENT_DATA','metrics were not collected')",
            report,
            ids[1],
            ids[2],
            ids[0],
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString()
        );
        mvc.perform(get("/api/v1/console/reports").param("executionId", ids[2]))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].executionMode").value("UNVERIFIED"))
            .andExpect(
                jsonPath("$.items[0].bindingStatus").value("NOT_VERIFIED")
            )
            .andExpect(jsonPath("$.items[0].windows[2].p95Seconds").isEmpty());
        mvc.perform(get("/api/v1/console/reports/" + report))
            .andExpect(status().isOk())
            .andExpect(
                jsonPath("$.conclusionStatus").value("INSUFFICIENT_DATA")
            );
        // Build artifact only: real MockMvc serialization is consumed by TypeScript contract tests.
        // This is test-only H2 data, never historical evidence or demo/runtime data.
        var mapper = JsonMapper.builder().build();
        var contracts = new LinkedHashMap<String, Object>();
        var paths = java.util.Map.of(
            "overview",
            "/api/v1/console/overview",
            "experiments",
            "/api/v1/console/experiments",
            "experiment",
            "/api/v1/console/experiments/" + ids[1],
            "executions",
            "/api/v1/console/executions",
            "execution",
            "/api/v1/console/executions/" + ids[2],
            "evidence",
            "/api/v1/console/executions/" + ids[2] + "/evidence",
            "reports",
            "/api/v1/console/reports",
            "report",
            "/api/v1/console/reports/" + report,
            "targets",
            "/api/v1/targets",
            "scenarios",
            "/api/v1/scenarios"
        );
        for (var entry : paths.entrySet())
            contracts.put(
                entry.getKey(),
                mapper.readTree(
                    mvc
                        .perform(get(entry.getValue()))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString()
                )
            );
        Files.createDirectories(Path.of("target"));
        Files.writeString(
            Path.of("target/console-contracts.json"),
            mapper.writeValueAsString(contracts)
        );
        assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM experiment_reports",
                Long.class
            )
        ).isEqualTo(1);
    }
}
