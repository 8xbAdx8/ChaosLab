import { useQuery } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import {
  FlaskConical,
  Activity,
  ShieldCheck,
  FileBarChart,
  ArrowUpRight,
  Server,
  Database,
} from "lucide-react";
import { get } from "../api/client";
import { overviewSchema, healthSchema } from "../api/contracts";
import {
  Badge,
  Heading,
  Panel,
  QueryState,
  Empty,
  number,
  date,
} from "../components/ui";
import { Chart } from "../components/Chart";

export function Dashboard() {
  const overview = useQuery({
    queryKey: ["overview"],
    queryFn: ({ signal }) =>
      get("/api/v1/console/overview", overviewSchema, signal),
  });
  const health = useQuery({
    queryKey: ["health"],
    queryFn: ({ signal }) => get("/actuator/health", healthSchema, signal),
    retry: false,
  });
  return (
    <>
      <Heading
        eyebrow="WORKSPACE OVERVIEW"
        title="看见故障，也看见恢复。"
        description="统一查看实验、执行与恢复证据。每一个结论，都应有对应的证据。"
        action={
          <span className="heading-label">
            <span className="status-dot" />
            LIVE API · READ ONLY
          </span>
        }
      />
      <QueryState query={overview}>
        {(data) => (
          <>
            <div className={`notice ${!data.readOnlyMode ? "warning" : ""}`}>
              <ShieldCheck size={18} />
              <p>
                {data.readOnlyMode
                  ? "后端只读模式已开启。"
                  : "后端尚未开启 console 只读模式；本界面不提供写操作，但直接 API 仍可能允许写入。"}{" "}
                当前未配置登录鉴权，请仅在本机或受限可信网络使用，不得公网暴露。
              </p>
            </div>
            <div className="kpi-grid">
              {[
                {
                  label: "实验总数",
                  value: data.experimentCount.toLocaleString(),
                  icon: FlaskConical,
                  note: `${data.targetCount} 个已登记目标`,
                },
                {
                  label: "累计执行",
                  value: data.executionCount.toLocaleString(),
                  icon: Activity,
                  note: `${data.executionStates.RUNNING ?? 0} 个正在运行`,
                },
                {
                  label: "执行成功率",
                  value: number(data.successRate, "%"),
                  icon: ShieldCheck,
                  note: "SUCCESS / 全部执行 · 不代表因果已确认",
                },
                {
                  label: "已存报告",
                  value: data.reportCount.toLocaleString(),
                  icon: FileBarChart,
                  note: "仅展示已有报告，不生成或改写",
                },
              ].map((k) => (
                <div className="kpi" key={k.label}>
                  <div className="kpi-label">
                    {k.label}
                    <k.icon size={18} />
                  </div>
                  <strong>{k.value}</strong>
                  <span>{k.note}</span>
                </div>
              ))}
            </div>
            <div className="dashboard-grid">
              <Panel
                title="执行状态分布"
                subtitle="真实状态统计，不将终态自动解释为恢复证明"
                action={
                  <Link className="subtle-link" to="/executions">
                    查看全部 <ArrowUpRight size={15} />
                  </Link>
                }
              >
                {data.executionCount === 0 ? (
                  <Empty text="尚无执行记录" />
                ) : (
                  <div className="distribution">
                    <Chart
                      label="执行状态数量分布，完整数值见右侧列表"
                      option={{
                        tooltip: { trigger: "item", renderMode: "richText" },
                        series: [
                          {
                            type: "pie",
                            radius: ["62%", "80%"],
                            center: ["50%", "50%"],
                            label: { show: false },
                            itemStyle: {
                              borderRadius: 4,
                              borderWidth: 3,
                              borderColor: "transparent",
                            },
                            data: Object.entries(data.executionStates)
                              .filter(([, value]) => value > 0)
                              .map(([name, value]) => ({
                                name,
                                value,
                                itemStyle: {
                                  color:
                                    name === "SUCCESS"
                                      ? "#2dd4bf"
                                      : name === "ROLLBACK_FAILED" ||
                                          name === "FAILED"
                                        ? "#f87171"
                                        : "#60a5fa",
                                },
                              })),
                          },
                        ],
                      }}
                    />
                    <ul className="state-list">
                      {Object.entries(data.executionStates).map(
                        ([state, count]) => (
                          <li key={state}>
                            <Badge value={state} />
                            <strong>{count}</strong>
                          </li>
                        ),
                      )}
                    </ul>
                  </div>
                )}
              </Panel>
              <Panel
                title="系统连接"
                subtitle="接口可用性与配置，不代表 sandbox 应用健康"
              >
                <div className="system-line">
                  <span>
                    <Server size={17} />
                    Spring Boot
                  </span>
                  <Badge
                    value={
                      health.data?.status ??
                      (health.isError ? "UNKNOWN" : "CHECKING")
                    }
                  />
                </div>
                <div className="system-line">
                  <span>
                    <Database size={17} />
                    数据库查询
                  </span>
                  <Badge value="UP" />
                </div>
                <div className="system-line">
                  <span>注册引擎</span>
                  <code>{data.engineMode}</code>
                </div>
                <div className="system-line">
                  <span>接口鉴权</span>
                  <Badge value="NOT_CONFIGURED" />
                </div>
                <p className="muted small system-note">
                  读取时间：
                  {date(new Date(overview.dataUpdatedAt).toISOString())}
                  <br />
                  此页不调用 root wrapper 或实时故障探针。
                </p>
              </Panel>
            </div>
            <Panel
              title="最近实验活动"
              subtitle="按最新执行时间排序；无执行的实验列在后面"
              action={
                <Link className="subtle-link" to="/experiments">
                  实验管理 <ArrowUpRight size={15} />
                </Link>
              }
            >
              {data.recentExperiments.length === 0 ? (
                <Empty />
              ) : (
                <div className="table-scroll">
                  <table>
                    <thead>
                      <tr>
                        <th>实验</th>
                        <th>假设</th>
                        <th>时长</th>
                        <th>状态</th>
                        <th>
                          <span className="sr-only">详情</span>
                        </th>
                      </tr>
                    </thead>
                    <tbody>
                      {data.recentExperiments.map((exp) => (
                        <tr key={exp.id}>
                          <td>
                            <Link
                              className="table-title"
                              to={`/experiments/${exp.id}`}
                            >
                              {exp.name}
                            </Link>
                            <small className="muted mono">{exp.id}</small>
                          </td>
                          <td className="truncate">{exp.hypothesis}</td>
                          <td>{exp.durationSeconds}s</td>
                          <td>
                            <Badge value={exp.status} />
                          </td>
                          <td>
                            <Link
                              className="icon-button"
                              aria-label={`查看 ${exp.name}`}
                              to={`/experiments/${exp.id}`}
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
            </Panel>
          </>
        )}
      </QueryState>
    </>
  );
}
