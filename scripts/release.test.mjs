// Release failures run in disposable fixtures, never edit the checked-in archive or user data.
import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { spawnSync } from "node:child_process";
import { randomUUID } from "node:crypto";
import { root } from "./dev.mjs";

function fixture() {
  const directory = fs.mkdtempSync(
    path.join(os.tmpdir(), "chaoslab-rc-doctor-"),
  );
  for (const sub of ["scripts", "backend", "frontend", "docs/evidence"])
    fs.mkdirSync(path.join(directory, sub), { recursive: true, mode: 0o700 });
  for (const file of [
    "scripts/dev.mjs",
    "VERSION",
    "docs/evidence/m1-core-real-20261010.json",
  ])
    fs.copyFileSync(path.join(root, file), path.join(directory, file));
  for (const file of [
    "backend/pom.xml",
    "backend/mvnw",
    "backend/mvnw.cmd",
    "frontend/package.json",
    "frontend/package-lock.json",
  ])
    fs.writeFileSync(path.join(directory, file), "fixture", { mode: 0o600 });
  return directory;
}
function cleanEnvironment() {
  const env = { ...process.env };
  for (const key of Object.keys(env))
    if (
      /^(SPRING_|CHAOSLAB_|SERVER_|MANAGEMENT_|VITE_|API_PROXY_TARGET$|JAVA_TOOL_OPTIONS$|JDK_JAVA_OPTIONS$|_JAVA_OPTIONS$|JAVA_OPTIONS$|NODE_OPTIONS$|MAVEN_OPTS$|MAVEN_ARGS$|CLASSPATH$|LOADER_)/i.test(
        key,
      )
    )
      delete env[key];
  return env;
}
function doctor(directory, overrides = {}) {
  return spawnSync(
    process.execPath,
    [path.join(directory, "scripts/dev.mjs"), "doctor", "history"],
    {
      env: { ...cleanEnvironment(), ...overrides },
      encoding: "utf8",
      timeout: 10000,
      windowsHide: true,
    },
  );
}
function rejects(result, message) {
  assert.equal(result.status, 1);
  assert.match(result.stderr, message);
  assert.doesNotMatch(result.stdout + result.stderr, /rc-secret-value/);
}

test("RC version agrees across trusted VERSION, Maven and npm lock; no dependency upgrade needed", () => {
  const version = fs.readFileSync(path.join(root, "VERSION"), "utf8").trim();
  const pom = fs.readFileSync(path.join(root, "backend/pom.xml"), "utf8");
  assert.equal(
    /<artifactId>chaoslab-backend<\/artifactId>\s*<version>([^<]+)<\/version>/.exec(
      pom,
    )?.[1],
    version,
  );
  const pkg = JSON.parse(
    fs.readFileSync(path.join(root, "frontend/package.json")),
  );
  const lock = JSON.parse(
    fs.readFileSync(path.join(root, "frontend/package-lock.json")),
  );
  assert.equal(pkg.version, version);
  assert.equal(lock.version, version);
  assert.equal(lock.packages[""].version, version);
});

test("explicit local test accounts use bounded stdin, keep no plaintext file and never overwrite", () => {
  const report = path.join(
    root,
    "backend/target/surefire-reports/TEST-com.chaoslab.console.fakedemo.FakeDemoApiTests.xml",
  );
  assert.ok(
    fs.existsSync(report),
    "Run backend verify before release acceptance",
  );
  const classpath = /name="java.class.path" value="([^"]+)"/
    .exec(fs.readFileSync(report, "utf8"))?.[1]
    ?.replaceAll("&amp;", "&")
    .replaceAll("&quot;", '"');
  assert.ok(classpath);
  const directory = fs.mkdtempSync(
    path.join(os.tmpdir(), "chaoslab-local-account-"),
  );
  const backend = path.join(directory, "backend");
  fs.mkdirSync(backend);
  const password = "Fixture-" + randomUUID();
  const config = path.join(directory, ".local/dev/accounts.json");
  const invoke = (input) =>
    spawnSync(
      path.join(
        process.env.JAVA_HOME,
        "bin",
        process.platform === "win32" ? "java.exe" : "java",
      ),
      [
        "-cp",
        classpath,
        "com.chaoslab.console.fakedemo.DeliveryTestAccounts",
        "--stdin-local",
      ],
      {
        cwd: backend,
        input,
        encoding: "utf8",
        timeout: 30000,
        windowsHide: true,
      },
    );
  for (const input of [
    "{" + password,
    "x".repeat(4097),
    JSON.stringify({ username: "fixture.admin", password: "short" }),
  ]) {
    const result = invoke(input);
    assert.equal(result.status, 1);
    assert.doesNotMatch(result.stdout + result.stderr, new RegExp(password));
    assert.equal(fs.existsSync(config), false);
  }
  const input = JSON.stringify({ username: "fixture.admin", password });
  const configured = invoke(input);
  assert.equal(configured.status, 0);
  const original = fs.readFileSync(config, "utf8");
  assert.ok(!original.includes(password));
  const accounts = JSON.parse(original);
  assert.equal(accounts.admin.username, "fixture.admin");
  assert.match(accounts.admin.hash, /^\$2a\$12\$/);
  assert.equal(
    fs.existsSync(path.join(directory, ".local/dev/smoke-credentials.json")),
    false,
  );
  assert.equal(invoke(input).status, 1);
  assert.equal(fs.readFileSync(config, "utf8"), original);
  assert.ok(!(configured.stdout + configured.stderr).includes(password));
});
test("doctor fails before private data writes when JAVA_HOME is absent", () => {
  const directory = fixture(),
    env = cleanEnvironment();
  delete env.JAVA_HOME;
  rejects(
    spawnSync(
      process.execPath,
      [path.join(directory, "scripts/dev.mjs"), "doctor", "history"],
      { env, encoding: "utf8", timeout: 10000, windowsHide: true },
    ),
    /JAVA_HOME is required/,
  );
  assert.equal(fs.existsSync(path.join(directory, ".local")), false);
});
const legacyJava =
  process.env.JAVA_HOME_17_X64 ||
  (process.platform === "win32" ? "C:/Program Files/Java/jdk-17.0.18" : "");
