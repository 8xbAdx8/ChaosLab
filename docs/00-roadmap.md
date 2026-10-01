# ChaosLab 学习与交付路线图

> 文档状态：持续更新；已完成阶段 0–5，阶段 6 进行中
> 初始调研：2026-08-21；最近更新：2026-09-27
> 当前实现：Spring Boot 后端、MySQL/Flyway、实验状态机、安全守卫、自动恢复、审计、FakeChaosEngine、隔离 Demo Services、可选 Prometheus 抓取和 Grafana 初始看板、三阶段观察报告脚本、平台内只读三阶段指标报告，以及接入 Dry Run/启动安全门/报告生成的本机 Demo 容器身份核验。尚无真实故障注入或因果结论。

## 1. 项目目标

ChaosLab 是一个教学型、可演示、可继续扩展的混沌工程实验平台。它不复制 ChaosBlade-Box，也不把 `blade` 命令散落在业务代码中，而是把平台能力与故障执行能力分开：

```text
ChaosLab（用户、权限、实验、安全、编排、审计、报告）
    ↓
ChaosEngine 适配层（统一执行协议）
    ↓
FakeChaosEngine / ChaosBladeEngine / KubernetesChaosEngine
```

最终演示闭环是：定义稳态与假设 → 注册测试目标 → 安全检查 → Dry Run → 注入故障 → 观察指标 → 自动恢复 → 生成报告 → 保留审计证据。

## 2. 初始本机环境基线（2026-08-21，历史记录）

以下结果是项目启动时的只读检查，不代表当前开发机状态。当前后端目标版本是 Java 21，并通过仓库内 Maven Wrapper 固定 Maven 3.9.16。

| 项目 | 当前结果 | 对项目的影响 | 后续动作 |
| --- | --- | --- | --- |
| 操作系统 | Windows 11，amd64；PowerShell 7.6.4 | 不把 Windows 原生宿主机作为 ChaosBlade 真实注入目标 | 使用 Docker Linux 容器、WSL2、Linux VM 或 kind |
| Java | Oracle JDK/Javac 17.0.18 | 不满足既定 Java 21 基线 | 创建后端前由学习者安装并在 IDEA 中选择 JDK 21 |
| Maven | 3.8.8，当前使用 JDK 17 | Maven 可用，但会跟随错误的 JDK | 升级 JDK 后复查 `mvn -version` |
| Docker / Compose | 命令不存在或未加入 PATH | 暂时不能启动 MySQL、靶场和监控栈 | Docker 阶段前安装 Docker Desktop 并验证 Linux containers |
| WSL2 | 查询返回 `E_ACCESSDENIED` | 无法根据本次检查确认是否已安装 | 后续由学习者在普通终端执行 `wsl --status` 与 `wsl -l -v` |
| Git | 2.54.0.windows.1 | 满足本地版本控制需要 | 已为本目录初始化 `main` 分支，不执行 push |
| 工作目录 | 初始为空 | 没有需要兼容的历史代码 | 从文档与最小后端骨架逐步建设 |

### 环境判断原则

- “命令不存在”只说明当前终端找不到程序，不擅自等同于“机器绝对没有安装”。
- WSL 的权限错误只说明本次检查失败，不擅自等同于“WSL 不存在”。
- 在真正创建 Maven 工程前，再从 Spring Initializr 和各依赖官方发布页核验稳定版本与 Java 21 兼容性，避免把今天的版本号长期写死在路线图里。
- 本项目不会自动修改 PATH、安装 JDK、安装 Docker 或启用 WSL；环境变更由学习者确认后亲自完成。

## 3. 学习方式与质量门槛

每个阶段都按以下教学闭环执行：

1. 为什么做。
2. 第一次出现的概念：概念 → 生活化例子 → 代码中的作用 → ChaosLab 中的位置。
3. 工业项目的常见设计与取舍。
4. 本阶段的最小修改范围。
5. 编码与重点代码讲解。
6. 自动验证与学习者在 IDEA/curl 中的亲自验证。
7. 常见错误和定位方法。
8. 检查 diff、测试、秘密信息后，创建一个独立且有意义的 commit。

所有阶段共同遵守：

- 真实故障只能针对显式注册的本地隔离靶场。
- 没有安全校验、最大时长和恢复路径，就不能执行真实故障。
- 每一阶段必须有明确验收标准；“项目能启动”不是功能完成。
- 不为展示设计模式而制造抽象；抽象必须解决隔离变化、安全或测试问题。
- 未经明确允许不执行 `git push`。

