import { useQuery } from "@tanstack/react-query";
import { Link, useParams } from "react-router-dom";
import { ArrowUpRight, Info } from "lucide-react";
import { get, params } from "../api/client";
import { pageOf, reportSchema } from "../api/contracts";
import {
  Badge,
  Heading,
  Panel,
  QueryState,
  Empty,
  Pagination,
  Fields,
  Id,
  date,
  number,
} from "../components/ui";
import { useFilters } from "./Experiments";
export function Reports() {
  const { search, page, update } = useFilters();
  const executionId = search.get("executionId") ?? "";
  const query = useQuery({
    queryKey: ["reports", executionId, page],
    queryFn: ({ signal }) =>
      get(
        `/api/v1/console/reports${params({ page, size: 20, executionId })}`,
        pageOf(reportSchema),
        signal,
      ),
  });
  return (
    <>
      <Heading
        eyebrow="EXPERIMENT REPORTS"
        title="实验报告"
        description="读取已持久化报告。物理恢复、执行来源、指标完整度和报告结论互不替代。"
      />
      <div className="notice">
        <Info size={18} />
        <p>
          INSUFFICIENT_DATA 与 UNKNOWN 是有效结果，不应被美化为
          VERIFIED。本控制台不生成报告，也不重新解释历史报告。
        </p>
      </div>
      <Panel>
        <div className="filters">
          <label>
            Execution ID
            <input
              placeholder="完整 UUID（可选）"
              value={executionId}
              onChange={(e) => update("executionId", e.target.value)}
            />
          </label>
        </div>
        <QueryState query={query}>
          {(data) => (
            <>
              {!data.items.length ? (
                <Empty
                  text="暂无已生成报告"
                  detail="报告只能由既有应用流程生成，本控制台仅提供查看。"
                />
              ) : (
                <div className="table-scroll">
                  <table>
                    <thead>
                      <tr>
                        <th>报告 / 场景</th>
                        <th>指标</th>
                        <th>结论</th>
                        <th>生成时间</th>
                        <th>
                          <span className="sr-only">详情</span>
                        </th>
                      </tr>
                    </thead>
                    <tbody>
                      {data.items.map((r) => (
                        <tr key={r.id}>
                          <td>
                            <Link
                              className="table-title mono"
                              to={`/reports/${r.id}`}
                            >
                              {r.id}
                            </Link>
                            <small className="muted">{r.scenarioCode}</small>
                          </td>
                          <td>
                            <Badge value={r.metricsStatus} />
                          </td>
                          <td>
                            <Badge value={r.conclusionStatus} />
                          </td>
                          <td>{date(r.generatedAt)}</td>
                          <td>
                            <Link
                              className="icon-button"
                              aria-label={`查看报告 ${r.id}`}
                              to={`/reports/${r.id}`}
                            >
                              <ArrowUpRight size={17} />
                            </Link>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
              <Pagination {...data} onPage={(p) => update("page", String(p))} />
            </>
          )}
        </QueryState>
      </Panel>
    </>
  );
}
export function ReportDetail() {
  const { id = "" } = useParams();
  const query = useQuery({
    queryKey: ["report", id],
    queryFn: ({ signal }) =>
      get(`/api/v1/console/reports/${id}`, reportSchema, signal),
  });
  return (
    <>
      <Link className="back-link" to="/reports">
        ← 返回报告列表
      </Link>
      <QueryState query={query}>
        {(r) => (
          <>
            <Heading
              eyebrow="REPORT INSPECTION"
              title="实验报告"
              description={r.reason}
              action={<Badge value={r.conclusionStatus} />}
            />
            <Panel title="独立结论维度">
              <Fields
                values={[
                  ["执行来源", <Badge value={r.executionMode} />],
                  ["指标采集", <Badge value={r.metricsStatus} />],
                  ["报告结论", <Badge value={r.conclusionStatus} />],
                  ["指标绑定", <Badge value={r.bindingStatus} />],
                  ["Report ID", <Id value={r.id} />],
                  [
                    "Execution",
                    <Link
                      className="subtle-link mono"
                      to={`/executions/${r.executionId}`}
                    >
                      {r.executionId}
                    </Link>,
                  ],
                  ["生成时间", date(r.generatedAt)],
                  ["场景", r.scenarioCode],
                  ["Start audit", <Id value={r.startAuditId} />],
                  ["Recovery audit", <Id value={r.recoveryAuditId} />],
                  ["Container ID", <Id value={r.containerId} />],
                  ["Image ID", <Id value={r.imageId} />],
                  ["Metrics job", r.metricsJob ?? "未绑定"],
                  ["Route", r.route ?? "未绑定"],
                ]}
              />
              <div className="notice compact">
                <Info size={17} />
                <p>
                  后端 SUCCESS 不会把 UNVERIFIED、NOT_VERIFIED
                  或尚未采集的指标自动改写为 VERIFIED。
                </p>
              </div>
            </Panel>
            <Panel
              title="Before / During / After"
              subtitle="展示报告保存的观察窗口；缺失值留空，不作零值填充"
            >
              <div className="table-scroll">
                <table>
                  <thead>
                    <tr>
                      <th>窗口</th>
                      <th>时间范围</th>
                      <th>采集结论</th>
                      <th>Scrapes</th>
                      <th>Requests</th>
                      <th>5xx</th>
                      <th>Error rate</th>
                      <th>P95</th>
                    </tr>
                  </thead>
                  <tbody>
                    {r.windows.map((w) => (
                      <tr key={w.phase}>
                        <td>
                          <strong>{w.phase.toUpperCase()}</strong>
                          {w.reason && (
                            <small className="muted">{w.reason}</small>
                          )}
                        </td>
                        <td>
                          <small>{date(w.start)}</small>
                          <small>{date(w.end)}</small>
                        </td>
                        <td>
                          <Badge value={w.metricsStatus} />
                        </td>
                        <td>{number(w.scrapeSamples)}</td>
                        <td>{number(w.estimatedRequests)}</td>
                        <td>{number(w.estimated5xx)}</td>
                        <td>
                          {number(
                            w.errorRate == null ? null : w.errorRate * 100,
                            "%",
                          )}
                        </td>
                        <td>{number(w.p95Seconds, "s")}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </Panel>
          </>
        )}
      </QueryState>
    </>
  );
}
