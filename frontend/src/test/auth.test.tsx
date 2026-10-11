import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router-dom";
import { afterEach, describe, expect, it, vi } from "vitest";
import { App } from "../App";
import { anonymous, type Identity, sessionAction } from "../api/auth";
import { get } from "../api/client";
import { z } from "zod";
import { allowedProxyRequest } from "../../vite.config";
vi.mock("../components/Chart", () => ({ Chart: () => <div /> }));
const viewer: Identity = {
  ...anonymous,
  authenticated: true,
  username: "test-viewer",
  roles: ["VIEWER"],
};
function renderApp(
  path: string,
  client = new QueryClient({ defaultOptions: { queries: { retry: false } } }),
  state?: unknown,
) {
  window.matchMedia = vi.fn().mockReturnValue({ matches: false });
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[{ pathname: path, state }]}>
        <App />
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return client;
}
afterEach(() => {
  vi.unstubAllGlobals();
  localStorage.clear();
});
describe("session UI and narrow auth transport", () => {
  it("only adds login/logout POST exceptions; other writes stay denied", () => {
    for (const p of ["/api/v1/auth/login", "/api/v1/auth/logout"])
      expect(allowedProxyRequest("POST", p)).toBe(true);
    for (const p of [
      "/api/v1/auth/login/",
      "/api/v1/auth/role",
      "/api/v1/console/access",
      "/api/v1/experiments",
      "/api/v1/root",
      "/api/v1/authorization",
    ])
      expect(allowedProxyRequest("POST", p)).toBe(false);
    expect(allowedProxyRequest("DELETE", "/api/v1/auth/logout")).toBe(false);
  });
  it("unauthenticated routes never fetch protected data before login", async () => {
    const fetch = vi
      .fn()
      .mockImplementation(async () => new Response(JSON.stringify(anonymous)));
    vi.stubGlobal("fetch", fetch);
    renderApp("/experiments");
    expect(
      await screen.findByRole("heading", { name: "登录控制台" }),
    ).toBeInTheDocument();
    expect(fetch.mock.calls.every((c) => c[0] === "/api/v1/auth/me")).toBe(
      true,
    );
  });
  it("login uses fresh CSRF and same-origin cookie; restores protected route without persisting password", async () => {
    let signedIn = false;
    const password = crypto.randomUUID(),
      token = crypto.randomUUID();
    const fetch = vi.fn().mockImplementation(async (path: string) => {
      if (path === "/api/v1/auth/me")
        return new Response(JSON.stringify(signedIn ? viewer : anonymous));
      if (path === "/api/v1/auth/csrf")
        return new Response(
          JSON.stringify({ headerName: "X-CSRF-TOKEN", token }),
        );
      if (path === "/api/v1/auth/login") {
        signedIn = true;
        return new Response('{"authenticated":true}');
      }
      return new Response(
        JSON.stringify({ items: [], total: 0, page: 0, size: 20 }),
      );
    });
    vi.stubGlobal("fetch", fetch);
    renderApp("/experiments");
    await userEvent.type(await screen.findByLabelText("用户名"), "test-viewer");
    await userEvent.type(screen.getByLabelText("密码"), password);
    await userEvent.click(screen.getByRole("button", { name: "登录" }));
    expect(await screen.findByText("暂无实验")).toBeInTheDocument();
    const call = fetch.mock.calls.find((c) => c[0] === "/api/v1/auth/login");
    expect(call?.[1]).toMatchObject({
      method: "POST",
      credentials: "same-origin",
      headers: { "X-CSRF-TOKEN": token },
    });
    expect(new URLSearchParams(call?.[1].body as string).get("password")).toBe(
      password,
    );
    expect(localStorage.length).toBe(0);
    expect(screen.queryByLabelText("密码")).not.toBeInTheDocument();
  });
  it("login failure has no automatic retry or raw error disclosure and clears password", async () => {
    const fetch = vi.fn().mockImplementation(async (path: string) =>
      path === "/api/v1/auth/me"
        ? new Response(JSON.stringify(anonymous))
        : path === "/api/v1/auth/csrf"
          ? new Response(
              JSON.stringify({
                headerName: "X-CSRF-TOKEN",
                token: crypto.randomUUID(),
              }),
            )
          : new Response("/root/private secret", { status: 401 }),
    );
    vi.stubGlobal("fetch", fetch);
    renderApp("/login");
    await userEvent.type(await screen.findByLabelText("用户名"), "test-viewer");
    await userEvent.type(screen.getByLabelText("密码"), crypto.randomUUID());
    await userEvent.click(screen.getByRole("button", { name: "登录" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("登录失败");
    expect(screen.getByLabelText("密码")).toHaveValue("");
    expect(screen.queryByText("/root/private secret")).not.toBeInTheDocument();
    expect(
      fetch.mock.calls.filter((c) => c[0] === "/api/v1/auth/login"),
    ).toHaveLength(1);
  });
  it("VIEWER gets a route permission denial; ADMIN sees readonly permission summary", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockImplementation(async () => new Response(JSON.stringify(viewer))),
    );
    renderApp("/access");
    expect(await screen.findByRole("alert")).toHaveTextContent("权限不足");
    expect(
      screen.queryByRole("link", { name: "权限信息" }),
    ).not.toBeInTheDocument();
  });
  it("ADMIN foundation does not claim real execution capability", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockImplementation(
        async (p: string) =>
          new Response(
            JSON.stringify(
              p === "/api/v1/auth/me"
                ? { ...viewer, roles: ["ADMIN"] }
                : {
                    capabilities: ["CONSOLE_READ", "ADMIN_PERMISSION_READ"],
                    realExecutionAllowed: false,
                  },
            ),
          ),
      ),
    );
    renderApp("/access");
    expect(await screen.findByText("DISABLED")).toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: /create|destroy|授权/ }),
    ).not.toBeInTheDocument();
  });
  it("dashboard shows server authentication state rather than the former unauthenticated warning", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockImplementation(
        async (p: string) =>
          new Response(
            JSON.stringify(
              p === "/api/v1/auth/me"
                ? viewer
                : p === "/actuator/health"
                  ? { status: "UP" }
                  : {
                      experimentCount: 0,
                      executionCount: 0,
                      targetCount: 0,
                      reportCount: 0,
                      executionStates: {},
                      successRate: null,
                      successRateDefinition: "SUCCESS / all",
                      recentExperiments: [],
                      engineMode: "fake",
                      readOnlyMode: true,
                      authenticationConfigured: true,
                    },
            ),
          ),
      ),
    );
    renderApp("/");
    expect(await screen.findByText("SESSION_RBAC")).toBeInTheDocument();
    expect(
      screen.getByText(/已启用服务端会话登录与只读角色权限/),
    ).toBeInTheDocument();
    expect(screen.queryByText(/当前未配置登录鉴权/)).not.toBeInTheDocument();
  });
  it("logout invalidates UI identity and clears all previous user cache", async () => {
    let signedIn = true;
    vi.stubGlobal(
      "fetch",
      vi.fn().mockImplementation(async (p: string) => {
        if (p === "/api/v1/auth/me")
          return new Response(JSON.stringify(signedIn ? viewer : anonymous));
        if (p === "/api/v1/auth/csrf")
          return new Response(
            JSON.stringify({
              headerName: "X-CSRF-TOKEN",
              token: crypto.randomUUID(),
            }),
          );
        if (p === "/api/v1/auth/logout") {
          signedIn = false;
          return new Response(null, { status: 204 });
        }
        return new Response(
          JSON.stringify({ items: [], total: 0, page: 0, size: 20 }),
        );
      }),
    );
    const client = renderApp("/experiments");
    await screen.findByText("暂无实验");
    client.setQueryData(["previous-user-private-data"], "not-shared");
    await userEvent.click(screen.getByRole("button", { name: "退出登录" }));
    expect(
      await screen.findByRole("heading", { name: "登录控制台" }),
    ).toBeInTheDocument();
    expect(client.getQueryData(["previous-user-private-data"])).toBeUndefined();
  });
  it("expired backend session evicts cache and returns to login", async () => {
    const client = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    });
    client.setQueryData(["previous-user-private-data"], "not-shared");
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockImplementation(async (p: string) =>
          p === "/api/v1/auth/me"
            ? new Response(JSON.stringify(viewer))
            : new Response("not raw", { status: 401 }),
        ),
    );
    renderApp("/experiments", client);
    expect(
      await screen.findByRole("heading", { name: "登录控制台" }),
    ).toBeInTheDocument();
    await waitFor(() =>
      expect(
        client.getQueryData(["previous-user-private-data"]),
      ).toBeUndefined(),
    );
  });
  it("local archive identity is explicit, not an authenticated role or browser bypass flag", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockImplementation(async (p: string) =>
          p === "/api/v1/auth/me"
            ? new Response(
                JSON.stringify({ ...anonymous, mode: "LOCAL_HISTORY" }),
              )
            : new Response("archive unavailable", { status: 503 }),
        ),
    );
    renderApp("/access");
    expect(
      await screen.findByText("本地历史演示 · 非登录身份"),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole("link", { name: "权限信息" }),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "退出登录" }),
    ).not.toBeInTheDocument();
  });
  it("missing CSRF stops POST dispatch; authorization errors stay safe", async () => {
    const fetch = vi
      .fn()
      .mockResolvedValue(new Response("no token", { status: 503 }));
    vi.stubGlobal("fetch", fetch);
    await expect(sessionAction("logout")).rejects.toThrow("HTTP 503");
    expect(fetch).toHaveBeenCalledTimes(1);
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValue(new Response("privileged stderr", { status: 403 })),
    );
    await expect(get("/api/v1/console/access", z.unknown())).rejects.toThrow(
      "权限不足",
    );
  });
});
