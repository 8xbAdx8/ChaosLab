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

首次响应也从持久化后的快照读取，使数据库的时间精度和 JSON 数字表示在首次返回、后续 GET 与幂等重放中保持一致。

未恢复、缺少审计证据、目标不一致或无正时长窗口时返回明确的 `409` 错误；错误实验/执行/报告 ID 返回 `404`。当前 API 仍无认证，只能在本机使用。

## 本机 MySQL 端到端验收

`scripts/verify_report_e2e.py` 使用 Python 标准库、真实 MySQL 8.4 容器和当前仓库的 Demo Compose，验证平台报告的完整链路。需要运行中的 Docker Desktop Linux engine、Java 21+、Python 3，以及本机已有的 `mysql:8.4`、Demo 和观测服务镜像。先打包当前后端；如果 Demo 镜像尚未构建，先执行 `docker compose --profile observability -f demo-services/compose.yml build`，并准备 Compose 中的观测镜像。

在仓库根目录运行（Java 路径替换为本机实际路径）：

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-21'
.\backend\mvnw.cmd -f backend/pom.xml --batch-mode -DskipTests package
py -3 scripts/verify_report_e2e.py --java "$env:JAVA_HOME\bin\java.exe"
```

脚本启动默认监听 `127.0.0.1:18080` 的验收后端（可用 `--port` 修改），以及带随机密码、动态本机端口和 tmpfs 数据目录的专用 MySQL。现有 MySQL 服务和数据库不参与验收。Demo 服务在结束后继续运行；脚本结束时停止自己的后端，并核验唯一运行标签后删除自己的 MySQL 容器及临时数据。

验收覆盖：V10 迁移应用、Docker Target 安全门、持续订单流量、Fake 执行与恢复、提前生成的未采集快照、三个完整指标窗口、MySQL JSON 数组、报告幂等重放，以及后端重启后的快照读回。重启后将**验收后端**的指标查询地址指向一个不可用的本机端口，检查新报告降级为 `INSUFFICIENT_DATA`，而已有报告仍可读回和重放；不需要停止共享 Prometheus。

每次生成唯一的 `backend/target/report-acceptance/<run-id>/` 目录，保留 `evidence.json` 与后端日志，目录受 Git 忽略。脚本会创建目标、实验、执行和报告，但这些只存在于本次专用数据库；它只用于当前 Fake 引擎阶段。验收约需 2–4 分钟，失败以非零退出码结束，证据中记录失败原因。该检查需要实际容器与时间窗口，因此不加入默认单元测试任务。

### 验收记录：2026-09-29

在 Windows、JDK 24、Docker Desktop Linux engine、MySQL 8.4.11 和现有 Demo 镜像上，16 项端到端断言全部通过。流量生成器完成 229 次成功订单请求、0 次失败；三个窗口各有 6 个抓取样本，均为 `OBSERVED`，结论保持 `SIMULATED_ONLY`。后端完整 `verify` 的 165 项测试通过。

此次验收发现首次报告响应与数据库读回值有末位浮点和时间精度差异。持久化适配器现会在写入后解除新实体的托管状态，再从数据库读取同一快照作为响应；回归断言覆盖含纳秒生成时间的保存/读回一致性，实际 MySQL 验收覆盖整份响应一致性。最终通过的本机证据目录为 `backend/target/report-acceptance/e34cf1981f1041ba97e4f1af9669e75f/`。临时数据库已删除，日志与 JSON 证据保留在本机；该结果证明观察报告链路可运行，不证明真实故障效果或生产部署能力。

## 下一增量边界

真实故障效果结论仍属于后续执行器与因果证据阶段。接入真实引擎前，应先明确允许攻击的本机容器、命令范围及恢复保障。
