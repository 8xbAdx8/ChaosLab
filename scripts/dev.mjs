#!/usr/bin/env node
// One foreground session owns two direct children. stop never trusts a saved PID.
import { spawn, spawnSync } from "node:child_process";
import { createHash, randomBytes, timingSafeEqual } from "node:crypto";
import { createServer } from "node:http";
import net from "node:net";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { createInterface } from "node:readline/promises";

export const root = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  "..",
);
const local = path.join(root, ".local", "dev");
const accountFile = path.join(local, "accounts.json");
const stateFile = path.join(local, "session.json");
const lockFile = path.join(local, "start.lock");
const jar = path.join(
  root,
  "backend/target/chaoslab-backend-0.0.1-SNAPSHOT.jar",
);
const windows = process.platform === "win32";
const fail = (message) => {
  throw new Error(message);
};
const wait = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

export function options(args) {
  const [command = "doctor", mode = "fake", ...flags] = args;
  if (
    !["doctor", "start", "stop", "configure", "build"].includes(command) ||
    !["fake", "history"].includes(mode)
  )
    fail("Use doctor/start/stop/configure/build and fake/history only.");
  const result = { command, mode, backend: 8080, frontend: 5173 };
  const seen = new Set();
  for (let i = 0; i < flags.length; i += 2) {
    const key = { "--backend-port": "backend", "--frontend-port": "frontend" }[
      flags[i]
    ];
    if (!key || seen.has(key) || !/^\d+$/.test(flags[i + 1] ?? ""))
      fail("Only unique --backend-port/--frontend-port integers are accepted.");
    seen.add(key);
    result[key] = Number(flags[i + 1]);
    if (result[key] < 1024 || result[key] > 65535)
      fail("Ports must be between 1024 and 65535.");
  }
  if (result.backend === result.frontend)
    fail("Backend and frontend need distinct ports.");
  return result;
}

export function validateAccounts(value) {
  if (
    !value ||
    typeof value !== "object" ||
    Array.isArray(value) ||
    Object.keys(value).some((key) => !["admin", "viewer"].includes(key))
  )
    fail("Invalid private accounts file; use configure.");
  for (const role of ["admin", "viewer"]) {
    const user = value[role];
    if (!user && role === "viewer") continue;
    if (
      !user ||
      Object.keys(user).sort().join(",") !== "hash,username" ||
      typeof user.username !== "string" ||
      typeof user.hash !== "string" ||
      !/^[A-Za-z0-9._-]{1,64}$/.test(user.username) ||
      !/^\$2[aby]\$(10|11|12|13|14)\$[./A-Za-z0-9]{53}$/.test(user.hash)
    )
      fail(
        "Configure a username and BCrypt cost 10–14 hash (no plaintext passwords).",
      );
  }
  if (
    value.viewer?.username.toLowerCase() === value.admin.username.toLowerCase()
  )
    fail("ADMIN and VIEWER must be different users.");
  return value;
}

export function rejectOverrides(env = process.env) {
  // The launch contract, not arbitrary caller environment, selects engine/database/profile/proxy.
  const blocked = Object.keys(env).filter((key) =>
    /^(SPRING_|CHAOSLAB_|SERVER_|MANAGEMENT_|VITE_|API_PROXY_TARGET$|JAVA_TOOL_OPTIONS$|JDK_JAVA_OPTIONS$|_JAVA_OPTIONS$|JAVA_OPTIONS$|NODE_OPTIONS$|MAVEN_OPTS$|MAVEN_ARGS$|CLASSPATH$|LOADER_)/i.test(
      key,
    ),
  );
  if (blocked.length) {
    // Report fixed key names/families, never their values or arbitrary credential suffixes.
    const families = [
      ...new Set(
        blocked.map((key) => {
          const normalized = key.toUpperCase();
          const prefix =
            /^(SPRING_|CHAOSLAB_|SERVER_|MANAGEMENT_|VITE_|LOADER_)/.exec(
              normalized,
            );
          return prefix ? `${prefix[1]}*` : normalized;
        }),
      ),
    ];
    fail(
      `Unsafe deployment overrides detected (${families.join(", ")}). Open a clean terminal and remove these overrides. Values are not printed.`,
    );
  }
  for (const dir of [root, path.join(root, "frontend")]) {
    if (
      fs
        .readdirSync(dir)
        .some((name) => /^\.env(?:\.|$)/.test(name) && name !== ".env.example")
    )
      fail(
        "Private Vite .env overrides detected. Use a clean checkout for the fixed local launcher.",
      );
  }
}

