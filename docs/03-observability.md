# 可观测性：阶段 6 增量 3

后端与 Demo 服务提供 Prometheus 指标出口；可选的本地 Prometheus 定期抓取两个 Demo 服务，并持久保存 7 天。`demo-services/report.py` 可从后端核验执行与审计事件，再查询三段相等时长的指标窗口，生成 Markdown 或 JSON 报告文件。**没有真实故障注入、目标到指标来源的可信绑定、Grafana 看板或平台内持久化报告。**

## 指标定义

| 指标 | 来源 | 含义 |
| --- | --- | --- |
| `chaoslab.execution.operations` | ChaosLab 后端 | 实验启动、销毁、自动恢复、紧急恢复和紧急停止的请求次数 |
| `http.server.requests` | Spring Boot/Micrometer | 后端和 Demo 服务的 HTTP 请求次数与耗时；按状态码观察错误率与延迟 |
| JVM、CPU、内存等默认指标 | Spring Boot/Micrometer | 观察服务资源状态，不直接等同于业务稳态 |

订单服务为 `http.server.requests` 启用直方图桶，用于估算 P95。报告只统计订单服务 `/orders/{orderId}` 路由的 5xx 错误率与 P95，不把 `/health`、Actuator 或演示用的 `/slow`、`/error` 混入稳态口径。

自定义计数器只使用两个有限取值的标签：

- `operation`：`start_experiment`、`destroy_experiment`、`automatic_recovery`、`emergency_recovery`、`emergency_stop`。
- `result`：`success`、`replayed`、`rejected`、`failed`。

`replayed` 表示幂等键命中已有执行，不是新建成功。其他值按操作返回或异常分类；这统计的是**操作请求**，不是当前正在运行的实验数。不要把 experimentId、executionId、targetId、异常消息或用户输入放进指标标签，以免时间序列数量无界增长。执行明细与失败原因仍以审计日志为准。

## 本机查看

后端启动后访问 `http://127.0.0.1:8080/actuator/prometheus`。Demo 靶场启动后访问订单服务的 `http://127.0.0.1:18081/actuator/prometheus`；库存服务不对宿主机发布端口，可在容器内检查：

```powershell
docker compose -f demo-services/compose.yml exec -T inventory-service curl -fsS http://127.0.0.1:8080/actuator/prometheus
```

先执行一次实验操作，再在后端端点搜索 `chaoslab_execution_operations_total`；未发生过的标签组合可能不会出现。Demo 服务可先请求 `/orders/order-1`、`/slow` 和 `/error`，再观察 HTTP 指标。HTTP 指标会包含状态码与耗时；仅凭 `/health` 为 `UP` 不能证明订单链路正常。

## 启动历史采集

```powershell
docker compose --profile observability -f demo-services/compose.yml up --build -d --wait
Invoke-RestMethod 'http://127.0.0.1:19090/api/v1/query?query=up'
```

预期 `order-service` 和 `inventory-service` 两个 job 的 `up` 均为 `1`。Prometheus 只监听宿主机 `127.0.0.1:19090`，其数据保存在 Compose 命名卷；普通 `docker compose --profile observability -f demo-services/compose.yml down` 不删除历史数据。后端仍在宿主机单独运行，**本配置没有抓取后端生命周期计数器**；其端点可直接查看，但不能从本报告反推生命周期历史。

## 三阶段观察报告

报告脚本需要本机 Python 3，不需要第三方包。在开始实验前先保持稳定的订单请求流至少一个完整窗口，期间和结束后继续按相近速率发请求。执行开始与结束之间至少 20 秒，建议至少 30 秒；恢复结束后，再等待同等时长和数次抓取。推荐从本机后端读取 `SUCCESS` 执行记录与精确审计事件，再生成报告：

```powershell
py -3 demo-services/report.py --backend http://127.0.0.1:8080 --experiment-id <实验UUID> --execution-id <执行UUID> --output .\chaoslab-report.md
```

后端接口 `GET /api/v1/audit-logs/by-execution/{experimentId}/{executionId}` 只返回同一次执行的事件；超过 200 条时明确报错，不静默截断。脚本要求匹配的执行状态为 `SUCCESS`、具备服务端 `startedAt/finishedAt`、存在成功启动及成功销毁/恢复的审计事件，并核对两个事件的 Target ID。保存报告时使用新文件创建，不覆盖已有文件；控制台输出该文件的 SHA-256 摘要。可加 `--format json` 保存机器可读证据。后端或审计证据不可用时，核验模式失败关闭。

需要独立演示指标计算时，仍可手工指定 `--started-at` 与 `--finished-at`（带时区的 ISO-8601，可包含小数秒），但输出标记为 `manual_unverified`，且不能使用 `--output` 保存为核验报告。

报告用执行时长定义三个不重叠的窗口：`before=[开始-时长,开始)`、`during=[开始,结束)`、`after=[结束,结束+时长)`；Prometheus 范围查询本身采用 `(窗口起点,窗口终点]` 的采样边界。程序要求每段有足够抓取样本、目标可用、订单请求和直方图数据，否则标记为“样本不足”，不输出三段差异结论。请求数是 PromQL `increase` 的外推估算，P95 是直方图估算，均不是逐请求精确日志。可加 `--format json` 获取机器可读结果。

报告分为“生命周期证据”“观察事实”和“比较与结论”：审计 ID 可用于回查平台记录，但差异仍只说明时间上的相关变化，不能证明故障导致变化。当前 Target 注册只是元数据，无法证明该执行作用于被采集的 `order-service`，报告始终标记 `target_metric_binding=unverified`。FakeChaosEngine 不会影响 Demo 服务；其报告通常不应出现真实故障偏离。若要比较，请保持三个窗口的负载、目标与路由口径一致。验证脚本：`py -3 -m unittest discover -s demo-services -p test_report.py`。

当前 API 和指标端点都没有认证。仅绑定本机或保持在隔离网络内，不要暴露到公网。下一增量要解决目标到容器及指标 job 的可信绑定，之后才能形成可信的正式故障实验结论；Grafana 看板仍待实现。
