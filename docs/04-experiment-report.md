# 平台内实验报告：第一增量

平台现在可以为一次**已成功恢复**的执行生成并查询不可变报告快照。它不是故障效果报告：后端尚未采集 Prometheus 三阶段指标，当前所有新报告的 `metricsStatus=NOT_COLLECTED`、`conclusionStatus=INSUFFICIENT_DATA`。报告不会虚构请求量、错误率或 P95，也不会把 Fake 引擎的 `SUCCESS` 当作真实故障证据。

## 证据与时间窗口

生成前要求执行记录属于 URL 中的实验、状态为 `SUCCESS`，并具有正时长的服务端开始/结束时间。还要求同一执行具有成功启动和成功销毁/恢复的审计事件；两者目标一致，且匹配实验目标，启动事件的场景代码与实验场景一致，时间顺序正确。报告保存执行、目标、场景、两条审计事件的 ID 与生成时间，以便回查。审计事件超过现有查询上限时拒绝生成，不静默截断。

设执行开始为 `S`、结束为 `E`、持续时间为 `D=E-S`：

| 阶段 | 窗口 | 当前指标状态 |
| --- | --- | --- |
| before | `[S-D, S)` | NOT_COLLECTED |
| during | `[S, E)` | NOT_COLLECTED |
| after | `[E, E+D)` | NOT_COLLECTED |

`executionMode=SIMULATED` 只在执行器 ID 与当前 Fake 引擎的 `fake-{executionId}` 完全一致时使用；其他 ID 一律标为 `UNVERIFIED`，不推断其为真实故障。执行模式、指标状态和结论状态是独立维度。未来即使三阶段指标完整，也只能先标记描述性比较可用；仅凭时间相关性不能证明故障因果。

## API 与持久化

```text
POST /api/v1/experiments/{experimentId}/executions/{executionId}/reports
Idempotency-Key: report-v1

GET /api/v1/experiments/{experimentId}/executions/{executionId}/reports/{reportId}
```

首次生成返回 `201` 和 `Location`；同一执行、同一幂等键重试返回原快照及 `200`。不同幂等键生成新的快照，旧快照不修改。数据库迁移 V9 创建 `experiment_reports`，对 `(execution_id, generation_key)` 建唯一约束，并在生成时锁定执行行，避免并发重试重复插入。报告记录由服务端执行与审计数据构成，不接受客户端上传的“已验证指标”或结论。

未恢复、缺少审计证据、目标不一致或无正时长窗口时返回明确的 `409` 错误；错误实验/执行/报告 ID 返回 `404`。当前 API 仍无认证，只能在本机使用。

## 下一增量边界

下一步增加服务端只读 Prometheus 采集与样本充足性判断，并将各窗口的估算请求数、5xx、错误率和 P95 写入**新的**报告快照。采集前必须核验本机 Demo Target、容器与指标 job 的绑定；不可用或不匹配时不得将指标标记为可信。真实故障效果结论仍属于后续执行器与因果证据阶段。