export function privateDirectory(directory) {
  // Fail before any write if an existing parent redirects into another data source.
  let current = path.parse(directory).root;
  for (const part of path.relative(current, directory).split(path.sep)) {
    current = path.join(current, part);
    if (
      fs.existsSync(current) &&
      (fs.lstatSync(current).isSymbolicLink() ||
        !fs.statSync(current).isDirectory())
    )
      fail("Local data path must contain real directories, never symlinks.");
  }
  fs.mkdirSync(directory, { recursive: true, mode: 0o700 });
  if (!windows && fs.statSync(directory).mode & 0o077)
    fail(
      "Private local directory is accessible by other users; set its permission to 0700.",
    );
}

function privateRead(file) {
  if (!fs.existsSync(file)) return null;
  const stat = fs.lstatSync(file);
  if (
    stat.isSymbolicLink() ||
    !stat.isFile() ||
    stat.size > 4096 ||
    (!windows && stat.mode & 0o077)
  )
    fail("Private configuration must be a small owner-only regular file.");
  try {
    return JSON.parse(fs.readFileSync(file, "utf8"));
  } catch {
    fail("Invalid private configuration; do not paste passwords into JSON.");
  }
}

export function javaExecutable() {
  if (!process.env.JAVA_HOME)
    fail(
      "JAVA_HOME is required. Install a JDK 21+ and point JAVA_HOME to the JDK directory.",
    );
  const java = path.join(
    process.env.JAVA_HOME,
    "bin",
    windows ? "java.exe" : "java",
  );
  const result = spawnSync(java, ["-version"], {
    encoding: "utf8",
    windowsHide: true,
  });
  const version = /version "(\d+)/.exec(result.stderr ?? "")?.[1];
  if (result.status !== 0 || !version || Number(version) < 21)
    fail("JAVA_HOME must contain a working JDK 21+ (Java 21 is CI-tested).");
  if (
    !fs.existsSync(
      path.join(process.env.JAVA_HOME, "bin", windows ? "javac.exe" : "javac"),
    )
  )
    fail("A full JDK is required, not a JRE.");
  return java;
}

export async function freePort(port) {
  const server = net.createServer();
  await new Promise((resolve, reject) => {
    server.once("error", () =>
      reject(
        new Error(
          `Port ${port} is occupied; stop its owner or choose another local port.`,
        ),
      ),
    );
    server.listen(port, "127.0.0.1", resolve);
  });
  await new Promise((resolve) => server.close(resolve));
}

function checkFiles() {
  for (const file of [
    "backend/pom.xml",
    "backend/mvnw",
    "backend/mvnw.cmd",
    "frontend/package-lock.json",
    "frontend/package.json",
    "docs/evidence/m1-core-real-20261010.json",
  ])
    if (!fs.existsSync(path.join(root, file)))
      fail("Incomplete checkout; clone the entire repository.");
  const sha = createHash("sha256")
    .update(
      fs.readFileSync(
        path.join(root, "docs/evidence/m1-core-real-20261010.json"),
      ),
    )
    .digest("hex");
  if (
    sha !== "b57cac027022e3c3b009b6daf20eef22c9146d2c7f32dd5c579841fe6eb84f33"
  )
    fail("Archived M1 SHA mismatch. History evidence must not be modified.");
}