## 4. 分阶段 Roadmap

### 阶段 0：调研、架构和环境基线

目标：先理解要建设什么，以及工具、平台、Agent、Operator 的职责边界。

学习内容：

- 混沌工程、故障注入、稳态、爆炸半径、恢复。
- ChaosBlade CLI、ChaosBlade-Box、Box Agent、ChaosBlade Operator 的关系。
- 平台控制面与故障执行面的区别。
- 为什么先做模块化单体和适配器。
- Git 仓库、diff、commit 的最小工作流。

产物：

- `docs/00-roadmap.md`
- `docs/01-chaosblade-research.md`
- `docs/02-architecture.md`

验收：文档覆盖官方组件、实验模型、create/status/destroy 生命周期、最终架构、安全边界、完整阶段门槛；diff 中不包含密钥或本机隐私数据。

### 阶段 1：Java 21 + Spring Boot 最小后端

目标：建立一个可运行、可测试、可观测的后端骨架，但暂不引入业务数据库和真实故障。

学习内容：Spring Boot、依赖注入、Spring MVC、Controller、Service、配置/Profile、Actuator、测试金字塔的第一层。

实现范围：

- 使用官方稳定 Spring Boot 版本和 Java 21 创建 Maven 工程。
- 建立 `common`、`experiment`、`target`、`scenario`、`engine` 等顶层业务模块边界。
- 提供健康检查与最小 smoke test。
- 增加适合 Java/IDEA/Maven/环境变量的 `.gitignore`。

验收：IDEA 使用 JDK 21；`mvn test` 通过；启动后 `/actuator/health` 返回 `UP`。

建议 commit：`chore: initialize ChaosLab backend`

### 阶段 2：核心领域、数据库和 REST API

目标：先完成可靠的实验管理 CRUD，而不是执行故障。

学习内容：DTO、Entity、Repository、Service、Controller、Validation、JPA/Hibernate、事务、Flyway、REST 资源建模、统一异常处理。

实现范围：

- 设计 Target、FaultScenario、Experiment、ExperimentExecution、AuditLog。
- MySQL + Flyway 初始 migration；禁止依赖 `ddl-auto=create`。
- 目标注册、场景查询、实验创建和查询 API。
- 统一错误响应和业务错误码。

验收：数据库由空库迁移成功；Controller/Service/Repository 测试通过；无业务逻辑进入 Controller。

建议拆分 commits：领域模型、数据库 migration、REST API、异常处理分别提交。

### 阶段 3：状态机与 FakeChaosEngine

目标：不接触真实机器，也能跑通完整实验生命周期。

学习内容：状态机、Adapter Pattern、Dependency Inversion、Strategy、幂等性、事务边界、乐观锁。

实现范围：

- 状态：`CREATED → VALIDATED → READY → RUNNING → DESTROYING → SUCCESS`，以及 `FAILED`、`ABORTED`、`ROLLBACK_FAILED`。
- 用显式转换规则拒绝非法操作。
- `ChaosEngine` 接口与 `FakeChaosEngine` 实现。
- start、status、abort/destroy API；Fake 引擎返回确定性结果。

验收：重复 start 不创建第二次执行；SUCCESS 不能再次 start；异常路径可测试；业务层不知道 fake 的内部实现。

### 阶段 4：安全系统、自动恢复与并发控制

目标：把“默认安全”做成代码约束，而不是操作手册中的提醒。

学习内容：SafetyGuard、allowlist、blast radius、dry-run、kill switch、调度器、锁、崩溃恢复、补偿事务。

实现范围：

- Target Allowlist 与 production deny-by-default。
- 最大持续时间、并发上限、单目标互斥、Dry Run。
- 定时自动 destroy、Emergency Stop。
- 应用重启后的超时实验扫描与恢复任务。
- 所有危险操作写审计日志。

验收：生产标签目标必然失败；超时自动恢复；destroy 失败进入 `ROLLBACK_FAILED` 并告警；并发测试证明同一目标不会被重复攻击。

### 阶段 5：专用 Demo Services 靶场

目标：建立“可被破坏、可重建、可观察”的隔离业务链路，绝不攻击 ChaosLab 自身。

学习内容：微服务调用、超时、故障传播、Docker image/container/network、健康检查。

实现范围：

- 最小 `order-service → inventory-service` 链路。
- `/health`、`/orders/{id}`、`/slow`、`/error`。
- Docker Compose 隔离网络、资源限制和清晰标签。
- 可重复初始化/销毁的测试数据。

