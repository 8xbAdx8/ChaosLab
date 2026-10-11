// Real packaged Spring/JPA/Flyway/Security + Vite. Random isolated test accounts only.
// Does not import or invoke any real engine, wrapper, SSH, Docker or VM command.
import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import { spawn, spawnSync } from "node:child_process";
import { randomUUID } from "node:crypto";
import os from "node:os";
import { root, javaExecutable } from "./dev.mjs";

const privateFile = (name) => path.join(root, ".local/dev", name);
const delay = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
const hold = process.argv.includes("--hold"); // Screenshot QA only, never used by public launchers.
const setupOnly = process.argv.includes("--setup-only");
if (
  process.argv.slice(2).some((arg) => !["--hold", "--setup-only"].includes(arg))
)
  throw new Error("Unknown smoke option");

if (!fs.existsSync(privateFile("accounts.json"))) {
  const xml = fs.readFileSync(
    path.join(
      root,
      "backend/target/surefire-reports/TEST-com.chaoslab.console.fakedemo.FakeDemoApiTests.xml",
    ),
    "utf8",
  );
  const classpath = /name="java.class.path" value="([^"]+)"/
    .exec(xml)?.[1]
    ?.replaceAll("&amp;", "&")
    .replaceAll("&quot;", '"');
  assert.ok(
    classpath,
    "Run backend verify first; actual test classpath is required.",
  );
  const result = spawnSync(
    javaExecutable(),
    ["-cp", classpath, "com.chaoslab.console.fakedemo.DeliveryTestAccounts"],
    { cwd: path.join(root, "backend"), stdio: "inherit", windowsHide: true },
  );
  assert.equal(result.status, 0);
} else
  assert.ok(
    fs.existsSync(privateFile("smoke-credentials.json")),
    "Never reuse developer accounts for acceptance",
  );
if (setupOnly) process.exit(0);