export async function doctor(opts, requireAccounts = true) {
  rejectOverrides();
  checkFiles();
  const [major, minor] = process.versions.node.split(".").map(Number);
  if (major < 22 || (major === 22 && minor < 12))
    fail("Install Node.js 22.12+ (Node 24 is CI-tested).");
  javaExecutable();
  const npm = spawnSync(
    windows ? (process.env.ComSpec ?? "cmd.exe") : "npm",
    windows ? ["/d", "/s", "/c", "npm --version"] : ["--version"],
    { encoding: "utf8", windowsHide: true },
  );
  if (npm.status !== 0)
    fail("npm is missing; install the Node.js distribution including npm.");
  privateDirectory(local);
  if (fs.existsSync(stateFile) || fs.existsSync(lockFile))
    fail(
      "A managed/stale session or startup exists. Stop it first; no dependency reinstall or second service launch is allowed.",
    );
  if (opts.mode === "fake") {
    privateDirectory(path.join(root, ".local/chaoslab-fake-demo"));
    if (requireAccounts) validateAccounts(privateRead(accountFile));
  }
  await freePort(opts.backend);
  await freePort(opts.frontend);
  console.log(
    "Environment PASS: JDK 21+, Node/npm, Maven Wrapper, archive SHA, loopback ports, fixed local paths.",
  );
  if (!fs.existsSync(path.join(root, "frontend/node_modules/vite/bin/vite.js")))
    console.log(
      "Frontend dependencies will be installed from package-lock.json on start.",
    );
}

function run(executable, args, cwd, env = process.env) {
  return new Promise((resolve, reject) => {
    const child = spawn(executable, args, {
      cwd,
      env,
      stdio: "inherit",
      windowsHide: true,
    });
    child.once("error", () =>
      reject(
        new Error(
          "Build/tool could not start; check Java/npm and Maven Wrapper.",
        ),
      ),
    );
    child.once("exit", (code) =>
      code === 0
        ? resolve()
        : reject(
            new Error(
              "Build/tool failed. No service was started; fix dependency/build errors first.",
            ),
          ),
    );
  });
}

export async function build() {
  rejectOverrides();
  checkFiles();
  javaExecutable();
  // Controlled cmd wrapper only for .cmd build tools. Runtime children never use a shell.
  if (windows)
    await run(
      process.env.ComSpec ?? "cmd.exe",
      ["/d", "/s", "/c", "mvnw.cmd -B package -DskipTests"],
      path.join(root, "backend"),
    );
  else
    await run(
      "bash",
      ["./mvnw", "-B", "package", "-DskipTests"],
      path.join(root, "backend"),
    );
  privateDirectory(local);
  const stampFile = path.join(local, "npm-installed.json");
  const digest = createHash("sha256")
    .update(fs.readFileSync(path.join(root, "frontend/package-lock.json")))
    .digest("hex");
  const installed = privateRead(stampFile);
  if (
    installed?.digest !== digest ||
    !fs.existsSync(path.join(root, "frontend/node_modules/vite/bin/vite.js")) ||
    !fs.existsSync(path.join(root, "frontend/node_modules/typescript/bin/tsc"))
  ) {
    if (windows)
      await run(
        process.env.ComSpec ?? "cmd.exe",
        ["/d", "/s", "/c", "npm ci --no-audit --no-fund"],
        path.join(root, "frontend"),
      );
    else
      await run(
        "npm",
        ["ci", "--no-audit", "--no-fund"],
        path.join(root, "frontend"),
      );
    fs.writeFileSync(stampFile, JSON.stringify({ digest }), { mode: 0o600 });
  }
}