验收：正常链路返回 200；慢/错误接口表现可预测；删除并重建容器不丢失平台数据；目标必须显式注册。

### 阶段 6：可观测性与实验报告

目标：用指标回答“故障是否让稳态偏离、恢复后是否回归”。

学习内容：SLI/SLO/SLA、Counter/Gauge/Histogram、Micrometer、Prometheus、P95/P99、Grafana。

实现范围：

- ChaosLab 生命周期、失败和回滚指标。
- Demo 服务请求量、错误率、延迟、JVM/CPU/内存指标。
- Prometheus 抓取配置与初始 Grafana 看板。
- 实验前/中/后指标快照及 Experiment Report。

验收：Fake 实验也能留下生命周期指标；真实故障阶段可看到偏离与恢复；报告区分“观察事实”和“结论”。

### 阶段 7：ChaosBladeEngine（Linux / Docker）

2026-10-01 创建事务增量：已实现平台 `PREPARING` 占用预提交、事务外 create 与独立结果事务；中断/结果写入失败保留占用，重放不重复创建。`PREPARING` 纳入紧急停止人工处置提示。下一开发增量需补齐完整执行快照、恢复句柄及重启核对；此增量不启用真实注入，也没有完成销毁事务拆分。

目标：在安全门之后接入真实 ChaosBlade，只攻击本地 Linux 靶场。

学习内容：进程/PID、CPU load、内存压力、`tc` 网络延迟/丢包、命令注入、ProcessBuilder、超时与输出限制。

实现范围：

- 先固定一种低风险场景，再渐进增加 CPU、内存、网络、进程故障。
- 类型化 `CommandBuilder`；只允许场景枚举和白名单参数。
- 使用参数数组调用进程，不调用 shell 解释器，不接受原始命令字符串。
- 记录 ChaosBlade UID，支持 status/destroy，限制执行用户权限。
- 适配器集成测试使用隔离容器，默认 profile 仍使用 Fake。

验收：Dry Run 与实际命令模型一致；恶意参数被拒绝；实验到期必定尝试 destroy；ChaosLab 重启后能恢复遗留实验。

2026-09-30 契约、通道与解析增量：加入纯 Java 的单容器 CPU 命令白名单、UID 恢复句柄、恢复判定、独立受限进程通道及严格响应解析器。真实范围暂限定为 CPU 10%–40%、单核、1–30 秒；验证仅使用桩进程和合成响应，未注册真实引擎或接入应用。另已加入 `CREATE_UNCERTAIN` 的持久化、占用保留和紧急停止人工处置提示；仅处理引擎明确报告的不确定信号，调用前意图提交、执行节点日志、CLI 超时助手的生命周期兼容与重启恢复仍待实现，详见[执行器契约](05-chaosblade-executor-contract.md)。

### 阶段 8：稳定性机制对照实验

目标：用同一个故障证明超时、重试、熔断、限流、舱壁的效果与副作用。

学习内容：Timeout、Retry、Circuit Breaker、Rate Limit、Bulkhead、Resilience4j、重试风暴。

验收：每一种机制都有修改前/后基线、相同故障参数、指标对比和结论；不把“错误率降低”作为唯一成功标准。

### 阶段 9：认证、RBAC 和完整审计

目标：让危险操作具备身份、授权、追责和最小权限。

学习内容：Spring Security、JWT、RBAC、Filter、密码散列、审计事件、敏感信息脱敏。

实现范围：VIEWER、OPERATOR、ADMIN；目标管理、实验执行、Emergency Stop 分权；审计关联 experimentId、executionId、targetId、userId 和 sourceIp。

验收：越权测试通过；日志不含密码/JWT/数据库密钥；危险操作即使失败也留有审计事件。

### 阶段 10：Docker Compose 一键环境与前端

目标：一条命令启动 MySQL、Prometheus、Grafana、ChaosLab、Demo Services，随后再建立 React 控制台。

学习内容：Compose、volume、network、健康依赖、React/TypeScript/Vite、危险操作二次确认。

验收：全新环境按 README 可复现；前端流程必须经过 Safety Check → Dry Run → Confirm；不允许 UI 绕开后端安全策略。

### 阶段 11：Kubernetes 与 ChaosBlade Operator

目标：在本地 kind/minikube 中理解声明式混沌实验，不连接未知或生产集群。

