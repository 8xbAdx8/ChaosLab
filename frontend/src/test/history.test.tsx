import { existsSync, readFileSync } from "node:fs";
import { resolve } from "node:path";
import { createHash } from "node:crypto";
import { render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router-dom";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { App } from "../App";
import { HistoryContent, historyTime } from "../pages/History";
import { historySchema } from "../api/history";
import { allowedProxyRequest } from "../../vite.config";
import { isReadPath } from "../api/client";

vi.mock("../components/Chart", () => ({
  Chart: ({ label }: { label: string }) => (
    <div role="img" aria-label={label} />
  ),
}));
const path = resolve(
  process.env.CONSOLE_CONTRACT_DIR ?? "../backend/target",
  "history-contract.json",
);
// Same as the existing console cross-language tests: actual Spring serialization only.
// Explicit CI mode fails if generation was omitted; no synthetic runtime fallback.
if (process.env.CONSOLE_CONTRACT_DIR && !existsSync(path))
  throw new Error("Generate HistoryEvidenceApiTests contract first");
const load = () => historySchema.parse(JSON.parse(readFileSync(path, "utf8")));
afterEach(() => {
  vi.unstubAllGlobals();
  vi.unstubAllEnvs();
  localStorage.clear();
});

describe("precise history transport", () => {
  it("permits only the one readonly archive route", () => {
    const api = "/api/v1/console/history/m1-core";
    expect(isReadPath(api)).toBe(true);
    expect(allowedProxyRequest("GET", api)).toBe(true);
    expect(allowedProxyRequest("HEAD", api)).toBe(true);
    for (const p of [
      api + "/",
      api + "/raw",
      "/api/v1/console/history",
      "/api/v1/console/history/other",
    ]) {
      expect(isReadPath(p)).toBe(false);
      expect(allowedProxyRequest("GET", p)).toBe(false);
    }
    for (const m of ["POST", "DELETE", "OPTIONS", "PATCH"])
      expect(allowedProxyRequest(m, api)).toBe(false);
  });
  it("uses an explicit display timezone without changing ISO source values", () => {
    expect(historyTime("2026-10-09T17:39:00Z")).toContain("2026年10月10日");
    expect(historyTime("2026-10-09T17:39:00Z")).toContain("01:39:00");
    expect(historyTime(null)).toBe("未记录");
  });
});

describe.runIf(existsSync(path))(
  "actual Spring archive projection -> Console",
  () => {
    it("matches every fact and CPU sample to the unchanged original JSON", () => {
      const bytes = readFileSync(
        resolve("../docs/evidence/m1-core-real-20261010.json"),
      );
      const original = JSON.parse(bytes.toString("utf8")) as unknown;
      const h = load();
      expect(createHash("sha256").update(bytes).digest("hex")).toBe(
        h.evidenceSource.sha256,
      );
      const at = (pointer: string): unknown =>
        pointer
          .split("/")
          .slice(1)
          .reduce<unknown>(
            (value, key) =>
              value && typeof value === "object"
                ? (value as Record<string, unknown>)[key]
                : undefined,
            original,
          );
      for (const f of [
        ...h.facts,
        ...h.cpu.map((s) => s.percent),
        ...h.recoveryRounds.flatMap((r) => r.facts),
        ...h.audits.flatMap((a) => a.parameters),
      ])
        expect(f.value, f.pointer).toEqual(at(f.pointer) ?? null);
      for (const e of h.timeline) expect(e.observedAt).toBe(at(e.pointer));
      expect(h.cpu.map((s) => s.percent.value)).toEqual([
        0, 10.911216516130064, 0,
      ]);
    });
    it("renders three real samples, identity, rounds and UNKNOWN cause honestly", () => {
      render(<HistoryContent data={load()} />);
      expect(
        screen.getByText("历史真实实验 · 已结束 · 非实时监控"),
      ).toBeInTheDocument();
      expect(
        screen.getByText("aa73e482-5584-4e03-baca-0ba6a7b0c635", {
          selector: "code.identifier",
        }),
      ).toBeInTheDocument();
      expect(
        screen.getByText("dd07ca41-fbbc-416f-944a-712100376c86", {
          selector: "code.identifier",
        }),
      ).toBeInTheDocument();
      expect(
        screen.getByText("2d479cbbf2ea6dfe", { selector: "code.identifier" }),
      ).toBeInTheDocument();
      expect(screen.getByRole("img")).toHaveAccessibleName(
        /baseline 0%，during 10.91%，after 0%.*三个离散样本/,
      );
      expect(screen.getAllByText("UNKNOWN").length).toBeGreaterThan(0);
      expect(
        screen.getByText("PRESENT", { selector: ".badge" }),
      ).toBeInTheDocument();
      expect(
        screen.getByText("MANUAL_INTERVENTION", { selector: ".badge" }),
      ).toBeInTheDocument();
      expect(screen.getByText("已释放（归档值 false）")).toBeInTheDocument();
      expect(
        screen.getByText("1 核（仅验收报告记载，JSON 未单列）"),
      ).toBeInTheDocument();
      expect(
        screen.queryByRole("button", { name: /create|destroy|注入|授权/ }),
      ).not.toBeInTheDocument();
    });
    it("does not invent recovery or CPU metrics when absent despite SUCCESS", () => {
      const h = load();
      for (const f of h.facts)
        if (
          [
            "physicalRecovery",
            "health",
            "gate",
            "residual",
            "occupancyRetained",
          ].includes(f.key)
        )
          f.value = null;
      for (const s of h.cpu) s.percent.value = null;
      h.recoveryRounds = [];
      h.audits = [];
      render(<HistoryContent data={h} />);
      expect(screen.getByRole("img")).toHaveAccessibleName(
        /baseline 未采集，during 未采集，after 未采集/,
      );
      expect(screen.queryByText("10.91%")).not.toBeInTheDocument();
      expect(screen.queryByText("VERIFIED")).not.toBeInTheDocument();
      expect(
        screen.queryByText("已释放（归档值 false）"),
      ).not.toBeInTheDocument();
      expect(screen.getByText("未记录恢复观察回合")).toBeInTheDocument();
    });
    function app() {
      window.matchMedia = vi.fn().mockReturnValue({ matches: false });
      return render(
        <QueryClientProvider
          client={
            new QueryClient({ defaultOptions: { queries: { retry: false } } })
          }
        >
          <MemoryRouter initialEntries={["/history/m1-core"]}>
            <App />
          </MemoryRouter>
        </QueryClientProvider>,
      );
    }
    it("history-only mode consumes the actual GET response, no dashboard DB calls", async () => {
      vi.stubEnv("MODE", "history");
      const fetch = vi
        .fn()
        .mockImplementation(async () => new Response(JSON.stringify(load())));
      vi.stubGlobal("fetch", fetch);
      app();
      expect(
        await screen.findByText("一次真实故障，一次安全恢复"),
      ).toBeInTheDocument();
      expect(
        screen.queryByRole("link", { name: "实验管理" }),
      ).not.toBeInTheDocument();
      for (const call of fetch.mock.calls) {
        expect(call[0]).toBe("/api/v1/console/history/m1-core");
        expect(call[1].method).toBe("GET");
      }
      await userEvent.click(
        screen.getByRole("button", { name: "切换浅色主题" }),
      );
      expect(localStorage.getItem("chaoslab-theme")).toBe("light");
    });
    it("checksum/API failure exposes a safe error, never archived success as fallback", async () => {
      vi.stubGlobal(
        "fetch",
        vi
          .fn()
          .mockImplementation(
            async () => new Response("/root/private secret", { status: 503 }),
          ),
      );
      app();
      expect(screen.getByRole("status")).toHaveTextContent("正在读取");
      expect(await screen.findByRole("alert")).toHaveTextContent("HTTP 503");
      expect(screen.queryByText("10.91%")).not.toBeInTheDocument();
      expect(
        screen.queryByText("/root/private secret"),
      ).not.toBeInTheDocument();
    });
    it("rejects wrong wire mode/version, no synthetic realtime interpretation", () => {
      expect(
        historySchema.safeParse({ ...load(), dataMode: "LIVE" }).success,
      ).toBe(false);
      expect(
        historySchema.safeParse({ ...load(), schemaVersion: 2 }).success,
      ).toBe(false);
    });
  },
);
