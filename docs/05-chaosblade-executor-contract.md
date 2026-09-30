# ChaosBlade 执行器：命令白名单与恢复契约

状态：2026-09-30，阶段 7 的契约增量。当前生产代码仍只注册 `FakeChaosEngine`。本增量提供纯 Java 命令模型和恢复判定，不注册真实引擎、不运行进程，也不改变现有 Dry Run 的范围。

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

未来进程通道还需要固定工作目录、受控环境变量、独立 stdout/stderr 消费、输出上限、进程截止时间、取消与子进程处理。终止本地 CLI 进程不代表已移除故障；这些约束应通过无副作用的桩进程测试后再接入工具。

## 官方依据与待验证事项

本次只读核对了官方 CLI `v1.7.4` 的命令实现及 Docker 执行器源码。它们用于确定候选命令和生命周期语义，**不构成部署版本选择或兼容认证**：

- [CLI v1.7.4 create 实现](https://github.com/chaosblade-io/chaosblade/blob/v1.7.4/cli/cmd/create.go)：`--timeout` 的实现包含延迟调用 destroy 的本地子进程，不能作为独立于执行节点生存期的恢复保证。
- [CLI v1.7.4 destroy 实现](https://github.com/chaosblade-io/chaosblade/blob/v1.7.4/cli/cmd/destroy.go)：销毁按 UID 找回实验模型；本项目不开放强制删除记录的选项。
- [官方 Docker 场景模型](https://github.com/chaosblade-io/chaosblade-exec-docker/blob/master/exec/model.go)：Docker CPU 复用 CPU 场景并带容器 matcher；浮动分支仅用于研究。
- [官方 status 文档](https://chaosblade.io/en/docs/1.7.0/getting-started/chaosblade-tool-quick-start/cli-mode-user-guaid/blade-status/)：有独立的实验状态；记录不存在也可能是工具数据文件丢失。

后续需选择并锁定 Linux 工具包及摘要，在隔离执行环境核对帮助输出、UID 格式、权限、结果 JSON 与超时销毁行为，再实现实际适配器。当前代码没有任何开关可切换为真实注入。

## 验证

命令白名单测试覆盖非法参数、额外参数、重复键、尾随 JSON、非整数、超时长、未核验/变更容器及镜像；恢复测试覆盖危险 UID、UID 不匹配、数据丢失、节点/容器变更和通信失败。测试仅使用内存数据，不需要 Docker 或 ChaosBlade 二进制。

```powershell
.\backend\mvnw.cmd -f backend/pom.xml --batch-mode verify
```
