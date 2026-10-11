import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it, vi } from "vitest";
import { ReportInsights, metricBars } from "../components/ReportInsights";
import type { ReportAnalysis } from "../api/contracts";
import { evidence, execution } from "./fixtures";
vi.mock("../components/Chart", () => ({
  Chart: ({ label }: { label: string }) => (
    <div role="img" aria-label={label} />
  ),
}));
// Test-only aggregates, never imported as runtime fallback.
const sample: ReportAnalysis = {
  report: {
    id: execution.id,
    experimentId: execution.experimentId,
    executionId: execution.id,
    targetId: execution.id,
    scenarioCode: "CPU_LOAD",
    startAuditId: execution.id,
    recoveryAuditId: execution.experimentId,
    generatedAt: "2026-10-10T00:03:00Z",
    executionMode: "SIMULATED",
    metricsStatus: "NOT_COLLECTED",
    conclusionStatus: "INSUFFICIENT_DATA",
    bindingStatus: "NOT_VERIFIED",
    containerId: null,
    imageId: null,
    metricsJob: null,
    route: null,
    reason: "no metrics",
    windows: ["before", "during", "after"].map((phase, i) => ({
      phase,
      start: `2026-10-10T00:0${i}:00Z`,
      end: `2026-10-10T00:0${i + 1}:00Z`,
      metricsStatus: "NOT_COLLECTED",
      scrapeSamples: null,
      estimatedRequests: null,
      estimated5xx: null,
      errorRate: null,
      p95Seconds: null,
      reason: null,
      collectionVersion: null,
    })),
  },
  execution,
  experimentName: "Test-only Fake",
  targetName: "local-classroom",
  targetType: "LINUX_HOST",
  parameters: { percent: 10 },
  parameterSource: "START_AUDIT",
  traceability: "MATCHED",
  comparisonStatus: "NOT_COMPARABLE",
  metricSource: "UNKNOWN",
  metricSubject: "UNKNOWN",
  metricInstance: null,
  durationMillis: 9000,
  recovery: {
    ...evidence,
    nativeUid: null,
    nativeStatus: "UNKNOWN",
    physicalRecovery: "UNKNOWN",
    residual: "UNKNOWN",
    health: "UNKNOWN",
    recoveryGate: "UNKNOWN",
    baselineCpuPercent: null,
    duringCpuPercent: null,
  },
  audits: [],
  auditsTruncated: false,
  limitations: ["Missing data is not zero."],
};
describe("traceable report analysis", () => {
  it("Fake percent and SUCCESS do not become CPU or physical recovery proof", () => {
    render(
      <MemoryRouter>
        <ReportInsights data={sample} />
      </MemoryRouter>,
    );
    expect(screen.getByText(/FAKE 模拟实验/)).toBeInTheDocument();
    expect(screen.getByText("SUCCESS")).toBeInTheDocument();
    expect(screen.getByText("NOT_VERIFIED")).toBeInTheDocument();
    expect(
      screen.queryByRole("img", { name: "目标 CPU" }),
    ).not.toBeInTheDocument();
    expect(screen.queryByText("10%")).not.toBeInTheDocument();
    expect(metricBars(sample, "errors")).toEqual([null, null, null]);
    expect(
      screen.getByText(/目前没有与该执行绑定的目标内存/),
    ).toBeInTheDocument();
  });
  it("charts actual saved aggregates and derives throughput only from the exact window", () => {
    const data: ReportAnalysis = {
      ...sample,
      comparisonStatus: "AVAILABLE",
      metricSource: "PROMETHEUS_HTTP_V2",
      metricSubject: "BOUND_ORDER_SERVICE_HTTP_NOT_CONTAINER_CPU",
      metricInstance: "order-service:8080",
      report: {
        ...sample.report,
        bindingStatus: "VERIFIED_LOCAL_DEMO",
        containerId: "a".repeat(64),
        imageId: "sha256:" + "b".repeat(64),
        metricsJob: "order-service",
        route: "/orders/{orderId}",
        metricsStatus: "OBSERVED",
        windows: sample.report.windows.map((w) => ({
          ...w,
          metricsStatus: "OBSERVED",
          estimatedRequests: 120,
          estimated5xx: 12,
          errorRate: 0.1,
          p95Seconds: 0.04,
          scrapeSamples: 12,
          collectionVersion: "PROMETHEUS_HTTP_V2",
        })),
      },
    };
    expect(metricBars(data, "requests")).toEqual([120, 120, 120]);
    expect(metricBars(data, "throughput")).toEqual([2, 2, 2]);
    expect(metricBars(data, "errors")).toEqual([10, 10, 10]);
    expect(metricBars(data, "p95")).toEqual([40, 40, 40]);
    expect(
      metricBars(
        {
          ...data,
          comparisonStatus: "NOT_COMPARABLE",
          traceability: "REJECTED",
        },
        "p95",
      ),
    ).toEqual([null, null, null]);
    const partial = structuredClone(data);
    partial.report.windows[1]!.errorRate = null;
    expect(metricBars(partial, "errors")).toEqual([10, null, 10]);
  });
  it("missing after CPU stays missing; physical recovery and cause remain independent", () => {
    render(
      <MemoryRouter>
        <ReportInsights
          data={{
            ...sample,
            report: { ...sample.report, executionMode: "UNVERIFIED" },
            recovery: evidence,
          }}
        />
      </MemoryRouter>,
    );
    expect(screen.getByText(/After: 未采集/)).toBeInTheDocument();
    expect(screen.getAllByText("VERIFIED").length).toBeGreaterThan(0);
    expect(screen.getByText(/UNKNOWN 不代表 ACTIVE/)).toBeInTheDocument();
  });
});
