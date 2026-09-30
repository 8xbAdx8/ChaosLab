package com.chaoslab.engine.infrastructure.blade;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import static com.chaoslab.engine.infrastructure.blade.ProcessRunResult.Outcome.*;

/** Internal transport; callers outside this package use typed BladeProcessChannel methods. */
final class BoundedProcessRunner {
    private static final Set<String> ENVIRONMENT_KEYS = Set.of(
            "PATH", "LANG", "LC_ALL", "TMPDIR", "TMP", "TEMP", "SystemRoot");
    private final Path executable;
    private final Path directory;
    private final Map<String, String> environment;
    private final Duration timeout;
    private final int outputLimit;

    BoundedProcessRunner(Path executable, Path directory, Map<String, String> environment,
                         Duration timeout, int outputLimit) throws IOException {
        if (!executable.isAbsolute() || !directory.isAbsolute()) {
            throw new IllegalArgumentException("executable and working directory must be absolute");
        }
        this.executable = executable.toRealPath();
        this.directory = directory.toRealPath();
        if (!Files.isRegularFile(this.executable) || !Files.isExecutable(this.executable)
                || !Files.isDirectory(this.directory)) {
            throw new IllegalArgumentException("invalid executable or working directory");
        }
        if (!ENVIRONMENT_KEYS.containsAll(environment.keySet())) {
            throw new IllegalArgumentException("environment contains a non-whitelisted variable");
        }
        this.environment = Map.copyOf(environment);
        if (timeout.compareTo(Duration.ofMillis(10)) < 0 || timeout.compareTo(Duration.ofSeconds(60)) > 0
                || outputLimit < 1 || outputLimit > 1024 * 1024) {
            throw new IllegalArgumentException("invalid process deadline or output limit");
        }
        this.timeout = timeout;
        this.outputLimit = outputLimit;
    }

    ProcessRunResult run(List<String> arguments, BooleanSupplier cancelled) {
        List<String> command = new ArrayList<>();
        command.add(executable.toString());
        command.addAll(List.copyOf(arguments));
        Objects.requireNonNull(cancelled);
        if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean()) {
            return new ProcessRunResult(CANCELLED, null, "", "", true);
        }
        ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile());
        builder.environment().clear();
        builder.environment().putAll(environment);
        Process process;
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            process = builder.start();
        } catch (IOException exception) {
            return new ProcessRunResult(START_FAILED, null, "", "", true);
        }
        Capture capture = new Capture(outputLimit);
        Map<Long, ProcessHandle> descendants = new LinkedHashMap<>();
        Thread stdout = Thread.ofVirtual().name("blade-stdout").start(() -> capture.read(process.getInputStream(), false));
        Thread stderr = Thread.ofVirtual().name("blade-stderr").start(() -> capture.read(process.getErrorStream(), true));
        ProcessRunResult.Outcome outcome = IO_FAILED;
        boolean interrupted = false;
        boolean cleaned;
        try {
            process.getOutputStream().close();
            while (true) {
                process.descendants().forEach(child -> descendants.putIfAbsent(child.pid(), child));
                if (cancelled.getAsBoolean()) {
                    outcome = CANCELLED;
                    break;
                }
                if (capture.exceeded.get()) {
                    outcome = OUTPUT_LIMIT;
                    break;
                }
                if (capture.ioFailed.get()) {
                    outcome = IO_FAILED;
                    break;
                }
                if (!process.isAlive() && descendants.values().stream().anyMatch(ProcessHandle::isAlive)) {
                    outcome = DESCENDANTS_REMAINED;
                    break;
                }
                if (!process.isAlive() && capture.finished.getCount() == 0) {
                    // Recheck after reader completion to avoid losing a last-chunk failure.
                    outcome = capture.exceeded.get() ? OUTPUT_LIMIT : capture.ioFailed.get() ? IO_FAILED : EXITED;
                    break;
                }
                if (System.nanoTime() >= deadline) {
                    outcome = TIMED_OUT;
                    break;
                }
                Thread.sleep(20);
            }
        } catch (InterruptedException exception) {
            interrupted = true;
            outcome = CANCELLED;
        } catch (IOException exception) {
            outcome = IO_FAILED;
        } finally {
            // Clear interruption only during bounded cleanup, then restore it.
            interrupted |= Thread.interrupted();
            cleaned = terminate(process, descendants);
            stdout.interrupt();
            stderr.interrupt();
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        Integer exitCode = process.isAlive() ? null : process.exitValue();
        return new ProcessRunResult(outcome, exitCode, capture.text(false), capture.text(true), cleaned);
    }

    private boolean terminate(Process process, Map<Long, ProcessHandle> descendants) {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        boolean interrupted = false;
        try {
            do {
                process.descendants().forEach(child -> descendants.putIfAbsent(child.pid(), child));
                descendants.values().stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
                if (process.isAlive()) {
                    process.destroyForcibly();
                }
                if (!process.isAlive() && descendants.values().stream().noneMatch(ProcessHandle::isAlive)) {
                    return true;
                }
                try {
                    Thread.sleep(10);
                } catch (InterruptedException exception) {
                    interrupted = true;
                }
            } while (System.nanoTime() < deadline);
            return false;
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static final class Capture {
        private final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        private final ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        private final int maximum;
        private int used;
        private final AtomicBoolean exceeded = new AtomicBoolean();
        private final AtomicBoolean ioFailed = new AtomicBoolean();
        private final CountDownLatch finished = new CountDownLatch(2);

        private Capture(int maximum) {
            this.maximum = maximum;
        }

        private synchronized void append(byte[] bytes, int length, boolean errorStream) {
            int retained = Math.min(length, maximum - used);
            (errorStream ? stderr : stdout).write(bytes, 0, retained);
            used += retained;
            if (retained < length) {
                exceeded.set(true);
            }
        }

        private void read(InputStream stream, boolean errorStream) {
            try (stream) {
                byte[] bytes = new byte[4096];
                int count;
                while (!exceeded.get() && (count = stream.read(bytes)) != -1) {
                    append(bytes, count, errorStream);
                }
            } catch (IOException exception) {
                ioFailed.set(true);
            } finally {
                finished.countDown();
            }
        }

        private synchronized String text(boolean errorStream) {
            return (errorStream ? stderr : stdout).toString(StandardCharsets.UTF_8);
        }
    }
}
