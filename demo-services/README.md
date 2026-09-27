# 隔离 Demo Services 靶场

阶段 5 的靶场由两个独立的 Spring Boot 服务组成：`order-service → inventory-service`。它们与 ChaosLab 后端不在同一个 Compose 项目或网络中；目前只用于观察正常、慢响应和错误传播，**没有接入真实故障执行器**。

## 启动与检查

需要已启动的 Docker Desktop（Linux containers）。从仓库根目录执行：

```powershell
docker compose -f demo-services/compose.yml up --build -d --wait
docker compose -f demo-services/compose.yml ps
```

首次构建会下载 Java 21 镜像、Maven 和项目依赖。只有 `order-service` 的 18081 端口发布到本机 `127.0.0.1`；库存服务仅可由同一 Demo 网络内的订单服务访问。检查预期结果：

两服务都提供 `/actuator/prometheus` 指标出口；指标定义与安全注意事项见[可观测性文档](../docs/03-observability.md)。

需要保存指标历史、查看看板并生成三阶段观察报告时，使用可选的 `observability` profile；它会在本机 `127.0.0.1:19090` 启动 Prometheus，并在 `127.0.0.1:13000` 启动只读 Grafana。看板预置了订单请求量、5xx 错误率和 P95；Grafana 放在单独的观测网络，不进入三容器靶场网络。启动、验证与报告命令见[可观测性文档](../docs/03-observability.md)。普通靶场启动不包含 Prometheus 或 Grafana。

| 请求 | 预期状态 | 说明 |
| --- | --- | --- |
| `GET http://127.0.0.1:18081/health` | 200 | 订单服务自身存活 |
| `GET http://127.0.0.1:18081/orders/order-1` | 200 | 调用库存后返回 `AVAILABLE` |
| `GET http://127.0.0.1:18081/orders/order-2` | 200 | 固定库存为 0，返回 `OUT_OF_STOCK` |
| `GET http://127.0.0.1:18081/orders/missing` | 404 | 未知订单 |
| `GET http://127.0.0.1:18081/slow` | 504 | 库存延迟 1200 毫秒，订单读超时 600 毫秒 |
| `GET http://127.0.0.1:18081/error` | 502 | 库存返回 503，订单映射为上游错误 |

PowerShell 中可使用 `Invoke-WebRequest -SkipHttpErrorCheck` 查看非 2xx 状态码。例如：

```powershell
(Invoke-WebRequest 'http://127.0.0.1:18081/orders/order-1').Content
(Invoke-WebRequest 'http://127.0.0.1:18081/slow' -SkipHttpErrorCheck).StatusCode
```

服务使用固定的 `order-1/order-2` 和 `item-1/item-2` 内存数据，每次重建结果相同，不使用或删除 ChaosLab 平台数据库。要重建靶场可执行 `docker compose -f demo-services/compose.yml up -d --force-recreate --wait`；实验后执行 `docker compose -f demo-services/compose.yml down`，启用观测 profile 时应在 `down` 中也加上 `--profile observability`。Prometheus 使用独立命名卷保存七天指标；普通 `down` 不删除该卷。Grafana 状态是临时的，看板和数据源由仓库文件预置，每次启动自动恢复。

## 与平台的边界

如果要在 ChaosLab 平台中描述靶场，请显式注册唯一的 `name=order-service`、`type=DOCKER_CONTAINER`、`environment=CHAOS_LAB` 目标。Dry Run 与启动都会核验本机 Docker context、当前 Compose 项目中的订单容器及镜像、独立网络和 Prometheus 抓取目标；验证失败时拒绝启动，且不调用引擎。完整容器 ID 会传入引擎请求契约，但当前 `FakeChaosEngine` 仅模拟执行，不会对容器注入故障。不要将 ChaosLab 后端或其他容器注册为靶场目标。

核验需先启动 `observability` profile，并在启动后端前设置校验脚本的绝对路径。本机需有 Python 3、Docker CLI 和可访问的 `127.0.0.1:19090` Prometheus。PowerShell 示例（从仓库根目录执行）：

```powershell
docker compose --profile observability -f demo-services/compose.yml up -d --wait
$env:CHAOSLAB_DEMO_BINDING_SCRIPT = (Resolve-Path demo-services/binding.py).Path
```

未配置脚本、靶场重建、指标抓取不健康、别名不唯一或容器身份变化时，Docker Target 的安全检查将拒绝通过；普通 `JAVA_APPLICATION` 教学目标在 Fake 模式下仍可使用。这里的验证不构成真实注入能力，也不能消除验证与未来执行命令之间的容器状态变化风险；真实执行器仍须只使用已验证的完整容器 ID，并在注入前再次确认身份。

容器带有 `com.chaoslab.role=demo-target` 等标签、资源上限、非 root 用户、只读根文件系统与独立网络。这些约束为未来真实引擎提供边界，但不能替代执行前的目标核验与恢复测试。

## 不启动 Docker 的测试

```powershell
./backend/mvnw.cmd -f demo-services/pom.xml verify
```

Linux/macOS 使用 `bash ./backend/mvnw -f demo-services/pom.xml verify`。