async function configure(java) {
  if (!process.stdin.isTTY || !process.stdout.isTTY)
    fail(
      "First use requires an interactive terminal: run scripts/start-fake or configure. CI uses isolated random test accounts, never defaults.",
    );
  const readline = createInterface({
    input: process.stdin,
    output: process.stdout,
  });
  try {
    const accounts = {};
    for (const role of ["admin", "viewer"]) {
      if (
        role === "viewer" &&
        (
          await readline.question("Configure an optional VIEWER? [y/N] ")
        ).toLowerCase() !== "y"
      )
        continue;
      const username = await readline.question(
        `${role.toUpperCase()} username (your choice, no default): `,
      );
      console.log(
        "The existing Java tool will ask for a hidden password twice and display its BCrypt hash.",
      );
      readline.pause();
      await run(
        java,
        [
          "-Dloader.main=com.chaoslab.console.security.PasswordHashTool",
          "-cp",
          jar,
          "org.springframework.boot.loader.launch.PropertiesLauncher",
        ],
        root,
      );
      readline.resume();
      const hash = (
        await readline.question(
          "Paste only the generated BCrypt hash (not the password): ",
        )
      ).trim();
      accounts[role] = { username, hash };
    }
    validateAccounts(accounts);
    privateDirectory(local);
    if (fs.existsSync(accountFile))
      fail(
        "Accounts already exist. No overwrite: stop services and explicitly archive your private config before reconfiguring.",
      );
    fs.writeFileSync(accountFile, JSON.stringify(accounts), {
      mode: 0o600,
      flag: "wx",
    });
    console.log(
      "Private BCrypt configuration saved under ignored .local/dev. No plaintext password saved.",
    );
    if (windows)
      console.log(
        "Windows: keep this checkout in your private user folder; restrict .local ACL for shared machines (see delivery guide).",
      );
  } finally {
    readline.close();
  }
}

export function runtimeEnvironment(accounts, port) {
  const env = Object.fromEntries(
    Object.entries(process.env).filter(
      ([key]) =>
        !/^(SPRING_|CHAOSLAB_|SERVER_|MANAGEMENT_|VITE_|API_PROXY_TARGET$|JAVA_TOOL_OPTIONS$|JDK_JAVA_OPTIONS$|_JAVA_OPTIONS$|JAVA_OPTIONS$|NODE_OPTIONS$|MAVEN_OPTS$|MAVEN_ARGS$|CLASSPATH$|LOADER_)/i.test(
          key,
        ),
    ),
  );
  env.API_PROXY_TARGET = `http://127.0.0.1:${port}`;
  if (accounts)
    for (const role of ["admin", "viewer"])
      if (accounts[role]) {
        env[`CHAOSLAB_${role.toUpperCase()}_USERNAME`] =
          accounts[role].username;
        env[`CHAOSLAB_${role.toUpperCase()}_PASSWORD_HASH`] =
          accounts[role].hash;
      }
  return env;
}

export function controlAuthorized(header, token) {
  const a = Buffer.from(header ?? "");
  const b = Buffer.from(`Bearer ${token}`);
  return a.length === b.length && timingSafeEqual(a, b);
}

async function stop() {
  privateDirectory(local);
  const state = privateRead(stateFile);
  if (!state) {
    if (fs.existsSync(lockFile))
      fail(
        "Startup/build is in progress or its lock is stale. Use Ctrl+C in the original terminal. No PID was killed; do not assume startup was cancelled.",
      );
    console.log("No managed ChaosLab session; nothing stopped.");
    return;
  }
  if (
    Object.keys(state).sort().join(",") !== "id,port,root,token" ||
    state.root !== root ||
    !Number.isInteger(state.port) ||
    state.port < 1024 ||
    state.port > 65535 ||
    !/^[a-f0-9]{64}$/.test(state.token) ||
    !/^[a-f0-9]{32}$/.test(state.id)
  )
    fail("Untrusted session file. No process will be killed.");
  let response;
  try {
    response = await fetch(`http://127.0.0.1:${state.port}/stop/${state.id}`, {
      method: "POST",
      headers: { Authorization: `Bearer ${state.token}` },
      signal: AbortSignal.timeout(5000),
      redirect: "error",
    });
  } catch {
    fail(
      "Session supervisor unavailable. No saved PID was killed. Check ports and archive stale .local/dev/session.json only after confirming its supervisor is gone.",
    );
  }
  if (response.status !== 202)
    fail("Session identity rejected. No process was killed.");
  for (let i = 0; i < 100 && fs.existsSync(stateFile); i++) await wait(250);
  if (fs.existsSync(stateFile))
    fail("Shutdown not confirmed; inspect the original startup terminal.");
  console.log(
    "Managed session stopped; database and unrelated processes preserved.",
  );
}

