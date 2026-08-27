package com.chaoslab.execution.interfaces.rest;

import com.chaoslab.engine.application.model.EngineCreateResult;
import com.chaoslab.engine.application.model.EngineDestroyResult;
import com.chaoslab.engine.application.model.EngineExperimentId;
import com.chaoslab.engine.application.model.EngineStatusResult;
import com.chaoslab.engine.application.model.ReadyExperimentRequest;
import com.chaoslab.engine.application.port.ChaosEngine;
import com.chaoslab.engine.infrastructure.fake.FakeChaosEngine;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.net.URI;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "chaoslab.execution.recovery-scan-interval-ms=60000",
        "spring.datasource.url=jdbc:h2:mem:chaoslab-mutex-test;"
                + "MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE"
})
@AutoConfigureMockMvc
@Import(TargetExecutionMutexApiIntegrationTests.EngineConfiguration.class)
class TargetExecutionMutexApiIntegrationTests {

    private static final String CPU_LOAD_ID =
            "00000000-0000-0000-0000-000000000101";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private BlockingChaosEngine chaosEngine;

    @Test
    void shouldSerializeConcurrentStartsAndRejectSecondExecution()
            throws Exception {
        String suffix = UUID.randomUUID().toString();
        String targetId = registerTarget(suffix);
        String firstExperiment = createReadyExperiment(
                targetId,
                "first-" + suffix
        );
        String secondExperiment = createReadyExperiment(
                targetId,
                "second-" + suffix
        );
        chaosEngine.blockNextCreate();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch secondRequestStarted = new CountDownLatch(1);
        try {
            Future<MvcResult> first = executor.submit(() -> start(
                    firstExperiment,
                    "mutex-first-" + suffix
            ));
            assertThat(chaosEngine.awaitBlockedCreate(Duration.ofSeconds(5)))
                    .isTrue();

            Future<MvcResult> second = executor.submit(() -> {
                secondRequestStarted.countDown();
                return start(secondExperiment, "mutex-second-" + suffix);
            });
            assertThat(secondRequestStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> second.get(300, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);

            chaosEngine.releaseBlockedCreate();
            MvcResult firstResult = first.get(5, TimeUnit.SECONDS);
            MvcResult secondResult = second.get(5, TimeUnit.SECONDS);

            assertThat(firstResult.getResponse().getStatus()).isEqualTo(201);
            assertThat(secondResult.getResponse().getStatus()).isEqualTo(409);
            assertThat(secondResult.getResponse().getContentAsString())
                    .contains("\"code\":\"TARGET_EXECUTION_ALREADY_ACTIVE\"");

            String executionLocation = firstResult.getResponse().getHeader("Location");
            assertThat(executionLocation).isNotNull();
            mockMvc.perform(post(URI.create(executionLocation).getPath() + "/destroy"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("SUCCESS"));

            MvcResult retry = mockMvc.perform(post(
                            secondExperiment + "/executions"
                    ).header("Idempotency-Key", "mutex-retry-" + suffix))
                    .andExpect(status().isCreated())
                    .andReturn();
            String retryLocation = retry.getResponse().getHeader("Location");
            assertThat(retryLocation).isNotNull();
            mockMvc.perform(post(URI.create(retryLocation).getPath() + "/destroy"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("SUCCESS"));
        } finally {
            chaosEngine.releaseBlockedCreate();
            executor.shutdownNow();
        }
    }

    private MvcResult start(String experimentPath, String idempotencyKey)
            throws Exception {
        return mockMvc.perform(post(experimentPath + "/executions")
                        .header("Idempotency-Key", idempotencyKey))
                .andReturn();
    }

    private String registerTarget(String suffix) throws Exception {
        String request = """
                {
                  "name": "mutex-target-%s",
                  "type": "JAVA_APPLICATION",
                  "environment": "CHAOS_LAB"
                }
                """.formatted(suffix);
        MvcResult result = mockMvc.perform(post("/api/v1/targets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isCreated())
                .andReturn();
        return locationPath(result).substring(
                locationPath(result).lastIndexOf('/') + 1
        );
    }

    private String createReadyExperiment(String targetId, String name)
            throws Exception {
        String request = """
                {
                  "name": "%s",
                  "hypothesis": "Service remains available.",
                  "targetId": "%s",
                  "scenarioId": "%s",
                  "durationSeconds": 30,
                  "parameters": {
                    "percent": 40
                  }
                }
                """.formatted(name, targetId, CPU_LOAD_ID);
        MvcResult creation = mockMvc.perform(post("/api/v1/experiments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isCreated())
                .andReturn();
        String experimentPath = locationPath(creation);
        mockMvc.perform(post(experimentPath + "/validation"))
                .andExpect(status().isOk());
        mockMvc.perform(post(experimentPath + "/dry-run"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true));
        return experimentPath;
    }

    private String locationPath(MvcResult result) {
        String location = result.getResponse().getHeader("Location");
        assertThat(location).isNotNull();
        return URI.create(location).getPath();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class EngineConfiguration {

        @Bean
        @Primary
        BlockingChaosEngine blockingChaosEngine() {
            return new BlockingChaosEngine();
        }
    }

    static class BlockingChaosEngine implements ChaosEngine {

        private final FakeChaosEngine delegate = new FakeChaosEngine();
        private final AtomicBoolean blockNextCreate = new AtomicBoolean();
        private volatile CountDownLatch createEntered = new CountDownLatch(0);
        private volatile CountDownLatch releaseCreate = new CountDownLatch(0);

        void blockNextCreate() {
            createEntered = new CountDownLatch(1);
            releaseCreate = new CountDownLatch(1);
            blockNextCreate.set(true);
        }

        boolean awaitBlockedCreate(Duration timeout) throws InterruptedException {
            return createEntered.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        void releaseBlockedCreate() {
            releaseCreate.countDown();
        }

        @Override
        public EngineCreateResult create(ReadyExperimentRequest request) {
            if (blockNextCreate.compareAndSet(true, false)) {
                createEntered.countDown();
                awaitRelease();
            }
            return delegate.create(request);
        }

        @Override
        public EngineStatusResult status(EngineExperimentId engineExperimentId) {
            return delegate.status(engineExperimentId);
        }

        @Override
        public EngineDestroyResult destroy(EngineExperimentId engineExperimentId) {
            return delegate.destroy(engineExperimentId);
        }

        private void awaitRelease() {
            try {
                if (!releaseCreate.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test engine release timed out");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("test engine wait interrupted");
            }
        }
    }
}