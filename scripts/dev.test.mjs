import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import net from "node:net";
import {
  options,
  validateAccounts,
  rejectOverrides,
  privateDirectory,
  freePort,
  runtimeEnvironment,
  controlAuthorized,
  javaExecutable,
} from "./dev.mjs";

test("fixed modes, unique bounded ports; no argv/root/profile/database overrides", () => {
  assert.equal(options(["start", "history"]).mode, "history");
  for (const args of [
    ["start", "real"],
    ["start", "fake", "--engine", "blade"],
    ["start", "fake", "--backend-port", "0"],
    ["start", "fake", "--backend-port", "5173"],
    ["start", "fake", "--backend-port", "9000", "--backend-port", "9001"],
  ])
    assert.throws(() => options(args));
});
test("private accounts require complete BCrypt pairs and distinct identities; no defaults", () => {
  const admin = { username: "chosen.admin", hash: "$2a$12$" + "a".repeat(53) };
  assert.deepEqual(validateAccounts({ admin }), { admin });
  for (const config of [
    null,
    {},
    { admin: { ...admin, hash: "plaintext" } },
    { admin, viewer: admin },
    { admin, real: true },
    { admin: { ...admin, password: "secret" } },
  ])
    assert.throws(() => validateAccounts(config));
});
test("unsafe inherited configuration is rejected without echoing its value", () => {
  for (const key of [
    "SPRING_DATASOURCE_URL",
    "CHAOSLAB_ENGINE",
    "SERVER_ADDRESS",
    "JAVA_TOOL_OPTIONS",
    "NODE_OPTIONS",
    "VITE_API_BASE_URL",
    "API_PROXY_TARGET",
    "LOADER_MAIN",
    "MAVEN_ARGS",
  ])
    assert.throws(
      () => rejectOverrides({ [key]: "private-secret-value" }),
      (error) => !error.message.includes("private-secret-value"),
    );
});
test(
  "private paths reject redirects rather than touching another data source",
  { skip: process.platform === "win32" },
  () => {
    const base = fs.mkdtempSync(path.join(os.tmpdir(), "chaoslab-path-"));
    privateDirectory(path.join(base, "private"));
    fs.symlinkSync(path.join(base, "private"), path.join(base, "redirect"));
    assert.throws(() =>
      privateDirectory(path.join(base, "redirect", "nested")),
    );
    assert.equal(fs.existsSync(path.join(base, "private", "nested")), false);
  },
);
test("port conflict fails without killing the listener", async () => {
  const server = net.createServer();
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  try {
    await assert.rejects(freePort(server.address().port), /occupied/);
    assert.equal(server.listening, true);
  } finally {
    await new Promise((resolve) => server.close(resolve));
  }
});
test("shutdown authorization needs exact unguessable session token; no PID kill contract", () => {
  assert.equal(controlAuthorized("Bearer correct", "correct"), true);
  for (const value of [
    undefined,
    "correct",
    "Bearer incorrect",
    "Bearer correct\n",
  ])
    assert.equal(controlAuthorized(value, "correct"), false);
  const source = fs.readFileSync(new URL("./dev.mjs", import.meta.url), "utf8");
  assert.doesNotMatch(source, /process\.kill\(|taskkill|pkill|killall/);
});
test("runtime is rebuilt with fixed proxy and hash-only credentials", () => {
  const env = runtimeEnvironment(
    { admin: { username: "chosen", hash: "hash-only" } },
    8080,
  );
  assert.equal(env.API_PROXY_TARGET, "http://127.0.0.1:8080");
  assert.equal(env.CHAOSLAB_ADMIN_PASSWORD_HASH, "hash-only");
  assert.equal(env.CHAOSLAB_ADMIN_PASSWORD, undefined);
  assert.equal(env.SPRING_PROFILES_ACTIVE, undefined);
});
test("missing JAVA_HOME fails before service launch", () => {
  const previous = process.env.JAVA_HOME;
  delete process.env.JAVA_HOME;
  try {
    assert.throws(javaExecutable, /JAVA_HOME/);
  } finally {
    if (previous !== undefined) process.env.JAVA_HOME = previous;
  }
});