async function until(url, children, seconds) {
  const deadline = Date.now() + seconds * 1000;
  while (Date.now() < deadline) {
    if (
      children.some(
        (child) => child.exitCode !== null || child.signalCode !== null,
      )
    )
      fail(
        "A service exited during startup. See private backend/frontend logs; no automatic retry.",
      );
    try {
      const res = await fetch(url, {
        signal: AbortSignal.timeout(1500),
        redirect: "error",
      });
      if (res.ok) return;
    } catch {
      /* bounded startup wait */
    }
    await wait(500);
  }
  fail(
    "Service health timeout. Check private .local/dev logs; no service is considered ready.",
  );
}

export async function supervise(opts, accounts) {
  if (fs.existsSync(stateFile))
    fail(
      "A session file already exists. Run stop-dev; never overwrite a live/stale session identity.",
    );
  const children = [];
  const streams = [];
  let stopping = false;
  let server;
  let stateOwned = false;
  let shutdownPromise;
  let finished;
  const completion = new Promise((resolve) => {
    finished = resolve;
  });
  const shutdown = () =>
    (shutdownPromise ??= (async () => {
      stopping = true;
      for (const child of children)
        if (child.exitCode === null && child.signalCode === null)
          child.kill("SIGTERM");
      const deadline = Date.now() + 21000;
      while (
        children.some(
          (child) => child.exitCode === null && child.signalCode === null,
        ) &&
        Date.now() < deadline
      )
        await wait(100);
      for (const child of children)
        if (child.exitCode === null && child.signalCode === null)
          child.kill("SIGKILL");
      await Promise.all(
        children.map((child) =>
          child.exitCode !== null || child.signalCode !== null
            ? Promise.resolve()
            : new Promise((resolve) => child.once("exit", resolve)),
        ),
      );
      for (const stream of streams) stream.end();
      if (server?.listening)
        await new Promise((resolve) => server.close(resolve));
      if (stateOwned) fs.unlinkSync(stateFile);
      finished();
    })());
  const launch = (exe, args, name, env, cwd) => {
    const logFile = path.join(local, `${name}.log`);
    if (fs.existsSync(logFile) && fs.lstatSync(logFile).isSymbolicLink())
      fail("Refusing symlink log.");
    const log = fs.createWriteStream(logFile, { flags: "a", mode: 0o600 });
    streams.push(log);
    const child = spawn(exe, args, {
      cwd,
      env,
      stdio: ["ignore", "pipe", "pipe"],
      windowsHide: true,
    });
    children.push(child);
    child.stdout.pipe(log, { end: false });
    child.stderr.pipe(log, { end: false });
    child.once("error", () => {
      console.error("Service spawn failed.");
      void shutdown();
    });
    child.once("exit", () => {
      if (!stopping) {
        process.exitCode = 1;
        console.error(
          "Managed service exited; stopping its companion. No restart/retry.",
        );
        void shutdown();
      }
    });
  };
  const interrupt = () => {
    void shutdown();
  };
  process.once("SIGINT", interrupt);
  process.once("SIGTERM", interrupt);
  try {
    const token = randomBytes(32).toString("hex");
    const id = randomBytes(16).toString("hex");
    server = createServer((req, res) => {
      // Tiny loopback-only shutdown channel, not a platform/root command API.
      if (
        req.method !== "POST" ||
        req.url !== `/stop/${id}` ||
        !controlAuthorized(req.headers.authorization, token)
      ) {
        res.writeHead(403);
        res.end();
        return;
      }
      res.writeHead(202);
      res.end();
      void shutdown();
    });
    server.requestTimeout = 2000;
    server.headersTimeout = 2000;
    await new Promise((resolve, reject) => {
      server.once("error", reject);
      server.listen(0, "127.0.0.1", resolve);
    });
    fs.writeFileSync(
      stateFile,
      JSON.stringify({ root, id, token, port: server.address().port }),
      { mode: 0o600, flag: "wx" },
    );
    stateOwned = true;
    const env = runtimeEnvironment(accounts, opts.backend);
    const args =
      opts.mode === "fake"
        ? ["-jar", jar]
        : [
            "-Dloader.main=org.chaoslab.historydemo.HistoryDemoApplication",
            "-cp",
            jar,
            "org.springframework.boot.loader.launch.PropertiesLauncher",
          ];
    args.push(
      `--spring.profiles.active=${opts.mode === "fake" ? "fake-demo" : "history-demo"}`,
      "--server.address=127.0.0.1",
      `--server.port=${opts.backend}`,
    );
    if (opts.mode === "fake")
      args.push(
        `--chaoslab.fake-demo.directory=${path.join(root, ".local/chaoslab-fake-demo").replaceAll("\\", "/")}`,
      );
    launch(javaExecutable(), args, "backend", env, path.join(root, "backend"));
    await until(
      `http://127.0.0.1:${opts.backend}/actuator/health`,
      children,
      90,
    );
    const descriptor = await fetch(
      `http://127.0.0.1:${opts.backend}/api/v1/auth/me`,
    ).then((r) => r.json());
    if (
      opts.mode === "fake"
        ? descriptor.executionMode !== "FAKE_DEMO" ||
          descriptor.realExecutionAllowed !== false
        : descriptor.mode !== "LOCAL_HISTORY"
    )
      fail("Unexpected backend mode; refusing frontend startup.");
    launch(
      process.execPath,
      [
        path.join(root, "frontend/node_modules/vite/bin/vite.js"),
        "--host",
        "127.0.0.1",
        "--port",
        String(opts.frontend),
        "--strictPort",
        "--mode",
        opts.mode,
      ],
      "frontend",
      env,
      path.join(root, "frontend"),
    );
    await until(`http://127.0.0.1:${opts.frontend}/`, children, 30);
    console.log(
      `READY: http://127.0.0.1:${opts.frontend}/${opts.mode === "fake" ? "login" : "history/m1-core"}`,
    );
    console.log(
      "Keep this terminal open. Ctrl+C or scripts/stop-dev stops only these direct children. No database is deleted.",
    );
    await completion;
  } finally {
    await shutdown();
    process.removeListener("SIGINT", interrupt);
    process.removeListener("SIGTERM", interrupt);
  }
}

