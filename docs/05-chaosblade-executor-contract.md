# ChaosBlade 执行器：命令白名单与恢复契约

状态：2026-10-02，阶段 7 的契约、受限通道、响应解析、创建事务拆分及独立快照存储增量。当前生产代码仍只注册 `FakeChaosEngine`。命令模型、恢复判定和解析器保持纯 Java；进程通道未注册为 Spring Bean、未接入引擎或 HTTP 路由，也不改变现有 Dry Run 的范围。测试仅使用合成响应、数据库和无故障注入的 Java 桩进程。

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

`destroy` 进程退出码为零或响应 `success=true`，不能替代后续状态核对。独立的 `BladeResponseDecoder` 已实现完整 JSON、类型、UID、状态值与响应大小校验，但尚未接入应用；未知字符串不能被映射为 `DESTROYED`。

## 严格响应解析（候选 v1.7.4 方言）

解析器是无副作用的纯 Java 类，不执行命令、不保存 UID、不自动重试，也不返回“实验已恢复”。测试响应按官方源码构造，并非真实工具的录制结果；最终部署版本仍需锁定并做兼容验收。

入口必须收到 `EXITED`、退出码 0、`cleanupComplete=true` 的完整进程结果，否则抛出 `TRANSPORT_UNCERTAIN`。只读取 stdout，不合并或从日志中提取 JSON；非空白 stderr、UTF-8 替换字符、总输出超过 64 KiB 均拒绝。JSON 限制深度为 16，拒绝重复键、尾随文档、未知顶层字段及隐式类型转换。顶层 `code` 必须是 int32 范围整数，`success` 必须是布尔值且与 `code=200` 一致，成功响应不能带非空 `error`。

| 方法 | 接受的成功结果 | 输出的含义 |
| --- | --- | --- |
| `decodeCreate` | `result` 为 16–64 位小写十六进制 UID 字符串 | 创建回执，仍需可靠持久化，不是恢复证据 |
| `decodeStatus` | `result` 为单个 Docker CPU 实验记录，含官方八个字符串字段；`Command=docker`、`SubCommand=cpu load` | UID 与精确状态 `Created/Success/Error/Destroyed`，必须再交恢复契约核对节点、目标及保存的 UID |
| `decodeDestroy` | `result` 为非空对象或非空白字符串，兼容模型/已销毁描述两种外形 | 仅 `REQUIRES_STATUS_CHECK`；不验证结果载荷的全部业务语义，也不从描述中猜 UID |

状态字段严格使用 `Uid`、`Command`、`SubCommand`、`Flag`、`Status`、`Error`、`CreateTime`、`UpdateTime`。后三类诊断/时间文本和 `Flag` 只核验字符串类型，不用于证明目标身份；目标身份必须由独立安全门重新取得。准备记录、列表、未知/大小写变化的状态均拒绝。解析得到不同 UID 时保留该值，让恢复契约判定人工处置，绝不替换成请求 UID。

完整的失败响应按数字错误码区分 `DATA_NOT_FOUND`（67002）与 `TOOL_REPORTED_FAILURE`，不匹配错误文本。非零退出码或异常通道优先作为传输不确定处理，不尝试挽救其中的失败 JSON。异常只含固定分类，不携带原始输出或 JSON 解析异常，防止将敏感诊断内容带到日志/API。

未来调用方必须显式处理这些分类：create 解析失败仍属于“创建结果不确定”，不能释放占用或盲目重试；status 的 `DATA_NOT_FOUND` 进入人工处置，其他不可用结果进入有预算的状态核对；destroy 回执或解析失败都不能确认恢复。创建不确定状态的应用层入口已加入，见下节；解析器与真实工具仍未接入调度流程。

