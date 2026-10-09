package com.chaoslab.engine.infrastructure.blade;

import java.nio.file.Path;
import java.util.Arrays;

/** Harmless child JVM used by transport tests; never invokes Blade or Docker. */
public final class ProcessFixture {
    /** Test-only bounded cleanup for hosted Windows file-in-use errors.
     * All process assertions/helper cleanup happen BEFORE this hook. No recursion,
     * process killing, relaxed assertions or ignored persistent filesystem errors.
     */
    static void releaseWindowsWorkingDirectory(Path directory) throws Exception {
        if (!System.getProperty("os.name").startsWith("Windows") || !java.nio.file.Files.exists(directory)) return;
        Path target = directory.toRealPath();
        Path temporaryRoot = Path.of(System.getProperty("java.io.tmpdir")).toRealPath();
        if (!target.startsWith(temporaryRoot) || !target.getFileName().toString().startsWith("junit-")
                || java.nio.file.Files.isSymbolicLink(directory)) throw new java.io.IOException("unexpected test working directory");
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(2).toNanos();
        while (true) {
            try {
                // Only flat files in this exact JUnit-owned fixture directory.
                try (var children = java.nio.file.Files.list(target)) {
                    for (Path file : children.toList()) {
                        if (!java.nio.file.Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS))
                            throw new java.io.IOException("unexpected fixture entry");
                        java.nio.file.Files.deleteIfExists(file);
                    }
                }
                java.nio.file.Files.deleteIfExists(target); return;
            } catch (java.nio.file.FileSystemException held) {
                if (System.nanoTime() >= deadline) throw held;
                Thread.sleep(20);
            }
        }
    }

    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "handoff-ok", "handoff-bad-json", "handoff-wait", "handoff-nonzero", "handoff-flood" -> {
                String executable = Path.of(System.getProperty("java.home"), "bin",
                        System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
                Process child = new ProcessBuilder(executable, "-cp", System.getProperty("java.class.path"),
                        ProcessFixture.class.getName(), "sleep")
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD).start();
                java.nio.file.Files.writeString(Path.of(args[1]), Long.toString(child.pid()));
                System.out.println("child=" + child.pid());
                System.out.flush();
                Thread.sleep(700); // Give the runner time to observe the harmless child.
                switch (args[0]) {
                    case "handoff-wait" -> Thread.sleep(60000);
                    case "handoff-nonzero" -> System.exit(7);
                    case "handoff-flood" -> { for (int i=0; i<20000; i++) System.out.println("x".repeat(64)); }
                    case "handoff-bad-json" -> System.out.println("not-json");
                    default -> { }
                }
            }
            case "echo" -> {
                System.out.println(String.join("|", Arrays.copyOfRange(args, 1, args.length)));
                System.err.println("separate-stderr");
            }
            case "context" -> {
                System.out.println("cwd=" + Path.of("").toRealPath());
                System.out.println("lang=" + System.getenv("LANG"));
                System.out.println("javaOptions=" + System.getenv("JAVA_TOOL_OPTIONS"));
                System.out.println("stdin=" + System.in.read());
            }
            case "exit" -> System.exit(7);
            case "flood" -> {
                Thread error = new Thread(() -> {
                    for (int i = 0; i < 20000; i++) System.err.println("e".repeat(64));
                });
                error.start();
                for (int i = 0; i < 20000; i++) System.out.println("o".repeat(64));
                error.join();
            }
            case "sleep" -> {
                System.out.println("pid=" + ProcessHandle.current().pid());
                System.out.flush();
                Thread.sleep(60000);
            }
            case "child", "orphan", "legal-helper" -> {
                String executable = Path.of(System.getProperty("java.home"), "bin",
                        System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
                ProcessBuilder childBuilder = new ProcessBuilder(executable, "-cp", System.getProperty("java.class.path"),
                        ProcessFixture.class.getName(), "sleep");
                if ("legal-helper".equals(args[0])) {
                    childBuilder.redirectOutput(ProcessBuilder.Redirect.DISCARD)
                            .redirectError(ProcessBuilder.Redirect.DISCARD);
                } else childBuilder.inheritIO();
                Process child = childBuilder.start();
                System.out.println("child=" + child.pid());
                System.out.flush();
                if ("child".equals(args[0])) child.waitFor();
                else Thread.sleep(500);
            }
            default -> throw new IllegalArgumentException("unknown fixture mode");
        }
    }
}
