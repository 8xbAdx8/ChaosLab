# ChaosBlade 执行器：命令白名单与恢复契约

状态：2026-09-30，阶段 7 的契约与受限进程通道增量。当前生产代码仍只注册 `FakeChaosEngine`。命令模型和恢复判定保持纯 Java；新增进程通道未注册为 Spring Bean、未接入引擎或 HTTP 路由，也不改变现有 Dry Run 的范围。测试仅启动无故障注入的 Java 桩进程。

## 首个场景的范围

`DockerCpuCommandPlan` 只接收 `CPU_LOAD`，参数必须是只含 `percent` 的 JSON 对象。百分比为整数 10–40，执行时长为 1–30 秒，固定 `--cpu-count 1`。范围比 Fake 阶段的通用场景校验更窄；后续真实引擎接入时，Dry Run 必须使用同一计划生成器，不能先允许启动再由执行器拒绝。

计划使用不可修改的参数列表：

```text
/opt/chaosblade/blade
create docker cpu load
--container-id <核验得到的完整64位容器ID>
--cpu-percent <10到40的整数>
--cpu-count 1
--timeout <1到30秒>
```

这里只展示参数顺序，不是 shell 脚本。未知字段、重复键、额外 JSON 文档、浮点数/字符串形式的百分比、其他场景和超长参数均被拒绝。调用方不能指定二进制路径、shell、主机 PID、容器名称、镜像、网络接口、异步模式或额外 flags。

`from(request, freshlyVerifiedTarget)` 要求请求中的核验身份与执行前重新取得的身份完全一致，包括 Target ID、容器完整 ID、镜像 ID 和指标 job。工厂只比较两个身份快照，本身不调用 Docker，也不能证明调用方真的进行了重新核验。未来适配器必须从可信身份验证端口取得第二个快照，不能由 HTTP 客户端提供。

## UID 和执行节点的归属

`BladeRecoveryHandle` 绑定平台 execution ID、执行节点实例标识、核验后的目标身份和原生 Blade UID。UID 暂限定为 16–64 位小写十六进制，这是本项目的输入白名单，不声称兼容所有 ChaosBlade 版本。状态和销毁只生成：

```text
/opt/chaosblade/blade status <已保存UID>
/opt/chaosblade/blade destroy <已保存UID>
```

不按容器名称回退，不使用匹配式批量销毁或 `--force-remove`。执行节点标识必须对应同一份持久化 Blade 状态目录；仅在另一台节点安装相同二进制，不能代替原节点的恢复能力。版本、二进制摘要、状态目录身份、命令计划及操作日志都需要随真实执行记录持久化。

## 恢复判定

以下规则已由 `BladeRecoveryContract` 和测试固定，但尚未接入调度器：

| 可核对的事实 | 决策 |
| --- | --- |
| 保存的恢复句柄缺失、执行节点变化或容器/镜像身份变化 | `MANUAL_INTERVENTION`，保持恢复未确认 |
| 状态查询超时、通信失败或尚无有效状态 | `RETRY_STATUS`，在重试预算内继续核对 |
| UID 不匹配，或工具返回记录不存在 | `MANUAL_INTERVENTION`，不能视作已恢复 |
| 同一 UID 为 `Created`、`Success` 或 `Error` | `DESTROY_REQUIRED`，错误状态也可能留有故障效果 |
| 同一执行节点、目标身份和 UID，查询状态为 `Destroyed` | `CONFIRMED_RECOVERED`，仅确认引擎侧恢复 |

`destroy` 进程退出码为零或响应 `success=true`，不能替代后续状态核对。CLI 结果解析器还须校验完整 JSON、类型、UID、状态值与响应大小；未知字符串不能被映射为 `DESTROYED`。本增量尚未实现该解析器。

## 接入前必须完成的执行流程

1. 持久化执行意图、目标身份、所用版本/节点和恢复截止时间，再启动外部命令。当前 `startNew()` 的同一数据库事务包围引擎调用，不能直接承载真实故障的崩溃恢复语义。
2. 在专用 Linux 执行节点重新核验容器身份、靶场标签、状态目录和工具摘要，然后用参数列表启动进程。当前 Demo 的只读文件系统、权限和 Docker Desktop 执行方式尚未做真实工具兼容验收。
3. 执行节点先可靠记录 create 的原始结果和原生 UID，控制面保存恢复句柄后才能确认 `RUNNING`。不把平台 UUID 假定为 Blade UID，也不依赖尚未验证的自定义 `--uid` 重试语义。
4. create 超时、输出超限、失去连接、响应格式错误或保存结果失败时，记为“结果不确定”，保留目标占用并核对执行节点日志。没有 UID 时进入人工处置，不能盲目重试 create 或释放目标供新实验使用。当前 Fake 流程把 create 异常归为失败，真实模式接入前需新增持久化的中间状态和恢复扫描。
5. 截止时间、手动销毁和紧急停止都走同一恢复流程。保存销毁意图，调用同一节点上的 `destroy UID`，再查询状态。重试要有次数、间隔和总时限；耗尽后保持恢复失败状态，保留占用并提供可核对的人工处置信息。
6. 控制面重启后扫描创建中、结果不确定、运行中和销毁中的记录。该流程需要独立于 Web 请求事务，且执行节点的状态目录必须存活。恢复完成与业务指标回归分别记录。

