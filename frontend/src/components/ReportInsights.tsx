import { Chart } from "./Chart";
import { Link } from "react-router-dom";
import type { EChartsCoreOption } from "echarts/core";
import type { ReportAnalysis } from "../api/contracts";
import { Badge, Empty, Fields, Id, Panel, date, number } from "./ui";

type Window = ReportAnalysis["report"]["windows"][number];

// Chart points are saved window aggregates, never a fabricated high-frequency stream.
export function metricBars(
  data: ReportAnalysis,
  metric: "requests" | "throughput" | "errors" | "p95",
): (number | null)[] {
  if (data.comparisonStatus !== "AVAILABLE")
    return data.report.windows.map(() => null);
  return data.report.windows.map((w: Window) => {
    if (w.metricsStatus !== "OBSERVED") return null;
    if (metric === "requests") return w.estimatedRequests;
    if (metric === "errors")
      return w.errorRate == null ? null : w.errorRate * 100;
    if (metric === "p95")
      return w.p95Seconds == null ? null : w.p95Seconds * 1000;
    const seconds = (Date.parse(w.end) - Date.parse(w.start)) / 1000;
    return w.estimatedRequests == null || seconds <= 0
      ? null
      : w.estimatedRequests / seconds;
  });
}
function Bars({
  values,
  label,
  unit,
}: {
  values: (number | null)[];
  label: string;
  unit: string;
}) {
  if (!values.some((v) => v != null))
    return (
      <Empty
        text={`${label} · NOT_COLLECTED / INSUFFICIENT_DATA`}
        detail="没有可比较的同主体、三阶段数据。缺失值不是 0。"
      />
    );
  const option: EChartsCoreOption = {
    color: ["#22b8a5"],
    tooltip: { trigger: "axis" },
    grid: { left: 64, right: 20, bottom: 40, top: 25 },
    xAxis: {
      type: "category",
      data: ["Before", "During", "After"],
      axisLabel: { color: "#8b97ab" },
    },
    yAxis: {
      type: "value",
      name: unit,
      axisLabel: { color: "#8b97ab" },
      splitLine: { lineStyle: { color: "#8b97ab33" } },
    },
    series: [{ type: "bar", data: values, barMaxWidth: 56 }],
  };
  return (
    <>
      <Chart option={option} label={label} />
      <p className="muted">
        {values
          .map(
            (v, i) => `${["Before", "During", "After"][i]}: ${number(v, unit)}`,
          )
          .join(" · ")}
      </p>
    </>
  );
}
export function ReportInsights({ data }: { data: ReportAnalysis }) {
  const { report, execution, recovery } = data;
  const simulated = report.executionMode === "SIMULATED";
  return (
    <>
      <Panel
        title="实验与执行上下文"
        subtitle="名称为当前定义；执行参数以成功启动审计快照为准，不从当前表单回填历史"
      >
        <Fields
          values={[
            [
              "实验",
              <Link
                className="subtle-link"
                to={`/experiments/${report.experimentId}`}
              >
                {data.experimentName}
              </Link>,
            ],
            ["Experiment ID", <Id value={report.experimentId} />],
            ["目标", `${data.targetName} · ${data.targetType}`],
            ["Target ID", <Id value={report.targetId} />],
            ["执行状态", <Badge value={execution.status} />],
            ["开始", date(execution.startedAt)],
            ["结束", date(execution.finishedAt)],
            [
              "持续时间",
              number(
                data.durationMillis == null ? null : data.durationMillis / 1000,
                "s",
              ),
            ],
            ["参数来源", <Badge value={data.parameterSource} />],
            ["审计关联", <Badge value={data.traceability} />],
          ]}
        />
        <pre className="code-block">
          {data.parameterSource === "START_AUDIT"
            ? JSON.stringify(data.parameters, null, 2)
            : "UNKNOWN：缺少匹配的启动审计参数"}
        </pre>
        {simulated && (
          <div className="notice">
            <p>
              FAKE 模拟实验 · SIMULATED。SUCCESS
              仅代表模拟生命周期完成，不代表真实 CPU 故障或物理恢复。
            </p>
          </div>
        )}
      </Panel>
      <Panel
        title="数据来源与比较资格"
        subtitle="读取持久化报告，不向 Prometheus 发起即时查询，不接触执行器"
      >
        <Fields
          values={[
            ["采集版本", <Badge value={data.metricSource} />],
            ["三阶段比较", <Badge value={data.comparisonStatus} />],
            [
              "数据主体",
              data.metricSubject === "UNKNOWN"
                ? "UNKNOWN：目标与 HTTP 指标尚未绑定"
                : "绑定的 order-service HTTP 路由（不是容器 CPU）",
            ],
            ["Job", report.metricsJob ?? "未绑定"],
            ["Instance", data.metricInstance ?? "UNKNOWN"],
            ["Route", report.route ?? "未绑定"],
          ]}
        />
        <ul>
          {data.limitations.map((text) => (
            <li key={text} className="muted">
              {text}
            </li>
          ))}
        </ul>
        <p className="muted">
          旧报告没有采集版本时保留原值和原结论，但不升级为 M5 已验证比较。HTTP
          P95 是直方图估计，不是故障因果证明。
        </p>
      </Panel>
      <div className="report-metrics-grid">
        {(
          [
            ["requests", "HTTP 请求数", "次"],
            ["throughput", "平均吞吐量", "req/s"],
            ["errors", "HTTP 5xx 错误率", "%"],
            ["p95", "HTTP P95 延迟", "ms"],
          ] as const
        ).map(([key, label, unit]) => (
          <Panel
            key={key}
            title={label}
            subtitle="实际三阶段聚合值 · 非实时曲线"
          >
            <Bars values={metricBars(data, key)} label={label} unit={unit} />
          </Panel>
        ))}
        <Panel
          title="目标 CPU 证据"
          subtitle="独立的同主体已存 cgroup 审计样本；没有样本不显示配置 percent"
        >
          <Bars
            values={
              simulated
                ? [null, null, null]
                : [
                    recovery.baselineCpuPercent,
                    recovery.duringCpuPercent,
                    recovery.afterCpuPercent,
                  ]
            }
            label="目标 CPU"
            unit="%"
          />
        </Panel>
        <Panel title="目标内存">
          <Empty
            text="NOT_COLLECTED"
            detail="目前没有与该执行绑定的目标内存观测。后台 JVM 内存不能替代目标容器内存。"
          />
        </Panel>
      </div>
      <Panel title="恢复证据：独立于 HTTP 报告结论">
        <Fields
          values={[
            ["Native UID", <Id value={recovery.nativeUid} />],
            ["Native state", <Badge value={recovery.nativeStatus} />],
            [
              "Physical recovery",
              <Badge
                value={simulated ? "NOT_VERIFIED" : recovery.physicalRecovery}
              />,
            ],
            ["Residual", <Badge value={recovery.residual} />],
            ["Health", <Badge value={recovery.health} />],
            ["Recovery Gate", <Badge value={recovery.recoveryGate} />],
            ["Recovery cause", <Badge value={recovery.recoveryCause} />],
            ["CPU audit", <Id value={recovery.cpuAuditId} />],
            ["Recovery audit", <Id value={recovery.recoveryAuditId} />],
            ["CPU observed", date(recovery.cpuObservedAt)],
            ["Recovery observed", date(recovery.recoveryObservedAt)],
          ]}
        />
        <p className="muted">
          {recovery.note} UNKNOWN 不代表 ACTIVE。物理恢复 VERIFIED
          也不能补齐应用 P95 或错误率证据。
        </p>
        <Link className="subtle-link" to="/history/m1-core">
          查看 SHA 校验的 M1 历史真实实验（独立归档，不是 Fake 数据库） →
        </Link>
      </Panel>
      <Panel
        title="审计事件与执行时间线"
        subtitle="仅关联同 Experiment / Execution，最多 100 条；操作成功不自动等同指标或物理恢复验证"
      >
        {!data.audits.length ? (
          <Empty text="未找到关联审计事件" />
        ) : (
          <ol className="timeline">
            {data.audits.map((audit) => (
              <li key={audit.id}>
                <div className="timeline-point" />
                <div>
                  <strong>{audit.operation}</strong>{" "}
                  <Badge value={audit.result} />
                  <p className="muted">
                    {date(audit.occurredAt)} · {audit.actor}
                  </p>
                  <Id value={audit.id} />
                  {audit.failureCode && <p>{audit.failureCode}</p>}
                </div>
              </li>
            ))}
          </ol>
        )}
        {data.auditsTruncated && <p>列表已截断，完整记录请按实验查询。</p>}
        <Link
          className="subtle-link"
          to={`/audit?experimentId=${report.experimentId}`}
        >
          关联审计日志 →
        </Link>
      </Panel>
      <Panel title="指标与报告可信度说明">
        <p>
          时间序列是带时间戳的观测点。这里只保存 Before / During / After
          聚合值，因此用柱状图，不补造连续曲线。
        </p>
        <p>
          错误率 = 同窗口 5xx 请求数 ÷ 总请求数；P95
          从同路由的累计直方图计算。V2 至少要求 20
          次请求、足够抓取样本、完整直方图及明确错误数；不足时不计算可信比较。20
          次是教学门槛，不是统计置信保证。
        </p>
        <p>
          报告解释发生了什么及哪些证据不足；审计记录谁在何时做了什么。二者通过
          execution 和审计 ID 关联，而不是靠名字或 SUCCESS 猜测。
        </p>
      </Panel>
    </>
  );
}
