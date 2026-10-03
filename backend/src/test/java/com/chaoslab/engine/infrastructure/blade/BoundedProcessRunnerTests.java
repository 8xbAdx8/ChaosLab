package com.chaoslab.engine.infrastructure.blade;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.chaoslab.engine.infrastructure.blade.ProcessRunResult.Outcome.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Timeout(15)
class BoundedProcessRunnerTests {
    @TempDir Path directory;

    private final Path java = Path.of(System.getProperty("java.home"), "bin",
            System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");

    @Test
    void passesLiteralArgumentsAndCapturesSeparateStreams() throws Exception {
        var result = runner(5, 4096).run(args("echo", "space value", "$(echo injected);&|"), () -> false);
        assertThat(result.outcome()).isEqualTo(EXITED);
        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).contains("space value|$(echo injected);&|");
        assertThat(result.stderr()).contains("separate-stderr");
        assertThat(result.cleanupComplete()).isTrue();
    }

    @Test
    void fixesDirectoryClosesStdinAndUsesExplicitEnvironment() throws Exception {
        var result = runner(5, 4096).run(args("context"), () -> false);
        assertThat(result.outcome()).isEqualTo(EXITED);
        assertThat(result.stdout()).contains("cwd=" + directory.toRealPath(), "lang=C", "javaOptions=null", "stdin=-1");
    }

    @Test
    void preservesNonzeroExitRatherThanReportingSuccess() throws Exception {
        var result = runner(5, 4096).run(args("exit"), () -> false);
        assertThat(result.outcome()).isEqualTo(EXITED);
        assertThat(result.exitCode()).isEqualTo(7);
    }

    @Test
    void simultaneousFloodCannotDeadlockOrExceedCombinedBudget() throws Exception {
        var result = runner(5, 4096).run(args("flood"), () -> false);
        assertThat(result.outcome()).isEqualTo(OUTPUT_LIMIT);
        assertThat(result.stdout().getBytes(StandardCharsets.UTF_8).length
                + result.stderr().getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(4096);
        assertThat(result.cleanupComplete()).isTrue();
    }

    @Test
    void deadlineStopsTheChildProcess() throws Exception {
        var result = runner(2, 4096).run(args("sleep"), () -> false);
        assertThat(result.outcome()).isEqualTo(TIMED_OUT);
        assertThat(result.cleanupComplete()).isTrue();
        assertStopped(result.stdout(), "pid=");
    }

    @Test
    void cancellationBeforeStartDoesNotLaunchAnything() throws Exception {
        var result = runner(5, 4096).run(args("sleep"), () -> true);
        assertThat(result.outcome()).isEqualTo(CANCELLED);
        assertThat(result.exitCode()).isNull();
        assertThat(result.stdout()).isEmpty();
    }

    @Test
    void cancellationDuringExecutionTerminatesTheProcess() throws Exception {
        AtomicInteger polls = new AtomicInteger();
        var result = runner(5, 4096).run(args("sleep"), () -> polls.incrementAndGet() >= 50);
        assertThat(result.outcome()).isEqualTo(CANCELLED);
        assertThat(result.cleanupComplete()).isTrue();
    }

    @Test
    void interruptionCancelsAndPreservesTheInterruptFlag() throws Exception {
        var runner = runner(5, 4096);
        AtomicReference<ProcessRunResult> result = new AtomicReference<>();
        AtomicBoolean interrupted = new AtomicBoolean();
        CountDownLatch entered = new CountDownLatch(1);
        Thread caller = new Thread(() -> {
            result.set(runner.run(args("sleep"), () -> { entered.countDown(); return false; }));
            interrupted.set(Thread.currentThread().isInterrupted());
        });
        caller.start();
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        caller.interrupt();
        caller.join(5000);
        assertThat(caller.isAlive()).isFalse();
        assertThat(result.get().outcome()).isEqualTo(CANCELLED);
        assertThat(result.get().cleanupComplete()).isTrue();
        assertThat(interrupted).isTrue();
    }

    @Test
    void timeoutAlsoStopsAnObservedDescendant() throws Exception {
        var result = runner(3, 4096).run(args("child"), () -> false);
        assertThat(result.outcome()).isEqualTo(TIMED_OUT);
        assertThat(result.cleanupComplete()).isTrue();
        assertStopped(result.stdout(), "child=");
    }

    @Test
    void rejectsInheritedRuntimeHooksAndRelativePaths() {
        assertThatThrownBy(() -> new BoundedProcessRunner(java, directory,
                Map.of("JAVA_TOOL_OPTIONS", "-javaagent:unexpected.jar"), Duration.ofSeconds(5), 4096))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BoundedProcessRunner(Path.of("java"), directory,
                Map.of(), Duration.ofSeconds(5), 4096)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void survivingDescendantIsNotReportedAsNormalCompletion() throws Exception {
        var result = runner(5, 4096).run(args("orphan"), () -> false);
        assertThat(result.outcome()).isEqualTo(DESCENDANTS_REMAINED);
        assertThat(result.cleanupComplete()).isTrue();
        assertStopped(result.stdout(), "child=");
    }

    @Test
    void startFailureIsDistinguishedFromAnExecutedCommand() throws Exception {
        Path file = Files.createTempFile(directory, "missing-tool", ".exe");
        file.toFile().setExecutable(true);
        var runner = new BoundedProcessRunner(file, directory, Map.of(), Duration.ofSeconds(5), 4096);
        Files.delete(file);
        var result = runner.run(List.of(), () -> false);
        assertThat(result.outcome()).isEqualTo(START_FAILED);
        assertThat(result.exitCode()).isNull();
    }

    @Test
    void legalLongLivedHelperConflictsWithCurrentRunnerContract() throws Exception {
        // Harmless JVM sleeper, stdout/stderr detached: not a pipe-closure test.
        // The parent stays alive 500ms so the runner can observe its child.
        var result = runner(5, 4096).run(args("legal-helper"), () -> false);
        assertThat(result.exitCode()).isZero();
        assertThat(result.outcome()).isEqualTo(DESCENDANTS_REMAINED);
        assertThat(result.cleanupComplete()).isTrue();
        assertStopped(result.stdout(), "child=");
        System.out.println("M1_LIFECYCLE: parentExit=0 outcome=" + result.outcome()
                + " helperAlive=false cleanupComplete=" + result.cleanupComplete());
    }

    private BoundedProcessRunner runner(int seconds, int bytes) throws Exception {
        return new BoundedProcessRunner(java, directory, Map.of("LANG", "C"), Duration.ofSeconds(seconds), bytes);
    }

    private static List<String> args(String... args) {
        try {
            List<String> command = new ArrayList<>(List.of("-cp",
                    Path.of(ProcessFixture.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString(),
                    ProcessFixture.class.getName()));
            command.addAll(List.of(args));
            return command;
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void assertStopped(String output, String prefix) {
        long pid = output.lines().filter(line -> line.startsWith(prefix))
                .mapToLong(line -> Long.parseLong(line.substring(prefix.length()))).findFirst().orElseThrow();
        assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)).isFalse();
    }
}