const credentials = JSON.parse(
  fs.readFileSync(privateFile("smoke-credentials.json"), "utf8"),
);
const sentinel = spawn(
  process.execPath,
  ["-e", "setInterval(() => {}, 1000)"],
  { stdio: "ignore", windowsHide: true },
);
const base = "http://127.0.0.1:15173";
let cookies = new Map();
async function request(
  route,
  { method = "GET", body, key, token, expected = 200 } = {},
) {
  const headers = {
    Cookie: [...cookies].map(([key, value]) => `${key}=${value}`).join("; "),
  };
  if (token) headers[token.headerName] = token.token;
  if (key) headers["Idempotency-Key"] = key;
  if (body !== undefined)
    headers["Content-Type"] =
      body instanceof URLSearchParams
        ? "application/x-www-form-urlencoded"
        : "application/json";
  const response = await fetch(base + route, {
    method,
    headers,
    body:
      body === undefined
        ? undefined
        : body instanceof URLSearchParams
          ? body
          : JSON.stringify(body),
    signal: AbortSignal.timeout(10000),
    redirect: "error",
  });
  for (const cookie of response.headers.getSetCookie()) {
    const [pair] = cookie.split(";");
    const index = pair.indexOf("=");
    cookies.set(pair.slice(0, index), pair.slice(index + 1));
  }
  assert.equal(
    response.status,
    expected,
    `${method} ${route}: unexpected status (raw response not logged)`,
  );
  return response.headers.get("Content-Type")?.includes("json")
    ? response.json()
    : null;
}
async function login(role) {
  cookies = new Map();
  const token = await request("/api/v1/auth/csrf");
  await request("/api/v1/auth/login", {
    method: "POST",
    body: new URLSearchParams({
      username: `demo.${role}`,
      password: credentials.password,
    }),
    token,
  });
  const identity = await request("/api/v1/auth/me");
  assert.equal(identity.executionMode, "FAKE_DEMO");
  assert.equal(identity.realExecutionAllowed, false);
  assert.ok(identity.roles.includes(role.toUpperCase()));
}
async function write(route, body, key, expected = 200) {
  const token = await request("/api/v1/auth/csrf");
  return request(route, { method: "POST", body, key, token, expected });
}
let session;
function launch(mode) {
  // Exercise public OS entry points, not a test server substitute.
  const exe = process.platform === "win32" ? "powershell.exe" : "bash";
  const script = path.join(
    root,
    "scripts",
    `start-${mode}.${process.platform === "win32" ? "ps1" : "sh"}`,
  );
  const args =
    process.platform === "win32"
      ? [
          "-NoProfile",
          "-File",
          script,
          "-BackendPort",
          "18086",
          "-FrontendPort",
          "15173",
        ]
      : [script, "--backend-port", "18086", "--frontend-port", "15173"];
  session = spawn(exe, args, {
    cwd: root,
    stdio: "inherit",
    windowsHide: true,
  });
  session.once("error", () => {
    process.exitCode = 1;
  });
}
async function ready() {
  for (let i = 0; i < 600; i++) {
    assert.equal(session.exitCode, null, "Launcher exited before readiness");
    try {
      const r = await fetch(base + "/actuator/health", {
        signal: AbortSignal.timeout(1000),
      });
      if (r.ok) return;
    } catch {
      /* build/start is bounded by test budget */
    }
    await delay(500);
  }
  throw new Error("Delivery startup timeout");
}
async function stop() {
  if (!session) return;
  const exit = new Promise((resolve) =>
    session.exitCode !== null ? resolve() : session.once("exit", resolve),
  );
  const result = spawn(
    process.execPath,
    [path.join(root, "scripts/dev.mjs"), "stop"],
    { cwd: root, stdio: "inherit" },
  );
  assert.equal(await new Promise((resolve) => result.once("exit", resolve)), 0);
  await Promise.race([
    exit,
    delay(30000).then(() => {
      throw new Error("Launcher did not exit after verified stop");
    }),
  ]);
  assert.equal(
    sentinel.exitCode,
    null,
    "stop must preserve unrelated Node processes",
  );
  session = null;
}
try {
  launch("fake");
  await ready();
  const state = JSON.parse(
    fs.readFileSync(privateFile("session.json"), "utf8"),
  );
  assert.equal(
    (
      await fetch(`http://127.0.0.1:${state.port}/stop/${state.id}`, {
        method: "POST",
      })
    ).status,
    403,
  );
  for (const address of Object.values(os.networkInterfaces())
    .flat()
    .filter((item) => item && !item.internal && item.family === "IPv4"))
    for (const port of [18086, 15173]) {
      await assert.rejects(
        fetch(`http://${address.address}:${port}/actuator/health`, {
          signal: AbortSignal.timeout(1000),
        }),
        "Services must not accept connections on a non-loopback address",
      );
    }
  await request("/api/v1/console/overview", { expected: 401 });
  await login("admin");
  const definition = {
    name: "M6 local Fake delivery demo",
    hypothesis: "SIMULATED lifecycle; no physical CPU claim",
    targetId: "00000000-0000-0000-0000-000000000401",
    scenarioId: "00000000-0000-0000-0000-000000000101",
    durationSeconds: 30,
    parameters: { percent: 10 },
  };
  const experiment = await write(
    "/api/v1/experiments",
    definition,
    undefined,
    201,
  );
  await write(`/api/v1/experiments/${experiment.id}/validation`);
  assert.equal(
    (await write(`/api/v1/experiments/${experiment.id}/dry-run`)).accepted,
    true,
  );
  const route = `/api/v1/experiments/${experiment.id}/executions`;
  const key = `m6-${randomUUID()}`;
  const execution = await write(route, undefined, key, 201);
  assert.equal(execution.status, "RUNNING");
  const replay = await write(route, undefined, key, 200);
  assert.equal(replay.id, execution.id);
  const destroy = await write(`${route}/${execution.id}/destroy`);
  assert.equal(destroy.status, "SUCCESS");
  const report = await write(
    `${route}/${execution.id}/reports`,
    undefined,
    `report-${randomUUID()}`,
    201,
  );
  assert.equal(report.executionMode, "SIMULATED");
  assert.equal(report.metricsStatus, "NOT_COLLECTED");
  const analysis = await request(
    `/api/v1/console/reports/${report.id}/analysis`,
  );
  assert.equal(analysis.recovery.physicalRecovery, "UNKNOWN");
  assert.equal(analysis.recovery.baselineCpuPercent, null);
  assert.ok(analysis.audits.length > 0);
  // Even a logged-in ADMIN with valid CSRF cannot access REAL or bypass missing CSRF.
  await request("/api/v1/experiments", {
    method: "POST",
    body: definition,
    expected: 403,
  });
  await write("/api/v1/real/authorization", {}, undefined, 403);
  await login("viewer");
  await request(`/api/v1/console/executions/${execution.id}`);
  await write("/api/v1/experiments", definition, undefined, 403);
  await write(`${route}/${execution.id}/destroy`, undefined, undefined, 403);
  await write("/api/v1/auth/logout", undefined, undefined, 204);
  await request("/api/v1/console/overview", { expected: 401 });
  fs.writeFileSync(
    privateFile("smoke-result.json"),
    JSON.stringify(
      {
        experimentId: experiment.id,
        executionId: execution.id,
        reportId: report.id,
        mode: "LOCAL_FAKE_ACCEPTANCE_NOT_FIELD_EVIDENCE",
      },
      null,
      2,
    ),
    { mode: 0o600 },
  );
  if (hold) {
    console.log(
      "Screenshot session READY; random test credentials remain private. Stop with stop-dev.",
    );
    await new Promise((resolve) => session.once("exit", resolve));
    session = null;
  } else {
    console.log(
      "Fake API/RBAC/report PASS; immediately stopping to verify committed records survive restart.",
    );
    await stop();
    assert.ok(
      fs.existsSync(
        path.join(root, ".local/chaoslab-fake-demo/chaoslab_fake_demo.mv.db"),
      ),
    );
    launch("fake");
    await ready();
    await login("viewer");
    assert.equal(
      (await request(`/api/v1/console/executions/${execution.id}`)).status,
      "SUCCESS",
    );
    assert.equal(
      (await request(`/api/v1/console/reports/${report.id}/analysis`)).report
        .id,
      report.id,
    );
    await stop();
    const database = fs.statSync(
      path.join(root, ".local/chaoslab-fake-demo/chaoslab_fake_demo.mv.db"),
    );
    launch("history");
    await ready();
    const identity = await request("/api/v1/auth/me");
    assert.equal(identity.mode, "LOCAL_HISTORY");
    const history = await request("/api/v1/console/history/m1-core");
    assert.equal(
      history.evidenceSource.sha256,
      "b57cac027022e3c3b009b6daf20eef22c9146d2c7f32dd5c579841fe6eb84f33",
    );
    await request("/api/v1/console/experiments", { expected: 401 });
    await stop();
    assert.equal(
      fs.statSync(
        path.join(root, ".local/chaoslab-fake-demo/chaoslab_fake_demo.mv.db"),
      ).mtimeMs,
      database.mtimeMs,
    );
    console.log(
      "M6 DELIVERY PASS: packaged backend, ADMIN/VIEWER/CSRF, Vite proxy, idempotency, persistent H2 restart, honest report, DB-free SHA history, owned shutdown.",
    );
  }
} finally {
  await stop();
  sentinel.kill();
}
