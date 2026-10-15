// PTY acceptance only. Never writes accounts, never imported by a launcher.
import { spawn } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import { root, javaExecutable, terminalQuestion } from "./dev.mjs";

await terminalQuestion("Fixture username: ");
if (process.stdin.isRaw || process.stdin.listenerCount("keypress"))
  throw new Error("Terminal input was not released");
const version = fs.readFileSync(path.join(root, "VERSION"), "utf8").trim();
const child = spawn(
  javaExecutable(),
  [
    "-Dloader.main=com.chaoslab.console.security.PasswordHashTool",
    "-cp",
    path.join(root, `backend/target/chaoslab-backend-${version}.jar`),
    "org.springframework.boot.loader.launch.PropertiesLauncher",
  ],
  { cwd: root, stdio: "inherit", windowsHide: true },
);
child.on("error", () => {
  process.exitCode = 1;
});
child.on("exit", (code) => {
  process.exitCode = code ?? 1;
});
