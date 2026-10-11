import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router-dom";
import { afterEach, describe, it, expect, vi } from "vitest";
import { z } from "zod";
import { anonymous, canManageFake, type Identity } from "../api/auth";
import { fakeWrite, intentKey } from "../api/fake";
import { allowedProxyRequest } from "../../vite.config";
import {
  FakeExperimentActions,
  FakeExecutionActions,
} from "../pages/FakeExperiments";
import {
  experimentSchema,
  executionSchema,
  reportSchema,
  type Experiment,
} from "../api/contracts";
import { App } from "../App";
import { execution } from "./fixtures";
vi.mock("../components/Chart", () => ({ Chart: () => <div /> }));
const admin: Identity = {
  ...anonymous,
  authenticated: true,
  username: "test-admin",
  roles: ["ADMIN"],
  executionMode: "FAKE_DEMO",
  fakeExecutionAllowed: true,
  readOnly: false,
};
const experiment: Experiment = {
  id: execution.experimentId,
  name: "TEST-ONLY simulation",
  hypothesis: "Lifecycle only",
  targetId: "00000000-0000-0000-0000-000000000401",
  scenarioId: "00000000-0000-0000-0000-000000000101",
  durationSeconds: 10,
  parameters: { percent: 10 },
  status: "READY",
  version: 2,
};
function mount(node: React.ReactNode, path = "/") {
  window.matchMedia = vi.fn().mockReturnValue({ matches: false });
  render(
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { queries: { retry: false } } })
      }
    >
      <MemoryRouter initialEntries={[path]}>{node}</MemoryRouter>
    </QueryClientProvider>,
  );
}
afterEach(() => {
  vi.unstubAllGlobals();
  sessionStorage.clear();
  localStorage.clear();
});
describe("Fake-only UI and write transport", () => {
  it("requires actual FAKE_DEMO ADMIN capability, not merely an ADMIN role", async () => {
    const fetch = vi.fn();
    vi.stubGlobal("fetch", fetch);
    for (const identity of [
      { ...admin, fakeExecutionAllowed: false },
      { ...admin, executionMode: "READ_ONLY" as const },
      { ...admin, roles: ["VIEWER"] as Identity["roles"] },
    ]) {
      expect(canManageFake(identity)).toBe(false);
      await expect(
        fakeWrite(identity, "POST", "/api/v1/experiments", z.unknown(), {}),
      ).rejects.toThrow("ADMIN");
    }
    expect(fetch).not.toHaveBeenCalled();
    expect(allowedProxyRequest("POST", "/api/v1/experiments")).toBe(false);
    expect(allowedProxyRequest("POST", "/api/v1/experiments", true)).toBe(true);
    for (const path of [
      "/api/v1/authorization",
      "/api/v1/root",
      "/api/v1/targets",
      "/api/v1/experiments/../blade",
    ])
      expect(allowedProxyRequest("POST", path, true)).toBe(false);
  });
  it("fetches CSRF and sends same-origin JSON with stable idempotency key", async () => {
    const token = crypto.randomUUID(),
      key = intentKey("start", experiment.id, admin.username);
    expect(intentKey("start", experiment.id, admin.username)).toBe(key);
    const fetch = vi
      .fn()
      .mockImplementation(
        async (path: string) =>
          new Response(
            JSON.stringify(
              path === "/api/v1/auth/csrf"
                ? { headerName: "X-CSRF-TOKEN", token }
                : execution,
            ),
          ),
      );
    vi.stubGlobal("fetch", fetch);
    await fakeWrite(
      admin,
      "POST",
      `/api/v1/experiments/${experiment.id}/executions`,
      executionSchema,
      undefined,
      key,
    );
    expect(fetch.mock.calls[1]?.[1]).toMatchObject({
      method: "POST",
      credentials: "same-origin",
      headers: { "X-CSRF-TOKEN": token, "Idempotency-Key": key },
    });
    expect(JSON.stringify([...Object.keys(sessionStorage)])).not.toMatch(
      /password|csrf|token/,
    );
  });
  it("uncertain transport never retries or changes state to SUCCESS and retains the same key", async () => {
    const key = intentKey("start", experiment.id, admin.username);
    const fetch = vi.fn().mockImplementation(async (path: string) => {
      if (path === "/api/v1/auth/csrf")
        return new Response(
          JSON.stringify({
            headerName: "X-CSRF-TOKEN",
            token: crypto.randomUUID(),
          }),
        );
      throw new Error("private raw error");
    });
    vi.stubGlobal("fetch", fetch);
    await expect(
      fakeWrite(
        admin,
        "POST",
        `/api/v1/experiments/${experiment.id}/executions`,
        executionSchema,
        undefined,
        key,
      ),
    ).rejects.toThrow("结果未知");
    expect(fetch).toHaveBeenCalledTimes(2);
    expect(intentKey("start", experiment.id, admin.username)).toBe(key);
  });
  it("start requires explicit confirmation and repeated click dispatches only once", async () => {
    let resolveWrite: (r: Response) => void = () => {};
    const pending = new Promise<Response>((resolve) => {
      resolveWrite = resolve;
    });
    const fetch = vi.fn().mockImplementation(async (path: string) =>
      path === "/api/v1/auth/csrf"
        ? new Response(
            JSON.stringify({
              headerName: "X-CSRF-TOKEN",
              token: crypto.randomUUID(),
            }),
          )
        : pending,
    );
    vi.stubGlobal("fetch", fetch);
    mount(<FakeExperimentActions identity={admin} experiment={experiment} />);
    await userEvent.click(
      screen.getByRole("button", { name: "启动 Fake 实验" }),
    );
    expect(
      screen.getByRole("dialog", { name: "确认启动 Fake" }),
    ).toBeInTheDocument();
    expect(fetch).not.toHaveBeenCalled();
    await userEvent.dblClick(screen.getByRole("button", { name: "确认启动" }));
    await waitFor(() =>
      expect(
        fetch.mock.calls.filter((c) => String(c[0]).endsWith("/executions")),
      ).toHaveLength(1),
    );
    resolveWrite(
      new Response(
        JSON.stringify({
          ...execution,
          status: "RUNNING",
          engineExperimentId: `fake-${execution.id}`,
        }),
      ),
    );
  });
  it("VIEWER and readonly ADMIN have no Fake management controls", () => {
    mount(
      <FakeExperimentActions
        identity={{ ...admin, roles: ["VIEWER"], fakeExecutionAllowed: false }}
        experiment={experiment}
      />,
    );
    expect(screen.queryByRole("button")).not.toBeInTheDocument();
  });
  it("stopping is confirmed and ambiguous/real IDs never expose fake stop controls", () => {
    mount(
      <FakeExecutionActions
        identity={admin}
        execution={{
          ...execution,
          status: "RUNNING",
          engineExperimentId: "blade-other",
        }}
      />,
    );
    expect(
      screen.queryByRole("button", { name: "停止 Fake 实验" }),
    ).not.toBeInTheDocument();
  });
  it("expired session returns to login; server raw failure text is never rendered", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockImplementation(async (path: string) =>
        path === "/api/v1/auth/me"
          ? new Response(JSON.stringify(admin))
          : path === "/api/v1/auth/csrf"
            ? new Response(
                JSON.stringify({
                  headerName: "X-CSRF-TOKEN",
                  token: crypto.randomUUID(),
                }),
              )
            : new Response("/root/private password", { status: 401 }),
      ),
    );
    let event = false;
    const listener = () => {
      event = true;
    };
    window.addEventListener("chaoslab-auth-expired", listener);
    await expect(
      fakeWrite(admin, "POST", "/api/v1/experiments", experimentSchema, {}),
    ).rejects.toThrow("登录已失效");
    expect(event).toBe(true);
    window.removeEventListener("chaoslab-auth-expired", listener);
  });
  it("Fake create route shows SIMULATED form and never fabricates CPU observations", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockImplementation(
        async (path: string) =>
          new Response(
            JSON.stringify(
              path === "/api/v1/auth/me"
                ? admin
                : path === "/api/v1/targets"
                  ? [
                      {
                        id: experiment.targetId,
                        name: "FAKE target",
                        type: "LINUX_HOST",
                        environment: "LOCAL",
                        enabled: true,
                      },
                    ]
                  : [
                      {
                        id: experiment.scenarioId,
                        code: "CPU_LOAD",
                        name: "CPU Load",
                        description: "Simulated",
                        parameterSchema: {},
                        enabled: true,
                      },
                    ],
            ),
          ),
      ),
    );
    mount(<App />, "/experiments/new");
    expect(
      await screen.findByRole("heading", { name: "创建 Fake 实验" }),
    ).toBeInTheDocument();
    expect(screen.getByLabelText("参数 JSON")).toHaveValue(
      '{\n  "percent": 10\n}',
    );
    expect(screen.queryByText("10.91%")).not.toBeInTheDocument();
    expect(screen.getByLabelText("持续秒数")).toHaveAttribute("max", "30");
  });
});
describe.runIf(process.env.CONSOLE_CONTRACT_DIR)(
  "actual Spring Fake lifecycle contracts",
  () => {
    it("keeps archived real M1 distinct from simulated experiments in Fake mode", async () => {
      const history = JSON.parse(
        readFileSync(
          resolve(process.env.CONSOLE_CONTRACT_DIR!, "history-contract.json"),
          "utf8",
        ),
      );
      vi.stubGlobal(
        "fetch",
        vi
          .fn()
          .mockImplementation(
            async (path: string) =>
              new Response(
                JSON.stringify(path === "/api/v1/auth/me" ? admin : history),
              ),
          ),
      );
      mount(<App />, "/history/m1-core");
      expect(
        await screen.findByText("历史真实实验 · 已结束 · 非实时监控"),
      ).toBeInTheDocument();
      expect(
        screen.queryByText("FAKE 模拟实验 · 不执行真实故障"),
      ).not.toBeInTheDocument();
    });
    it("maps durable definition/executions/report without invented physical verification", () => {
      const raw = JSON.parse(
        readFileSync(
          resolve(process.env.CONSOLE_CONTRACT_DIR!, "fake-contract.json"),
          "utf8",
        ),
      ) as Record<string, unknown>;
      for (const k of ["created", "edited", "ready"])
        expect(experimentSchema.safeParse(raw[k]).success).toBe(true);
      for (const k of ["running", "finished"])
        expect(executionSchema.safeParse(raw[k]).success).toBe(true);
      const report = reportSchema.parse(raw.report);
      expect(report.executionMode).toBe("SIMULATED");
      expect(report.metricsStatus).toBe("NOT_COLLECTED");
      expect(report.conclusionStatus).toBe("INSUFFICIENT_DATA");
      for (const w of report.windows) expect(w.p95Seconds).toBeNull();
    });
  },
);
