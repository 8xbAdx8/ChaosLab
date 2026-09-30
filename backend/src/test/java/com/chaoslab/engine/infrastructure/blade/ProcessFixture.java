package com.chaoslab.engine.infrastructure.blade;

import java.nio.file.Path;
import java.util.Arrays;

/** Harmless child JVM used by transport tests; never invokes Blade or Docker. */
public final class ProcessFixture {
    public static void main(String[] args) throws Exception {
        switch (args[0]) {
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
            case "child", "orphan" -> {
                String executable = Path.of(System.getProperty("java.home"), "bin",
                        System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
                Process child = new ProcessBuilder(executable, "-cp", System.getProperty("java.class.path"),
                        ProcessFixture.class.getName(), "sleep").inheritIO().start();
                System.out.println("child=" + child.pid());
                System.out.flush();
                if ("child".equals(args[0])) child.waitFor();
                else Thread.sleep(500);
            }
            default -> throw new IllegalArgumentException("unknown fixture mode");
        }
    }
}