方言依据：[v1.7.4 Response 定义及错误码](https://github.com/chaosblade-io/chaosblade-spec-go/blob/v1.7.4/spec/response.go)、[实验记录字段](https://github.com/chaosblade-io/chaosblade/blob/v1.7.4/data/experiment.go)、[status 实现](https://github.com/chaosblade-io/chaosblade/blob/v1.7.4/cli/cmd/status.go)及下文 create/destroy 实现。保守拒绝策略可能拒绝其他版本的合法响应，这种情况应保持结果不确定，不能放宽为默认成功。

## 接入前必须完成的执行流程

### 已完成的第一步：保留创建不确定记录

`ChaosEngine.create` 可以抛出固定内容的 `EngineCreateUncertainException`。应用服务捕获该信号后，在独立结果事务中将 `PREPARING` 更新为 `CREATE_UNCERTAIN`。此状态没有可信 UID、启动时间或结束时间，不伪造 `RUNNING` 或已恢复。普通异常目前仍沿用 Fake 阶段的 `FAILED` 行为；未来真实适配器必须正确归类不确定结果，不能依赖普通异常分支。

- `CREATE_UNCERTAIN` 计入目标互斥及全局并发额度；换幂等键或换同目标实验都不能绕过占用。同一幂等键返回原执行记录，不再次调用 create。
- 实验定义暂保持 `READY`，实际能否启动还必须通过执行记录的占用校验，不能单凭定义状态判断可用。
- 域模型不提供从此状态转为 `FAILED/RUNNING/DESTROYING/SUCCESS` 的自动转换；未获得可靠 UID 时拒绝普通销毁。自动到期恢复不处理该状态。
- 紧急停止将其纳入候选并返回 `MANUAL_INTERVENTION`，新增汇总字段 `manualInterventionCount`；不会调用引擎 destroy，也不算作 recovered。全局紧急停止审计标记恢复未完成，启动审计使用 `EXECUTION_CREATE_UNCERTAIN` 失败分类。这里“失败”描述操作未完成，不表示故障已清除。
- 现有 `status VARCHAR(32)` 可存储新枚举，无需修改旧迁移。集成测试使用独立 H2 数据库，不包围测试事务，确认服务提交后能从新事务读回；这不是真实 MySQL 或进程重启验收。

### 已完成的第二步：创建事务拆分

`start()` 使用 `NOT_SUPPORTED` 挂起调用方事务，通过 `TransactionTemplate(REQUIRES_NEW)` 完成两个独立事务：

1. 准入事务按原顺序取得全局/目标锁，核验安全门和占用，插入 `PREPARING` 并提交。只有本次新建记录的调用方继续执行；同一幂等键重放只返回已有状态。
2. 在没有数据库事务的上下文调用 create，不持有准入锁。其他请求可以立即读取已提交占用并被拒绝，而不是等待外部调用结束。
3. 结果事务保存 `RUNNING` 与实验定义状态，或明确的不确定/失败结果。更新保留乐观锁版本检查，不允许旧快照覆盖已变化记录。结果事务失败不会回滚第一步已经提交的记录，也不会重试 create。

意图写入或提交失败时不调用引擎。提交后、调用前或调用过程中中断，以及结果写入失败时，记录可能保留 `PREPARING`：这不证明故障尚未创建，不能自动重新执行或标记安全失败。该状态继续占用目标/全局额度，紧急停止返回 `MANUAL_INTERVENTION`，不调用 destroy；也不改变仍在执行中的创建记录。调用方必须先提交实验/目标配置，不能指望挂起事务中的未提交数据对 start 可见。

**仍未完成**：现有启动流程只写平台执行记录及占用；下节的 Blade 快照存储尚未接入该流程。还缺少工具/目标的执行前实际核验、原生 UID 的可靠执行节点日志、启动扫描、人工解除接口和跨节点协调；destroy 仍沿用 Fake 的单事务流程，紧急停止不是全局禁止新实验的闸门。模拟中断测试不等于真实 JVM 被杀或 MySQL 崩溃验收，仍禁止接入真实注入。不要通过直接改表或删除执行记录来假定故障恢复。

### 已完成的第三步：独立执行快照与 UID 存储（2026-10-02）

V11 创建 `blade_execution_snapshots`。`BladeExecutionSnapshot` 从白名单计划生成，保存平台 execution ID、Target ID、完整容器/镜像 ID、指标 job、CPU 百分比/时长、执行节点标识、状态目录标识、工具版本/SHA-256、记录时间和恢复截止时间。格式固定为 `DOCKER_CPU_V1`，命令使用固定路径、单核和同一白名单参数生成器重建，不存储可任意执行的 shell 文本。未知持久化格式拒绝读取；后续改变命令语义必须增加新格式，不能悄悄重新解释旧记录。

`JdbcBladeExecutionJournal` 仅提供内部存储操作，没有 HTTP 入口：

- `recordIntent` 在独立事务中只插入无 UID 的快照，要求存在已提交的 `PREPARING` 执行，且关联的目标、Docker 类型、CPU 场景和时长匹配。重复插入失败，不覆盖原计划。
- `recordUid` 在独立事务中锁定快照行，核对节点、状态目录及目标身份；首次保存 UID，重复相同 UID 幂等返回，不同 UID 被拒绝。同一节点和状态目录的一个 UID 不能归属两个平台执行。
- `findByExecutionId` 读回快照；没有 UID 时返回空恢复句柄，不从平台 UUID、目标名称或其他执行猜测 UID。

状态目录标识是可信部署提供的持久化状态存储身份，不是由请求指定的目录路径。版本、摘要及目标身份都只是保存的声明，**存储组件不会核验磁盘二进制、状态目录或实际容器**。未来适配器必须从同一已校验请求生成计划，重新核验身份，并在所有意图事务提交成功后才执行命令。

截止时间按快照记录时间加实验时长计算，不能在重启后重置。未来执行器必须在派发前检查截止时间并管理剩余预算，不能把读回快照当作允许重放 create 的指令。本增量没有派发器、过期扫描或自动销毁，故该时间目前只是持久化恢复依据。

该组件已注册为存储 Bean，但 Fake 引擎和当前启动服务都不调用它；也不改变平台执行状态或释放占用。创建成功而 UID 尚未写入的窗口仍需执行节点日志与核对流程解决。测试验证独立事务读回和并发互斥，不等于真实进程重启或真实 MySQL 验收。

### 后续必须完成

1. 在已实现的平台记录预提交基础上，补齐目标身份、所用版本/节点、命令快照和恢复截止时间的持久化，再允许真实外部命令。
2. 在专用 Linux 执行节点重新核验容器身份、靶场标签、状态目录和工具摘要，然后用参数列表启动进程。当前 Demo 的只读文件系统、权限和 Docker Desktop 执行方式尚未做真实工具兼容验收。
3. 执行节点先可靠记录 create 的原始结果和原生 UID，控制面保存恢复句柄后才能确认 `RUNNING`。不把平台 UUID 假定为 Blade UID，也不依赖尚未验证的自定义 `--uid` 重试语义。
4. create 超时、输出超限、失去连接、响应格式错误或保存结果失败时，记为“结果不确定”，保留目标占用并核对执行节点日志。没有 UID 时进入人工处置，不能盲目重试 create 或释放目标供新实验使用。明确的不确定异常现已保留占用，但普通异常仍沿用 Fake 失败分支；真实模式接入前必须补齐所有不确定路径、意图持久化与恢复扫描。
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

### 重启后人工触发的只读清单（2026-10-02）

`GET /api/v1/blade/recovery-inventory?limit=50` 直接读取持久化日志，不依赖进程内缓存。返回 `entries`、`checkedAt` 和 `nextCursor`；继续查询时将非空 `nextCursor` 作为 `after`。每页 1–100 条，默认 50，按执行 ID 主键排序；无下一页时游标为 null。扫描期间并发新增/绑定可能改变结果，不提供跨页一致性快照，后续巡检需从首页重新开始。

- `MANUAL_INTERVENTION / MISSING_UID`：不得猜 UID、重放 create 或按到期时间释放占用。
- `MANUAL_INTERVENTION / INVALID_SNAPSHOT`：未知格式或非法字段；该行保留提示，不阻断其他有效行。恢复期限与到期标志为 null，表示未知。数据库访问故障仍使请求失败，不伪装为空列表。
- `LIVE_VERIFICATION_REQUIRED / UNVERIFIED_LIVE_IDENTITY`：仅证明保存的快照结构完整且有 UID。尚需核验当前执行节点、状态目录、实际工具版本/摘要、容器/镜像身份，随后才可查询原生状态；不是安全销毁许可。

`overdue` 仅根据已保存的期限判断（等于期限也算到期），不重置计时，不证明已恢复。清单包含平台终态执行的快照，因为平台终态不能替代引擎侧证据；目前未存储恢复确认标记，因此不会自动移除条目。接口不返回工具原始输出、UID 或主机身份等完整快照，不调用 create/status/destroy，不更改执行状态或占用，也未接入启动监听器或定时恢复任务。Fake 不写入该日志，空列表不能作为全局安全证明。平台仍无认证，仅供隔离本地教学环境使用。

本次 Windows JDK 24 后端 `verify` 通过，按本轮生成的报告统计 274 项、零失败（不计目录内遗留的旧测试报告），其中新增 6 项测试。覆盖分页及终页、损坏记录隔离、缺失 UID、平台终态保留、期限边界、新建读取组件读回、数据库前后不变、HTTP 参数校验及拒绝写请求。H2 测试不等价于真实 MySQL、JVM 重启或真实故障恢复验收。

2026-10-02 快照存储增量：Windows JDK 24 下后端 `verify` 全量 269 项通过，包含 8 项新测试。覆盖 V11 迁移后的快照/句柄读回、调用方回滚不撤销独立意图提交、重复快照拒绝、缺失意图/身份不匹配、非 PREPARING 执行拒绝、UID 跨执行唯一约束、并发 UID 写入、未知格式和非法快照参数。使用 H2 MySQL 兼容模式；尚未对 V11 做真实 MySQL 或 JVM 重启验收。

2026-10-01 创建事务拆分：Windows JDK 24 下后端 `verify` 全量 261 项测试通过。新增 5 项事务集成测试，覆盖引擎调用时无活动事务、另一线程读到已提交占用、调用中重放不重复创建、模拟中断、结果写入失败、意图写入失败和外层事务回滚；并发准入测试改为验证引擎阻塞期间第二个请求立即被占用规则拒绝，另补 `PREPARING` 紧急停止人工处置测试。均为 H2 与模拟引擎验收，不是生产数据库或真实故障恢复认证。

创建不确定状态增量：Windows JDK 24 下后端 `verify` 全量 255 项测试通过。新增域状态约束、紧急停止无销毁调用及独立数据库集成测试，覆盖提交后读回、幂等重放、目标/全局占用保留和人工处置响应；未做真实故障或 JVM 崩溃恢复验收。

严格解析增量在 Windows JDK 24 下通过后端 `verify` 全量 251 项测试，其中 45 项解析测试覆盖创建 UID、四种状态、UID 不匹配、销毁回执、字段/结构错误、重复键、尾随文档、未知状态、异常通道、输出大小、嵌套深度和失败分类。以下 206 项及 Linux 验证记录属于此前进程通道增量。

命令白名单测试覆盖非法参数、额外参数、重复键、尾随 JSON、非整数、超时长、未核验/变更容器及镜像；恢复测试覆盖危险 UID、UID 不匹配、数据丢失、节点/容器变更和通信失败。

12 项进程测试使用当前 JDK 的 Java 桩程序，覆盖字面参数、环境/工作目录/stdin、非零退出码、双流洪泛、超时、启动前/运行中取消、中断、后代终止、孤儿进程、配置拒绝和启动失败。不需要安装 ChaosBlade，也不会注入真实故障。

本增量验收：Windows JDK 24 下后端 `verify` 共 206 项测试通过；另在带 `--init` 的 Linux Amazon Corretto 21 临时容器中，从源码重新编译并运行 `mvn --batch-mode -Dtest=BoundedProcessRunnerTests test`，12 项全部通过。Linux 验证使用只读源码/依赖缓存、独立临时构建目录和禁用网络的容器，没有挂载 Docker socket。这仅证明桩进程通道行为，不代表真实工具兼容验收。

```powershell
.\backend\mvnw.cmd -f backend/pom.xml --batch-mode verify
```