export async function main(args = process.argv.slice(2)) {
  const opts = options(args);
  if (opts.command === "stop") return stop();
  if (opts.command === "build") return build();
  if (opts.command === "doctor") return doctor(opts);
  await doctor(opts, false);
  // Atomic reservation precedes the slow build: concurrent invocations cannot reinstall live dependencies.
  const lock = randomBytes(32).toString("hex");
  try {
    fs.writeFileSync(lockFile, lock, { mode: 0o600, flag: "wx" });
  } catch {
    fail("Startup already reserved; no build or service was started.");
  }
  try {
    await build();
    if (opts.mode === "fake" && !fs.existsSync(accountFile))
      await configure(javaExecutable());
    if (opts.command === "configure") return;
    const accounts =
      opts.mode === "fake" ? validateAccounts(privateRead(accountFile)) : null;
    return await supervise(opts, accounts);
  } finally {
    if (
      fs.existsSync(lockFile) &&
      !fs.lstatSync(lockFile).isSymbolicLink() &&
      fs.readFileSync(lockFile, "utf8") === lock
    )
      fs.unlinkSync(lockFile);
  }
}
if (
  process.argv[1] &&
  path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)
)
  main().catch((error) => {
    console.error(`ChaosLab: ${error.message}`);
    process.exitCode = 1;
  });
