import { useQuery } from "@tanstack/react-query";
import { Archive, Info, ShieldCheck, ExternalLink } from "lucide-react";
import { get } from "../api/client";
import {
  historySchema,
  findFact,
  factText,
  factNumber,
  scalarText,
  type History,
  type Fact,
} from "../api/history";
import {
  Badge,
  Heading,
  Panel,
  QueryState,
  Fields,
  Id,
  number,
} from "../components/ui";
import { Chart } from "../components/Chart";

export const historyTime = (value: string | null) =>
  value
    ? new Intl.DateTimeFormat("zh-CN", {
        dateStyle: "medium",
        timeStyle: "medium",
        hour12: false,
        timeZone: "Asia/Shanghai",
      }).format(new Date(value))
    : "未记录";
const phaseNames = {
  baseline: "Baseline · 故障前",
  during: "During · 故障中",
  after: "After · 恢复后",
};
function Trace({ facts, keys }: { facts: Fact[]; keys?: string[] }) {
  const selected = keys ? facts.filter((f) => keys.includes(f.key)) : facts;
  return (
    <details className="history-trace">
      <summary>查看字段与原始 JSON Pointer</summary>
      <dl>
        {selected.map((f) => (
          <div key={f.key}>
            <dt>
              {f.label} <code>{f.pointer}</code>
            </dt>
            <dd>{scalarText(f.value)}</dd>
          </div>
        ))}
      </dl>
    </details>
  );
}
function HistoryContent({ data: h }: { data: History }) {
  const text = (key: string) => factText(h.facts, key);
  const value = (key: string) => findFact(h.facts, key)?.value;
  const cpu = h.cpu.map((s) =>
    typeof s.percent.value === "number" ? s.percent.value : null,
  );
  const occupancy =
    value("occupancyRetained") === false
      ? "已释放（归档值 false）"
      : value("occupancyRetained") === true
        ? "仍保留（归档值 true）"
        : "未记录 / UNKNOWN";
  return (
    <>
      <Heading
        eyebrow="M1 CORE / ARCHIVED FIELD EXPERIMENT"
        title="一次真实故障，一次安全恢复"
        description="历史真实实验 · 已结束 · 非实时监控"
        action={
          <span className="history-seal">
            <Archive size={16} />
            SHA-256 校验通过
          </span>
        }
      />
      <div className="notice">
        <Info size={18} />
        <p>
          来自 Git 归档的真实 M1 Core，不是模拟数据，也不是 VM
          的当前状态。刷新只会重新读取同一证据包，不会运行任何探针或故障命令。
        </p>
      </div>
      <div className="kpi-grid history-kpis">
        {[
          ["后端终态", "backendStatus", "生产事务结果"],
          ["物理恢复", "physicalRecovery", "同主体物理恢复证据"],
          ["Recovery Gate", "gate", "生产 Validator / Gate"],
          ["恢复来源", "recoveryCause", "未取得 ACTIVE / TIMEOUT 因果回执"],
        ].map(([label, key, note]) => (
          <div className="kpi" key={key}>
            <div className="kpi-label">
              {label}
              <ShieldCheck size={15} />
            </div>
            <div className="history-kpi-value">
              <Badge value={text(key!)} />
            </div>
            <span>{note}</span>
          </div>
        ))}
      </div>
      <Panel
        title="实验概要与固定目标"
        subtitle="身份直接来自归档；不是新建的业务数据库记录"
      >
        <Fields
          values={[
            ["Experiment ID", <Id value={text("experimentId")} />],
            ["Execution ID", <Id value={text("executionId")} />],
            ["Native UID", <Id value={text("nativeUid")} />],
            ["Sandbox", text("containerName") ?? "未记录"],
            ["Container ID", <Id value={text("containerId")} />],
            ["Image ID", <Id value={text("imageId")} />],
            [
              "故障参数",
              `CPU ${number(factNumber(h.facts, "cpuPercent"), "%")} · timeout ${number(factNumber(h.facts, "timeoutSeconds"), "s")}`,
            ],
            [
              "CPU 核数",
              <>
                <span>{h.cpuCountReportClaim.label}</span>
                <p className="muted small">
                  来源：已校验验收报告，不由 SUCCESS 推导
                </p>
              </>,
            ],
            [
              "create / 主动 destroy 请求",
              `${number(factNumber(h.facts, "createRequests"))} / ${number(factNumber(h.facts, "destroyRequests"))} 次`,
            ],
            ["占用", occupancy],
            [
              "执行节点 / 状态代次",
              <>
                <Id value={text("nodeId")} /> / <Id value={text("stateId")} />
              </>,
            ],
            ["主动恢复请求", <Badge value={text("activeDestroyRequest")} />],
          ]}
        />
        <Trace
          facts={h.facts}
          keys={[
            "experimentId",
            "executionId",
            "nativeUid",
            "containerName",
            "containerId",
            "imageId",
            "cpuPercent",
            "cpuCount",
            "timeoutSeconds",
            "createRequests",
            "destroyRequests",
            "occupancyRetained",
            "commitProof",
            "intentStatus",
            "automaticRetry",
          ]}
        />
      </Panel>
      <div className="evidence-grid">
        <Panel
          title="CPU 三阶段对比"
          subtitle="三个实际 cgroup 样本 · 非连续曲线 · 不插值"
        >
          <div className="sample-grid">
            {h.cpu.map((s) => (
              <div key={s.phase}>
                <span>{phaseNames[s.phase]}</span>
                <strong>
                  {number(
                    typeof s.percent.value === "number"
                      ? s.percent.value
                      : null,
                    "%",
                  )}
                </strong>
              </div>
            ))}
          </div>
          <Chart
            label={`历史 CPU：baseline ${number(cpu[0], "%")}，during ${number(cpu[1], "%")}，after ${number(cpu[2], "%")}；三个离散样本，不是实时曲线`}
            option={{
              grid: { left: 40, right: 20, bottom: 30, top: 24 },
              tooltip: { trigger: "axis", renderMode: "richText" },
              xAxis: {
                type: "category",
                data: ["Baseline", "During", "After"],
                axisLine: { lineStyle: { color: "#718096" } },
                axisTick: { show: false },
                axisLabel: { color: "#718096" },
              },
              yAxis: {
                type: "value",
                name: "%",
                min: 0,
                axisLabel: { color: "#718096" },
                splitLine: { lineStyle: { color: "#718096", opacity: 0.15 } },
              },
              series: [
                {
                  type: "bar",
                  barMaxWidth: 42,
                  itemStyle: { color: "#2dd4bf", borderRadius: [5, 5, 0, 0] },
                  data: cpu,
                },
              ],
            }}
          />
          <details className="history-trace">
            <summary>样本时间与来源（显示时间 UTC+8）</summary>
            <dl>
              {h.cpu.map((s) => (
                <div key={s.phase}>
                  <dt>
                    {phaseNames[s.phase]} · {historyTime(s.observedAt)}
                  </dt>
                  <dd>
                    <code>{s.percent.pointer}</code>
                    <br />
                    <code>{s.timePointer}</code>
                    <br />
                    <span className="muted">
                      原始时间：{s.observedAt ?? "未记录"}
                    </span>
                  </dd>
                </div>
              ))}
            </dl>
          </details>
          <p className="muted small">
            After 是终态后保存的只读数值样本，不是额外的终态否决条件；生产 Gate
            已在 SUCCESS 前完成物理恢复判断。
          </p>
        </Panel>
        <Panel title="故障已消除，来源仍未知" subtitle="三个不同概念，分别表达">
          <Fields
            values={[
              ["Native status", <Badge value={text("nativeStatus")} />],
              ["Residual", <Badge value={text("residual")} />],
              ["Health", <Badge value={text("health")} />],
              ["验收结果", scalarText(value("result"))],
              ["物理恢复", <Badge value={text("physicalRecovery")} />],
              ["恢复因果来源", <Badge value={text("recoveryCause")} />],
            ]}
          />
          <div className="notice compact warning">
            <Info size={17} />
            <p>
              VERIFIED：系统已物理恢复。SUCCESS：所有 M1 Core
              条件已在生产链通过。UNKNOWN：无法判断主动 destroy 或 timeout
              谁实际消除了故障，不能称为 ACTIVE_RECOVERY_CONFIRMED。
            </p>
          </div>
          <Trace
            facts={h.facts}
            keys={[
              "nativeStatus",
              "residual",
              "health",
              "gate",
              "physicalRecovery",
              "recoveryCause",
              "backendStatus",
              "result",
            ]}
          />
        </Panel>
      </div>
      <Panel
        title="恢复观察：从残留到稳定"
        subtitle="原始两轮生产摘要；第一轮是中间观察，不是实验终态"
      >
        <div className="table-scroll">
          <table>
            <thead>
              <tr>
                <th>回合 / 时间（UTC+8）</th>
                <th>Native</th>
                <th>Residual</th>
                <th>Health</th>
                <th>Gate / 可提交成功</th>
              </tr>
            </thead>
            <tbody>
              {h.recoveryRounds.map((r) => (
                <tr key={r.pointer}>
                  <td>
                    Round {number(factNumber(r.facts, "round"))}
                    <small className="muted">
                      {historyTime(factText(r.facts, "observedAt"))}
                    </small>
                  </td>
                  <td>
                    <Badge value={factText(r.facts, "nativeStatus")} />
                  </td>
                  <td>
                    <Badge value={factText(r.facts, "residual")} />
                  </td>
                  <td>
                    <Badge value={factText(r.facts, "health")} />
                  </td>
                  <td>
                    <Badge value={factText(r.facts, "gateOutcome")} />
                    <small className="muted">
                      successEligible ={" "}
                      {scalarText(findFact(r.facts, "successEligible")?.value)}
                    </small>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        {h.recoveryRounds.length === 0 && (
          <p className="muted">未记录恢复观察回合</p>
        )}
        <details className="history-trace">
          <summary>各轮精确字段来源</summary>
          {h.recoveryRounds.map((r) => (
            <Trace key={r.pointer} facts={r.facts} />
          ))}
        </details>
      </Panel>
      <Panel
        title="执行与恢复时间线"
        subtitle="仅使用已归档时间戳，显示为 Asia/Shanghai（UTC+8）；不以 Native 时间差推断恢复原因"
      >
        <ol className="history-timeline">
          {h.timeline.map((e) => (
            <li key={e.pointer} className={e.observedAt ? "recorded" : ""}>
              <span className="timeline-point" />
              <div>
                <strong>{e.label}</strong>
                <time dateTime={e.observedAt ?? undefined}>
                  {historyTime(e.observedAt)}
                </time>
                <details>
                  <summary>原始时间与字段</summary>
                  <code>
                    {e.observedAt ?? "未记录"}
                    <br />
                    {e.pointer}
                  </code>
                </details>
              </div>
            </li>
          ))}
        </ol>
      </Panel>
      <Panel
        title="生产审计事件"
        subtitle="归档中已有的 4 条事件；无虚构 audit ID，SQL 时间保留原值（时区未单列）"
      >
        {h.audits.map((a) => (
          <article className="audit-item" key={a.pointer}>
            <div className="audit-header">
              <div>
                <strong>{a.operation ?? "UNKNOWN"}</strong>
                <small className="muted">
                  {a.occurredAtRaw ?? "未记录"} · 原始 SQL 时间
                </small>
              </div>
              <Badge value={a.result} />
            </div>
            <Trace facts={a.parameters} />
            <p className="muted small">
              来源：<code>{a.pointer}</code>
            </p>
          </article>
        ))}
        {h.audits.length === 0 && (
          <p className="muted">原始证据未记录审计事件</p>
        )}
      </Panel>
      <Panel
        title="证据来源与校验"
        subtitle="读取前校验 SHA-256；不是重新运行现场验收"
      >
        <div className="evidence-grid">
          {[h.evidenceSource, h.reportSource].map((s) => (
            <div className="history-source" key={s.path}>
              <a
                className="subtle-link"
                href={s.url}
                target="_blank"
                rel="noopener noreferrer"
              >
                <ExternalLink size={14} />
                {s.path}
              </a>
              <Fields
                values={[
                  ["SHA-256", <Id value={s.sha256} />],
                  ["校验模式", s.checksumMode],
                  ["首次归档提交", <Id value={s.archiveCommit} />],
                ]}
              />
            </div>
          ))}
        </div>
        <p className="muted small">
          JSON 校验原始字节；Markdown 按 UTF-8 / LF 标准化校验，适配 Git 的
          Windows 文本换行。原始 JSON 没有更改。
        </p>
        <details className="history-trace">
          <summary>CPU count 的独立报告依据</summary>
          <blockquote>{h.cpuCountReportClaim.quote}</blockquote>
          <p className="muted small">
            报告章节：{h.cpuCountReportClaim.section}。JSON
            /committedIntent/cpuCount = null。
          </p>
        </details>
        <Trace
          facts={h.facts}
          keys={["codeCommit", "toolSha256", "policyDigest"]}
        />
      </Panel>
      <Panel
        title="仍未采集的内容"
        subtitle="不根据验收 PASS 或后端 SUCCESS 补造业务数据或报告结论"
      >
        <p className="muted">
          业务实验名称与假设、应用 P95 / 错误率、Native 完成的精确时间、ACTIVE /
          TIMEOUT winner identity 没有保存在这份证据中。本页不生成
          experiment_reports，也不把这些缺项标为 VERIFIED。
        </p>
        <Trace
          facts={h.facts}
          keys={[
            "experimentName",
            "hypothesis",
            "nativeDestroyedAt",
            "requestP95",
            "requestErrorRate",
            "recoveryCause",
          ]}
        />
      </Panel>
    </>
  );
}
export function HistoryPage() {
  const query = useQuery({
    queryKey: ["history", "m1-core"],
    staleTime: Infinity,
    queryFn: ({ signal }) =>
      get("/api/v1/console/history/m1-core", historySchema, signal),
  });
  return (
    <QueryState query={query}>{(h) => <HistoryContent data={h} />}</QueryState>
  );
}
export { HistoryContent };
