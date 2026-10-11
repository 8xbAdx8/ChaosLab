import { loadEnv, type Plugin } from "vite";
import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";
import type { IncomingMessage, ServerResponse } from "node:http";
import { fakeMutationPath } from "./src/api/fakePaths.ts";

export function allowedProxyRequest(
  method: string,
  path: string,
  fake = false,
): boolean {
  if (
    method === "POST" &&
    ["/api/v1/auth/login", "/api/v1/auth/logout"].includes(path)
  )
    return true;
  if (fake && fakeMutationPath(method, path)) return true; // Proxy is NOT authorization; production rejects outside FAKE_DEMO.
  return (
    ["GET", "HEAD"].includes(method) &&
    (/^\/api\/v1\/console\/reports\/[0-9a-f-]{36}\/analysis$/.test(path) ||
      /^\/api\/v1\/console\/(overview|experiments|executions|audit-logs|reports)(\/[0-9a-f-]{36})?(\/evidence)?$/.test(
        path,
      ) ||
      [
        "/api/v1/targets",
        "/api/v1/scenarios",
        "/actuator/health",
        "/api/v1/console/history/m1-core",
        "/api/v1/console/access",
        "/api/v1/auth/me",
        "/api/v1/auth/csrf",
      ].includes(path))
  );
}
function readOnlyProxy(fake = false): Plugin {
  const guard = (
    req: IncomingMessage,
    res: ServerResponse,
    next: () => void,
  ) => {
    const path = (req.url ?? "").split("?")[0] ?? "";
    if (
      (path.startsWith("/api/") || path.startsWith("/actuator/")) &&
      !allowedProxyRequest(req.method ?? "", path, fake)
    ) {
      res.writeHead(403, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ code: "CONSOLE_PROXY_READ_ONLY" }));
    } else next();
  };
  return {
    name: "console-read-only-proxy",
    configureServer: (s) => {
      s.middlewares.use(guard);
    },
    configurePreviewServer: (s) => {
      s.middlewares.use(guard);
    },
  };
}
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), "");
  const target = env.API_PROXY_TARGET || "http://127.0.0.1:8080";
  const proxy = { "/api": { target }, "/actuator/health": { target } };
  return {
    plugins: [readOnlyProxy(mode === "fake"), react(), tailwindcss()],
    server: { proxy },
    preview: { proxy },
    test: {
      environment: "jsdom",
      setupFiles: "./src/test/setup.ts",
      css: false,
    },
    build: {
      rolldownOptions: {
        output: {
          manualChunks: (moduleId: string) =>
            /node_modules[\\/](echarts|zrender)/.test(moduleId)
              ? "charts"
              : undefined,
        },
      },
    },
  };
});
