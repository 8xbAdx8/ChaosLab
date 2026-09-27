# 平台内实验报告：只读指标观察

平台可以为一次**已成功恢复**的执行生成并查询不可变报告快照。对于通过本机 Demo 容器身份与 Prometheus job 核验的 Docker Target，后端会只读查询三个指标窗口，保存估算请求数、5xx、错误率与 P95。它仍不是故障效果报告：Fake 引擎的 `SUCCESS` 不是故障注入证据，任何指标变化都不能单独证明因果。

## 证据与时间窗口

生成前要求执行记录属于 URL 中的实验、状态为 `SUCCESS`，并具有正时长的服务端开始/结束时间。还要求同一执行具有成功启动和成功销毁/恢复的审计事件；两者目标一致，且匹配实验目标，启动事件的场景代码与实验场景一致，时间顺序正确。报告保存执行、目标、场景、两条审计事件的 ID 与生成时间，以便回查。审计事件超过现有查询上限时拒绝生成，不静默截断。

设执行开始为 `S`、结束为 `E`、持续时间为 `D=E-S`：

| 阶段 | 窗口 | 指标状态 |
| --- | --- | --- |
| before | `[S-D, S)` | 视绑定与样本而定 |
| during | `[S, E)` | 视绑定与样本而定 |
| after | `[E, E+D)` | 视绑定与样本而定 |

`executionMode=SIMULATED` 只在执行器 ID 与当前 Fake 引擎的 `fake-{executionId}` 完全一致时使用；其他 ID 一律标为 `UNVERIFIED`，不推断其为真实故障。三段指标完整时，Fake 报告结论只能为 `SIMULATED_ONLY`，其他未核实执行只能为 `EXECUTION_UNVERIFIED`；任何窗口缺数据则为 `INSUFFICIENT_DATA`。执行模式、指标状态和结论状态是独立维度。

## 绑定与指标口径

只有启用、位于 `CHAOS_LAB` 环境、名称为 `order-service` 的 Docker Target 才尝试采集。后端复用 `CHAOSLAB_DEMO_BINDING_SCRIPT` 指向的本机核验脚本，要求唯一 Target 别名、当前 Compose 容器及镜像身份、健康状态、专用网络和唯一健康的 Prometheus `order-service` job 都符合预期；容器还必须在基线起点 `S-D` 之前已启动。未通过时 `bindingStatus=NOT_VERIFIED`、`metricsStatus=NOT_COLLECTED`，不会查询指标或保存容器身份。

恢复窗口结束并额外等待 5 秒后，后端向 `CHAOSLAB_REPORT_PROMETHEUS_URL` 发起只读 PromQL 查询；该地址只允许本机 HTTP 根地址，默认 `http://127.0.0.1:19090`。每段必须为 20–3600 秒，有足够的 5 秒抓取样本、`up=1`、订单路由请求及延迟直方图。数据不可用时窗口标为 `INSUFFICIENT_DATA`，不会把无流量当作 0% 错误率。请求数和 5xx 使用 `increase` 的外推估算，P95 使用直方图桶估算；Prometheus 范围采样边界是 `(起点,终点]`。三个窗口全部 `OBSERVED` 才会使报告整体 `metricsStatus=OBSERVED`。

报告是不可变快照。若在恢复窗口完成前生成，该快照保持 `NOT_COLLECTED`；等待完成后需用新的 `Idempotency-Key` 生成另一份快照，同一键重试不会重新采集。

即便三段完整，报告只陈述观察事实，不自动判断稳态偏离或恢复，也不声称故障因果。生成时的绑定核验不证明整个历史窗口内没有容器或 job 变更；历史容器启动时间检查只排除明显的容器重建。请保持三个窗口的请求负载可比。

## API 与持久化

```text
POST /api/v1/experiments/{experimentId}/executions/{executionId}/reports
Idempotency-Key: report-v1

GET /api/v1/experiments/{experimentId}/executions/{executionId}/reports/{reportId}
```

首次生成返回 `201` 和 `Location`；同一执行、同一幂等键重试返回原快照及 `200`。不同幂等键生成新的快照，旧快照不修改。数据库迁移 V9 创建 `experiment_reports`，V10 增加绑定状态、容器/镜像 ID 和三窗口 JSON；生成时锁定执行行，避免并发重试重复插入。报告记录由服务端执行、审计与只读指标构成，不接受客户端上传的“已验证指标”或结论。已存在的 V9 报告保持 `NOT_VERIFIED` 与未采集状态。

未恢复、缺少审计证据、目标不一致或无正时长窗口时返回明确的 `409` 错误；错误实验/执行/报告 ID 返回 `404`。当前 API 仍无认证，只能在本机使用。

## 下一增量边界

下一步需要对真实本机 Demo 运行一次端到端验收，并检查 MySQL 上的 V10 迁移与窗口持久化；真实故障效果结论仍属于后续执行器与因果证据阶段。
