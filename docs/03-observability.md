# 可观测性：阶段 6 增量 1

当前已提供 Prometheus 格式的采集端点，但**尚未部署 Prometheus/Grafana，也没有实验前/中/后的历史快照或报告**。端点只是当前进程的指标出口；没有定时抓取，就无法在进程重启后保留历史数据。

## 指标定义

| 指标 | 来源 | 含义 |
| --- | --- | --- |
| `chaoslab.execution.operations` | ChaosLab 后端 | 实验启动、销毁、自动恢复、紧急恢复和紧急停止的请求次数 |
| `http.server.requests` | Spring Boot/Micrometer | 后端和 Demo 服务的 HTTP 请求次数与耗时；按状态码观察错误率与延迟 |
| JVM、CPU、内存等默认指标 | Spring Boot/Micrometer | 观察服务资源状态，不直接等同于业务稳态 |

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

当前 API 和指标端点都没有认证。仅绑定本机或保持在隔离网络内，不要暴露到公网。下一个增量将配置 Prometheus 定期采集、定义稳态基线与实验时间窗，再生成有证据的实验报告。
