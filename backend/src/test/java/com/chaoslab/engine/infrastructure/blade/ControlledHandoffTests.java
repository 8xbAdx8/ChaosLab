package com.chaoslab.engine.infrastructure.blade;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import java.io.FilterInputStream;
import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;
import static com.chaoslab.engine.infrastructure.blade.BoundedProcessRunner.Lifecycle.*;
import static com.chaoslab.engine.infrastructure.blade.ProcessRunResult.Outcome.*;

@Timeout(20)
class ControlledHandoffTests {
    @TempDir Path directory;
    @AfterEach void releaseWorkingDirectory() throws Exception {
        ProcessFixture.releaseWindowsWorkingDirectory(directory);
    }
    Path java = Path.of(System.getProperty("java.home"), "bin",
            System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");

    BoundedProcessRunner runner(int seconds) throws Exception {
        return new BoundedProcessRunner(java, directory, Map.of(), Duration.ofSeconds(seconds), 4096);
    }
    List<String> args(String mode, Path marker) throws Exception {
        return List.of("-cp", Path.of(ProcessFixture.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString(),
                ProcessFixture.class.getName(), mode, marker.toString());
    }
    ProcessHandle helper(Path marker) throws Exception {
        long id = Long.parseLong(Files.readString(marker));
        return ProcessHandle.of(id).orElse(null);
    }
    boolean alive(Path marker) throws Exception {
        var h = helper(marker); return h != null && h.isAlive();
    }
    void cleanup(Path marker) throws Exception {
        if (!Files.exists(marker)) return;
        var h = helper(marker);
        if (h != null && h.isAlive()) {
            h.destroyForcibly();
            long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
            while (h.isAlive() && System.nanoTime()<deadline) Thread.sleep(10);
            assertThat(h.isAlive()).as("test-owned helper cleanup").isFalse();
        }
    }

    @Test void successfulHandoffAndInvalidJsonBothPreserveHelper() throws Exception {
        for (String mode : List.of("handoff-ok", "handoff-bad-json")) {
            Path marker = directory.resolve(mode);
            try {
                var r = runner(5).run(args(mode, marker), () -> false, CONTROLLED_HANDOFF);
                assertThat(r.outcome()).isEqualTo(HANDOFF);
                assertThat(r.exitCode()).isZero();
                assertThat(r.cleanupComplete()).isFalse();
                assertThat(alive(marker)).isTrue();
                // Existing decoder is intentionally NOT adapted in this phase.
                assertThatThrownBy(() -> new BladeResponseDecoder().decodeCreate(r))
                        .isInstanceOf(BladeResponseDecoder.DecodeException.class);
                assertThat(alive(marker)).isTrue();
            } finally { cleanup(marker); }
        }
    }

    @Test void timeoutNonzeroAndOutputLimitCleanObservedHelpers() throws Exception {
        for (String mode : List.of("handoff-wait", "handoff-nonzero", "handoff-flood")) {
            Path marker = directory.resolve(mode);
            try {
                var r = runner(mode.equals("handoff-wait") ? 2 : 5)
                        .run(args(mode, marker), () -> false, CONTROLLED_HANDOFF);
                assertThat(r.outcome()).isEqualTo(switch(mode) {
                    case "handoff-wait" -> TIMED_OUT;
                    case "handoff-flood" -> OUTPUT_LIMIT;
                    default -> DESCENDANTS_REMAINED;
                });
                if (mode.equals("handoff-nonzero")) assertThat(r.exitCode()).isEqualTo(7);
                assertThat(r.cleanupComplete()).isTrue();
                assertThat(alive(marker)).isFalse();
            } finally { cleanup(marker); }
        }
    }

    @Test void cancellationAndInterruptionCleanHelpers() throws Exception {
        for (boolean interrupt : List.of(false, true)) {
            Path marker = directory.resolve("cancel-"+interrupt);
            var runner = runner(8);
            var command = args("handoff-wait", marker);
            AtomicBoolean cancel = new AtomicBoolean();
            AtomicBoolean restored = new AtomicBoolean();
            AtomicReference<ProcessRunResult> result = new AtomicReference<>();
            Thread caller = new Thread(() -> {
                result.set(runner.run(command, cancel::get, CONTROLLED_HANDOFF));
                restored.set(Thread.currentThread().isInterrupted());
            });
            try {
                caller.start();
                long until = System.nanoTime()+Duration.ofSeconds(4).toNanos();
                while (!Files.exists(marker) && System.nanoTime()<until) Thread.sleep(10);
                assertThat(Files.exists(marker)).isTrue();
                Thread.sleep(300);
                if (interrupt) caller.interrupt(); else cancel.set(true);
                caller.join(5000);
                assertThat(caller.isAlive()).isFalse();
                assertThat(result.get().outcome()).isEqualTo(CANCELLED);
                assertThat(result.get().cleanupComplete()).isTrue();
                assertThat(alive(marker)).isFalse();
                assertThat(restored.get()).isEqualTo(interrupt);
            } finally { cancel.set(true); caller.interrupt(); caller.join(5000); cleanup(marker); }
        }
    }

    @Test void readFailureCleansHelper() throws Exception {
        Path marker = directory.resolve("io");
        var r = new BoundedProcessRunner(java, directory, Map.of(), Duration.ofSeconds(5), 4096,
                stream -> new FilterInputStream(stream) {
                    @Override public int read(byte[] b, int off, int len) throws IOException {
                        int n = super.read(b, off, len); // Parent has created helper before printing.
                        if (n > 0) {
                            try { Thread.sleep(300); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                            throw new IOException("injected read failure");
                        }
                        return n;
                    }
                });
        try {
            var result = r.run(args("handoff-wait", marker), () -> false, CONTROLLED_HANDOFF);
            assertThat(result.outcome()).isEqualTo(IO_FAILED);
            assertThat(result.cleanupComplete()).isTrue();
            assertThat(alive(marker)).isFalse();
        } finally { cleanup(marker); }
    }

    @Test void startFailureAndPreCancelledDoNotLaunch() throws Exception {
        Path missing = Files.createFile(directory.resolve("tool.exe"));
        missing.toFile().setExecutable(true);
        var r = new BoundedProcessRunner(missing, directory, Map.of(), Duration.ofSeconds(1), 4096);
        Files.delete(missing);
        var failed = r.run(List.of(), () -> false, CONTROLLED_HANDOFF);
        assertThat(failed.outcome()).isEqualTo(START_FAILED);
        assertThat(failed.cleanupComplete()).isTrue();
        assertThat(failed.exitCode()).isNull();
        assertThat(r.run(List.of(), () -> true, CONTROLLED_HANDOFF).outcome()).isEqualTo(CANCELLED);
    }

    @Test void channelRoutesOnlyCreateToHandoff() {
        var runner = org.mockito.Mockito.mock(BoundedProcessRunner.class);
        var channel = new BladeProcessChannel(runner);
        var plan = org.mockito.Mockito.mock(DockerCpuCommandPlan.class);
        var handle = org.mockito.Mockito.mock(BladeRecoveryHandle.class);
        org.mockito.Mockito.when(plan.createArguments()).thenReturn(List.of(DockerCpuCommandPlan.EXECUTABLE, "create"));
        org.mockito.Mockito.when(plan.deployment()).thenReturn(DockerCpuCommandPlan.DEFAULT_DEPLOYMENT);
        org.mockito.Mockito.when(handle.statusArguments(DockerCpuCommandPlan.DEFAULT_DEPLOYMENT)).thenReturn(List.of(DockerCpuCommandPlan.EXECUTABLE, "status"));
        org.mockito.Mockito.when(handle.destroyArguments(DockerCpuCommandPlan.DEFAULT_DEPLOYMENT)).thenReturn(List.of(DockerCpuCommandPlan.EXECUTABLE, "destroy"));
        java.util.function.BooleanSupplier cancel = () -> false;
        channel.create(plan, cancel); channel.status(handle, cancel); channel.destroy(handle, cancel);
        org.mockito.Mockito.verify(runner).run(List.of("create"), cancel, CONTROLLED_HANDOFF);
        org.mockito.Mockito.verify(runner).run(List.of("status"), cancel, STRICT_FOREGROUND);
        org.mockito.Mockito.verify(runner).run(List.of("destroy"), cancel, STRICT_FOREGROUND);
    }

    @Test void inheritedOutputDescriptorsAreNotAssumedClosed() throws Exception {
        // Unlike detached helper: orphan inherits both CLI pipes and sleeps.
        var r = runner(2).run(args("orphan", directory.resolve("unused")), () -> false, CONTROLLED_HANDOFF);
        long pid = r.stdout().lines().filter(s -> s.startsWith("child="))
                .mapToLong(s -> Long.parseLong(s.substring(6))).findFirst().orElseThrow();
        var h = ProcessHandle.of(pid);
        try {
            System.out.println("M1_FD_INHERIT: outcome="+r.outcome()+" cleanup="+r.cleanupComplete()
                    +" helperAlive="+h.map(ProcessHandle::isAlive).orElse(false));
            // JDK pipe-draining behaviour differs by OS; either explicit handoff or bounded failure.
            assertThat(r.outcome()).isIn(HANDOFF, TIMED_OUT);
            if (r.outcome() == HANDOFF) assertThat(r.cleanupComplete()).isFalse();
            else { assertThat(r.cleanupComplete()).isTrue(); assertThat(h.map(ProcessHandle::isAlive).orElse(false)).isFalse(); }
        } finally {
            if (h.isPresent() && h.get().isAlive()) {
                h.get().destroyForcibly();
                long end=System.nanoTime()+Duration.ofSeconds(3).toNanos();
                while(h.get().isAlive() && System.nanoTime()<end) Thread.sleep(10);
                assertThat(h.get().isAlive()).isFalse();
            }
        }
    }
}