test(
  "doctor rejects actual JDK 17 before any service or database",
  {
    skip:
      !legacyJava || !fs.existsSync(legacyJava)
        ? "JDK 17 not available on this runner"
        : false,
  },
  () => {
    rejects(doctor(fixture(), { JAVA_HOME: legacyJava }), /working JDK 21/);
  },
);
test("public doctor entry point explains missing Node without installing anything", () => {
  const win = process.platform === "win32";
  const executable = win
    ? path.join(
        process.env.SystemRoot,
        "System32/WindowsPowerShell/v1.0/powershell.exe",
      )
    : "/bin/bash";
  const file = path.join(root, `scripts/doctor.${win ? "ps1" : "sh"}`);
  const args = win
    ? ["-NoProfile", "-ExecutionPolicy", "RemoteSigned", "-File", file]
    : [file];
  const result = spawnSync(executable, args, {
    env: { ...cleanEnvironment(), PATH: "" },
    encoding: "utf8",
    // A loaded Windows hosted runner may spend over 10s initializing PowerShell.
    // Bound the test harness, not the production launcher or its safety checks.
    timeout: 30000,
    windowsHide: true,
  });
  assert.ifError(result.error);
  assert.notEqual(result.status, 0);
  assert.match(result.stdout + result.stderr, /Node\.js/);
});
test("doctor explains missing npm instead of installing globally", () => {
  rejects(doctor(fixture(), { PATH: "" }), /npm is missing/);
});
test("first Fake doctor explains absent accounts, never creates a default user", () => {
  const directory = fixture();
  rejects(
    spawnSync(
      process.execPath,
      [path.join(directory, "scripts/dev.mjs"), "doctor", "fake"],
      {
        env: cleanEnvironment(),
        encoding: "utf8",
        timeout: 10000,
        windowsHide: true,
      },
    ),
    /No local accounts configured.*start-fake/,
  );
  assert.equal(
    fs.existsSync(path.join(directory, ".local/dev/accounts.json")),
    false,
  );
});
test("doctor rejects unsafe inherited deployment settings without revealing values", () => {
  rejects(
    doctor(fixture(), { CHAOSLAB_ENGINE: "rc-secret-value" }),
    /Unsafe deployment overrides/,
  );
});
test("archive mismatch uses a disposable copy; real source bytes are never changed", () => {
  const directory = fixture();
  fs.writeFileSync(
    path.join(directory, "docs/evidence/m1-core-real-20261010.json"),
    "{}",
  );
  rejects(doctor(directory), /Archived M1 SHA mismatch/);
});
test("existing startup and session markers fail closed, without erasing them", () => {
  for (const marker of ["start.lock", "session.json"]) {
    const directory = fixture(),
      local = path.join(directory, ".local/dev");
    fs.mkdirSync(local, { recursive: true, mode: 0o700 });
    fs.writeFileSync(path.join(local, marker), "fixture", { mode: 0o600 });
    rejects(doctor(directory), /managed\/stale session or startup exists/);
    assert.equal(fs.readFileSync(path.join(local, marker), "utf8"), "fixture");
  }
});
test(
  "Unix overly readable private directory is rejected",
  {
    skip:
      process.platform === "win32"
        ? "NTFS ACL requires separate read-only OS verification, not POSIX chmod"
        : false,
  },
  () => {
    const directory = fixture(),
      local = path.join(directory, ".local/dev");
    fs.mkdirSync(local, { recursive: true, mode: 0o700 });
    fs.chmodSync(local, 0o755);
    rejects(doctor(directory), /accessible by other users/);
  },
);
