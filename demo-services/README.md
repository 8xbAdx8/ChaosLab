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

需要保存指标历史并生成三阶段观察报告时，使用可选的 `observability` profile；它会在本机 `127.0.0.1:19090` 启动 Prometheus，抓取两个 Demo 服务。启动、验证与报告命令见[可观测性文档](../docs/03-observability.md)。普通靶场启动不包含 Prometheus。

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

服务使用固定的 `order-1/order-2` 和 `item-1/item-2` 内存数据，每次重建结果相同，不使用或删除 ChaosLab 平台数据库。要重建靶场可执行 `docker compose -f demo-services/compose.yml up -d --force-recreate --wait`；实验后执行 `docker compose -f demo-services/compose.yml down`。启用观测 profile 时，Prometheus 使用独立命名卷保存七天指标；普通 `down` 不删除该卷。

## 与平台的边界

如果要在 ChaosLab 平台中描述靶场，请先显式注册 `type=DOCKER_CONTAINER`、`environment=CHAOS_LAB` 的目标。当前注册仅保存目标元数据；FakeChaosEngine 不读取容器 ID，也不会对容器注入故障。不要将 ChaosLab 后端或其他容器注册为靶场目标。

容器带有 `com.chaoslab.role=demo-target` 等标签、资源上限、非 root 用户、只读根文件系统与独立网络。这些约束为未来真实引擎提供边界，但不能替代执行前的目标核验与恢复测试。

## 不启动 Docker 的测试

```powershell
./backend/mvnw.cmd -f demo-services/pom.xml verify
```

Linux/macOS 使用 `bash ./backend/mvnw -f demo-services/pom.xml verify`。