学习顺序：Container → Pod → Deployment → Service → Namespace → Node → CRD → Controller → Operator。

实现范围：独立 Kubernetes 引擎适配器；namespace/label allowlist；Pod Kill、Pod Network Delay、Pod CPU Load；保存 CR 名称/UID；观察 Operator 状态与删除恢复。

验收：只能命中专用 namespace 中单个显式目标；CR 删除后完成恢复；无 kubeconfig 时平台安全失败而不是回退到其他目标。

### 阶段 12：工程化收尾与项目答辩

目标：把“能运行”提升为“能复现、能解释、能维护”。

产物：GitHub 级 README、架构/数据库/API/安全/可观测性/排障文档、ADR、OpenAPI、测试报告、演示截图、完整实验报告。

验收：全新机器可按文档运行；核心风险有测试；学习者能够独立新增一种场景并回答架构、安全、恢复、监控和 Kubernetes 相关问题。

## 5. 为什么第一阶段使用 FakeChaosEngine

`FakeChaosEngine` 不是“假的所以不重要”，而是一个教学和工程隔离工具。

生活化类比：建飞机驾驶舱时，先用飞行模拟器验证仪表、操作顺序和告警。如果直接把尚未验证的控制器接到真实发动机，一次状态错误就可能造成真实损害。

在 ChaosLab 中，它带来四个直接收益：

1. **分离问题**：先学习 Controller、Service、数据库、状态机和 API，不把 Linux 权限、工具安装、网络命令错误混进来。
2. **安全**：状态转换、自动恢复和幂等性尚未成熟时，不产生真实故障。
3. **可测试**：Fake 可以稳定地模拟成功、创建失败、查询失败、destroy 失败和超时，不依赖特权容器。
4. **证明抽象有效**：业务只依赖 `ChaosEngine` 契约；以后替换为 ChaosBlade，不需要重写实验业务。

进入真实 ChaosBlade 的硬门槛：安全守卫、最大时长、自动 destroy、kill switch、审计、单目标并发控制、隔离靶场、故障恢复测试全部通过。

## 6. 第一阶段将学会什么

完成阶段 0 与阶段 1 后，学习者应能解释并演示：

- 混沌工程不是随机破坏，而是验证稳态假设的受控实验。
- ChaosLab 控制面和 ChaosBlade 执行面的职责差异。
- 为什么 Windows 开发机需要 Linux 隔离执行环境。
- 为什么模块化单体适合当前学习阶段。
- Spring Boot 应用如何由 Controller、Service 和领域模块协作。
- 为什么通过接口注入 Fake 引擎能降低风险并提高可测试性。
- 如何在 IDEA 选择 JDK、运行测试、启动应用并检查 Actuator health。
- 如何阅读 Git diff、确认没有秘密信息并提交一个小而完整的 commit。

## 7. 当前验收与下一步

阶段 0–5 的实现可作为教学型 WIP 展示：目前使用 FakeChaosEngine，不执行真实故障；没有认证与 RBAC，不应部署到公网或生产环境。阶段 5 的两个服务在独立 Compose 网络中运行；正常调用、超时/错误传播与容器重建已验收。Target 注册保存元数据；对本机 Demo Docker Target，Dry Run 与启动时另行核验容器身份，失败则拒绝。此核验尚未经真实执行器验证，不能视为已实现故障注入。实际运行与测试步骤以仓库根目录 `README.md` 为准。

阶段 6 已加入平台操作计数与三项服务的 Prometheus 指标出口，并提供 Demo 服务的可选定期抓取、Grafana 初始看板与三阶段观察报告脚本；脚本可核验后端执行记录、精确审计事件及本机 Demo Target／容器／Prometheus job 的绑定。目标身份核验已接入 Docker Target 的 Dry Run、启动安全门及报告生成，见 `docs/03-observability.md`。平台内报告持久化生命周期证据，并可只读采集三阶段指标；绑定或样本不满足条件时明确标记未采集/样本不足。2026-09-29 已通过真实 MySQL 8.4.11 与本机 Demo 的 16 项端到端验收，覆盖三窗口采集、自动恢复、幂等快照、重启读回和指标源不可用时的降级，复现方式见 `docs/04-experiment-report.md`。2026-09-30 已加入真实执行器的纯命令、恢复契约、未接入应用的受限进程通道及严格响应解析，下一开发增量是持久化执行/恢复生命周期。真实执行器接入前，绝不把平台自身当作故障目标，也不把 Fake 模式报告当成真实故障结论。