## 受限进程通道（尚未接入应用）

`BladeProcessChannel` 仅暴露接收命令计划/恢复句柄的 `create`、`status`、`destroy` 方法，不提供任意命令入口。内部 `BoundedProcessRunner` 是包级实现，使用 `ProcessBuilder` 参数列表，不调用 shell。生产通道固定二进制 `/opt/chaosblade/blade`、5 秒命令等待预算和 stdout/stderr 合计 64 KiB 原始字节预算。

- 二进制与工作目录必须是绝对路径，解析真实路径并检查文件类型；这不是版本/摘要认证，部署仍需禁止非可信用户替换文件和目录。
- 清空继承环境，仅允许显式传入 `PATH`、`LANG`、`LC_ALL`、`TMPDIR`、`TMP`、`TEMP`、`SystemRoot`；这些值来自可信部署配置，不接受请求参数。
- 关闭 stdin，两个虚拟线程分别读取 stdout/stderr，合计保留原始字节不超过预算。超限立即停止等待并进入清理；异常时输出可能不完整，UTF-8 截断可能产生替换字符，不能用于确认成功。
- 取消标志和线程中断会进入清理，并保留调用线程的中断标志。期限使用单调时钟；启动调用本身受操作系统影响，不保证整个调用在严格的 5 秒内返回，另有最多约 2 秒的进程清理预算。
- 每轮记录可观察到的后代进程。父进程退出但已观察到的后代仍存活时，返回 `DESCENDANTS_REMAINED`，不返回正常结束，并尝试强制终止这些进程。

结果区分 `EXITED`、`TIMED_OUT`、`CANCELLED`、`OUTPUT_LIMIT`、`START_FAILED`、`IO_FAILED` 和 `DESCENDANTS_REMAINED`，保留退出码及两条输出流。`EXITED` 也可能具有非零退出码；即使退出码为零，也必须经过后续响应解析和恢复核验。

`cleanupComplete` **仅表示本地根进程及已观察到的后代已停止**，不表示故障恢复。后代枚举存在竞争窗口，无法保证捕获快速派生后脱离/重挂父节点的进程；实际 Linux 执行节点仍需 cgroup/进程监督等操作系统级隔离及可靠回收机制。不得将此通道作为恶意程序的安全沙箱。

尤其注意：候选 CLI 的 `--timeout` 会生成延迟销毁子进程，而本通道会清理观察到的残留子进程。两者的生命周期策略尚未兼容验收，可能终止自动销毁助手；**不能直接接入真实 create**，必须先设计独立恢复监督、持久化恢复责任和子进程归属策略。终止本地 CLI 永远不能代替 `destroy` 后的状态核对。

## 官方依据与待验证事项

本次只读核对了官方 CLI `v1.7.4` 的命令实现及 Docker 执行器源码。它们用于确定候选命令和生命周期语义，**不构成部署版本选择或兼容认证**：

- [CLI v1.7.4 create 实现](https://github.com/chaosblade-io/chaosblade/blob/v1.7.4/cli/cmd/create.go)：`--timeout` 的实现包含延迟调用 destroy 的本地子进程，不能作为独立于执行节点生存期的恢复保证。
- [CLI v1.7.4 destroy 实现](https://github.com/chaosblade-io/chaosblade/blob/v1.7.4/cli/cmd/destroy.go)：销毁按 UID 找回实验模型；本项目不开放强制删除记录的选项。
- [官方 Docker 场景模型](https://github.com/chaosblade-io/chaosblade-exec-docker/blob/master/exec/model.go)：Docker CPU 复用 CPU 场景并带容器 matcher；浮动分支仅用于研究。
- [官方 status 文档](https://chaosblade.io/en/docs/1.7.0/getting-started/chaosblade-tool-quick-start/cli-mode-user-guaid/blade-status/)：有独立的实验状态；记录不存在也可能是工具数据文件丢失。

后续需选择并锁定 Linux 工具包及摘要，在隔离执行环境核对帮助输出、UID 格式、权限、结果 JSON 与超时销毁行为，再实现实际适配器。当前代码没有任何开关可切换为真实注入。

## 验证

命令白名单测试覆盖非法参数、额外参数、重复键、尾随 JSON、非整数、超时长、未核验/变更容器及镜像；恢复测试覆盖危险 UID、UID 不匹配、数据丢失、节点/容器变更和通信失败。

12 项进程测试使用当前 JDK 的 Java 桩程序，覆盖字面参数、环境/工作目录/stdin、非零退出码、双流洪泛、超时、启动前/运行中取消、中断、后代终止、孤儿进程、配置拒绝和启动失败。不需要安装 ChaosBlade，也不会注入真实故障。

本增量验收：Windows JDK 24 下后端 `verify` 共 206 项测试通过；另在带 `--init` 的 Linux Amazon Corretto 21 临时容器中，从源码重新编译并运行 `mvn --batch-mode -Dtest=BoundedProcessRunnerTests test`，12 项全部通过。Linux 验证使用只读源码/依赖缓存、独立临时构建目录和禁用网络的容器，没有挂载 Docker socket。这仅证明桩进程通道行为，不代表真实工具兼容验收。

```powershell
.\backend\mvnw.cmd -f backend/pom.xml --batch-mode verify
```
