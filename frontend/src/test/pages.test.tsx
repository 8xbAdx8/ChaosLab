import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router-dom";
import { afterEach, describe, expect, it, vi } from "vitest";
import { App } from "../App";
import { EvidenceView } from "../pages/ExecutionDetail";
import { evidence, execution } from "./fixtures";
vi.mock("../components/Chart", () => ({
  Chart: ({ label }: { label: string }) => (
    <div role="img" aria-label={label} />
  ),
}));
function app(path: string) {
  window.matchMedia = vi.fn().mockReturnValue({ matches: false });
  return render(
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { queries: { retry: false } } })
      }
    >
      <MemoryRouter initialEntries={[path]}>
        <App />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}
afterEach(() => {
  vi.unstubAllGlobals();
  localStorage.clear();
});
describe("honest recovery evidence", () => {
  it("separates physical VERIFIED from cause UNKNOWN and missing after CPU", () => {
    render(<EvidenceView evidence={evidence} />);
    expect(screen.getByText("10.91%")).toBeInTheDocument();
    expect(screen.getAllByText("VERIFIED")).toHaveLength(2);
    expect(screen.getByText("UNKNOWN")).toBeInTheDocument();
    expect(screen.getByText("未采集")).toBeInTheDocument();
    expect(screen.getByRole("img")).toHaveAccessibleName(/after 未采集/);
  });
  it("does not fabricate missing physical evidence", () => {
    render(
      <EvidenceView
        evidence={{
          ...evidence,
          nativeStatus: "UNKNOWN",
          physicalRecovery: "UNKNOWN",
          residual: "UNKNOWN",
          health: "UNKNOWN",
          recoveryGate: "UNKNOWN",
          duringCpuPercent: null,
        }}
      />,
    );
    expect(screen.queryByText("VERIFIED")).not.toBeInTheDocument();
    expect(screen.queryByText("10.91%")).not.toBeInTheDocument();
  });
});
describe("read-only routes", () => {
  it("renders backend executions and filters real status; all requests remain GET", async () => {
    const fetch = vi
      .fn()
      .mockResolvedValue(
        new Response(
          JSON.stringify({ items: [execution], total: 1, page: 0, size: 20 }),
        ),
      );
    // Fresh Response per request, since response streams are single-use.
    fetch.mockImplementation(
      async () =>
        new Response(
          JSON.stringify({ items: [execution], total: 1, page: 0, size: 20 }),
        ),
    );
    vi.stubGlobal("fetch", fetch);
    app("/executions");
    expect(await screen.findByText(execution.id)).toBeInTheDocument();
    await userEvent.selectOptions(
      screen.getByLabelText("执行状态"),
      "CREATE_UNCERTAIN",
    );
    await waitFor(() =>
      expect(
        fetch.mock.calls.some((c) =>
          String(c[0]).includes("status=CREATE_UNCERTAIN"),
        ),
      ).toBe(true),
    );
    for (const call of fetch.mock.calls) expect(call[1].method).toBe("GET");
    expect(
      screen.queryByRole("button", { name: /create|destroy|注入|授权/ }),
    ).not.toBeInTheDocument();
  });
  it("renders empty state without demonstration data", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValue(
          new Response(
            JSON.stringify({ items: [], total: 0, page: 0, size: 20 }),
          ),
        ),
    );
    app("/experiments");
    expect(await screen.findByText("暂无实验")).toBeInTheDocument();
    expect(screen.queryByText("10.91%")).not.toBeInTheDocument();
  });
  it("shows errors and loading honestly", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValue(new Response("secret raw stderr", { status: 503 })),
    );
    app("/executions");
    expect(screen.getByRole("status")).toHaveTextContent("正在读取");
    expect(await screen.findByRole("alert")).toHaveTextContent("HTTP 503");
    expect(screen.queryByText("secret raw stderr")).not.toBeInTheDocument();
  });
  it("switches and persists theme", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValue(
          new Response(
            JSON.stringify({ items: [], total: 0, page: 0, size: 20 }),
          ),
        ),
    );
    app("/experiments");
    await screen.findByText("暂无实验");
    await userEvent.click(screen.getByRole("button", { name: "切换浅色主题" }));
    expect(localStorage.getItem("chaoslab-theme")).toBe("light");
    expect(
      screen.getByRole("button", { name: "切换深色主题" }),
    ).toBeInTheDocument();
  });
});
