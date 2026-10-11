import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { type Identity, anonymous } from "../api/auth";
import { activeExecution } from "../api/fake";
import { FakeExecutionActions } from "./FakeExperiments";
import { Link, useParams } from "react-router-dom";
import { ShieldCheck, Info } from "lucide-react";
import { get, params } from "../api/client";
import {
  executionSchema,
  evidenceSchema,
  pageOf,
  reportSchema,
  auditSchema,
  type Evidence,
} from "../api/contracts";
import {
  Badge,
  Heading,
  Panel,
  QueryState,
  Fields,
  Id,
  date,
  number,
} from "../components/ui";
import { Chart } from "../components/Chart";

export function EvidenceView({ evidence: e }: { evidence: Evidence }) {
  const values = [e.baselineCpuPercent, e.duringCpuPercent, e.afterCpuPercent];
  return (
    <>
      <div className="evidence-grid">
        <Panel
          title="CPU 故障观测"
          subtitle="持久化的直接 cgroup 样本 · 不是连续时间序列"
        >
          <div className="sample-grid">
            {["Baseline", "During", "After"].map((label, i) => (
              <div key={label}>
                <span>{label}</span>
                <strong>{number(values[i], "%")}</strong>
              </div>
            ))}
          </div>
          <Chart
            label={`CPU：baseline ${number(e.baselineCpuPercent, "%")}，during ${number(e.duringCpuPercent, "%")}，after ${number(e.afterCpuPercent, "%")}`}
            option={{
              grid: { left: 48, right: 25, bottom: 32, top: 20 },
              tooltip: { trigger: "axis", renderMode: "richText" },
              xAxis: {
                type: "category",
                data: ["Baseline", "During", "After"],
                axisLine: { lineStyle: { color: "#718096" } },
                axisTick: { show: false },
              },
              yAxis: {
                type: "value",
                name: "%",
                min: 0,
                splitLine: { lineStyle: { color: "#718096", opacity: 0.15 } },
                axisLabel: { color: "#718096" },
              },
              series: [
                {
                  type: "bar",
                  barMaxWidth: 42,
                  itemStyle: { color: "#2dd4bf", borderRadius: [5, 5, 0, 0] },
                  data: values,
                },
              ],
            }}
          />
          <p className="muted small">
            未采集项留空，不以 HEALTHY 推导 CPU 数值。恢复时的 Health
            评估与数值样本是不同证据。
          </p>
        </Panel>
        <Panel title="物理恢复证据" subtitle="历史同主体评估，不是当前实时探针">
          <Fields
            values={[
              ["Physical recovery", <Badge value={e.physicalRecovery} />],
              ["Native status", <Badge value={e.nativeStatus} />],
              ["Residual", <Badge value={e.residual} />],
              ["Health", <Badge value={e.health} />],
              ["Recovery Gate", <Badge value={e.recoveryGate} />],
              ["Recovery cause", <Badge value={e.recoveryCause} />],
            ]}
          />
          <div className="notice compact">
            <Info size={17} />
            <p>
              物理恢复 VERIFIED 不等于 ACTIVE_RECOVERY_CONFIRMED。主动请求与
              timeout 谁实际消除了故障，来源仍为 UNKNOWN。
            </p>
          </div>
        </Panel>
      </div>
      <Panel title="原生身份与证据关联">
        <Fields
          values={[
            ["Native UID", <Id value={e.nativeUid} />],
            ["Snapshot format", e.snapshotFormat ?? "未记录"],
            ["CPU audit ID", <Id value={e.cpuAuditId} />],
            ["Recovery audit ID", <Id value={e.recoveryAuditId} />],
            ["CPU observation", date(e.cpuObservedAt)],
            ["Recovery observation", date(e.recoveryObservedAt)],
          ]}
        />
      </Panel>
    </>
  );
}
export function ExecutionDetail({
  identity = anonymous,
}: {
  identity?: Identity;
}) {
  const [openedAt] = useState(Date.now);
  const { id = "" } = useParams();
  const query = useQuery({
    queryKey: ["execution", id],
    queryFn: ({ signal }) =>
      get(`/api/v1/console/executions/${id}`, executionSchema, signal),
    refetchInterval: (q) =>
      identity.executionMode === "FAKE_DEMO" &&
      Date.now() - openedAt < 90000 &&
      activeExecution(q.state.data?.status ?? "")
        ? 2000
        : false,
  });
  const evidence = useQuery({
    queryKey: ["evidence", id],
    queryFn: ({ signal }) =>
      get(`/api/v1/console/executions/${id}/evidence`, evidenceSchema, signal),
  });
  const reports = useQuery({
    queryKey: ["execution-reports", id],
    queryFn: ({ signal }) =>
      get(
        `/api/v1/console/reports${params({ executionId: id, size: 20 })}`,
        pageOf(reportSchema),
        signal,
      ),
  });
  const audits = useQuery({
    queryKey: ["execution-audits", id, query.data?.status],
    enabled: !!query.data,
    queryFn: ({ signal }) =>
      get(
        `/api/v1/console/audit-logs${params({ experimentId: query.data?.experimentId, size: 100 })}`,
        pageOf(auditSchema),
        signal,
      ),
  });
  return (
    <>
      <Link className="back-link" to="/executions">
        ← 返回执行记录
      </Link>
      <QueryState query={query}>
        {(exec) => (
          <>
            <Heading
              eyebrow="EXECUTION INSPECTION"
              title={`执行 · Attempt ${exec.attempt}`}
              description="执行状态、恢复证据和报告分别呈现。不会仅凭 SUCCESS 宣称恢复因果已经确认。"
              action={<Badge value={exec.status} />}
            />
            {exec.engineExperimentId === `fake-${exec.id}` && (
              <div className="notice simulation">
                <strong>SIMULATED · Fake 执行结果</strong>
                <p>
                  SUCCESS 仅表示模拟生命周期完成，不是物理恢复
                  VERIFIED。自动恢复最多约持续时间 + 扫描间隔。
                </p>
              </div>
            )}
            <FakeExecutionActions identity={identity} execution={exec} />
            {exec.engineExperimentId === `fake-${exec.id}` && (
              <Panel
                title="模拟执行审计"
                subtitle="真实持久化审计时间，不伪造状态转换或物理恢复证据"
              >
                <QueryState query={audits}>
                  {(data) => (
                    <ul className="checks-list">
                      {data.items
                        .filter((a) => a.executionId === exec.id)
                        .map((a) => (
                          <li key={a.id}>
                            <Badge value={a.result} />
                            {a.operation} · {date(a.occurredAt)} · {a.actor}
                          </li>
                        ))}
                    </ul>
                  )}
                </QueryState>
              </Panel>
            )}
            <Panel title="执行身份">
              <Fields
                values={[
                  ["Execution ID", <Id value={exec.id} />],
                  [
                    "Experiment",
                    <Link
                      className="subtle-link mono"
                      to={`/experiments/${exec.experimentId}`}
                    >
                      {exec.experimentId}
                    </Link>,
                  ],
                  ["Native engine ID", <Id value={exec.engineExperimentId} />],
                  ["版本", exec.version],
                  ["安全诊断", exec.errorMessage ?? "无记录"],
                  [
                    "占用解释",
                    [
                      "PREPARING",
                      "CREATE_UNCERTAIN",
                      "RUNNING",
                      "DESTROYING",
                      "ROLLBACK_FAILED",
                    ].includes(exec.status)
                      ? "按状态应保留占用；本页不读取占用锁，不能确认锁实际状态"
                      : "终态不等于锁状态；本页不读取占用锁",
                  ],
                ]}
              />
            </Panel>
            <Panel
              title="记录时间线"
              subtitle="仅展示实际保存的时间；未保存的状态转换不补造时间戳"
            >
              <ol className="timeline">
                {[
                  { label: "执行创建 / PREPARING", at: exec.createdAt },
                  { label: "运行开始", at: exec.startedAt },
                  {
                    label: "主动恢复请求",
                    at: evidence.data?.recoveryAttemptStartedAt,
                  },
                  {
                    label: "Native 状态观察",
                    at: evidence.data?.engineObservedAt,
                  },
                  {
                    label: "恢复证据观察",
                    at: evidence.data?.recoveryObservedAt,
                  },
                  { label: `终态 ${exec.status}`, at: exec.finishedAt },
                ].map((item) => (
                  <li key={item.label} className={item.at ? "recorded" : ""}>
                    <span className="timeline-point" />
                    <div>
                      <strong>{item.label}</strong>
                      <time>{date(item.at)}</time>
                    </div>
                  </li>
                ))}
              </ol>
            </Panel>
            {exec.engineExperimentId === `fake-${exec.id}` ? (
              <Panel title="模拟证据边界">
                没有真实 CPU、Native UID、Residual/Health 或 Recovery Gate
                物理证据。所有未采集项保持 UNKNOWN；请查看模拟审计与报告。
                <Link to={`/audit?experimentId=${exec.experimentId}`}>
                  查看审计事件
                </Link>
              </Panel>
            ) : (
              <QueryState query={evidence}>
                {(e) => <EvidenceView evidence={e} />}
              </QueryState>
            )}
            <Panel
              title="关联报告"
              subtitle="报告结论保持原始独立语义，不被执行状态覆盖"
            >
              <QueryState query={reports}>
                {(data) => (
                  <>
                    <p className="muted">共 {data.total} 份已存报告</p>
                    <div className="report-links">
                      {data.items.map((r) => (
                        <Link
                          className="report-link"
                          key={r.id}
                          to={`/reports/${r.id}`}
                        >
                          <ShieldCheck size={17} />
                          <Id value={r.id} />
                          <Badge value={r.conclusionStatus} />
                        </Link>
                      ))}
                    </div>
                    {data.total > 20 && (
                      <Link
                        className="subtle-link"
                        to={`/reports?executionId=${id}`}
                      >
                        查看全部报告 →
                      </Link>
                    )}
                  </>
                )}
              </QueryState>
            </Panel>
          </>
        )}
      </QueryState>
    </>
  );
}
