# M1 工具兼容性审查

日期：2026-10-04。Phase 1 内容保留；下面追加 Phase 2A 的现场只读核验与无害实验。未执行真正的 create/status/destroy，未修改 api3 或运行代码。

| 项目 | 已知证据 | 当前判定 |
| --- | --- | --- |
| 候选 | v1.8.1 基础 + 本地 api3，Development | 不是官方 api3 release |
| CLI 摘要 | c0c987bbd0aa9d158e90ab48f737680fe1b7eefdbf6c9e74744444c49c847e96 | 10月3日传输核对历史证据；执行前再核验 |
| nsexec 摘要 | 693219257100421d3c2321c2b1bfb3d285d1b6eed1fa82feabd033797a51e793 | 同上；还需记录其他配套文件 |
| CLI 大小 | 本轮本地文件 74,921,322 字节 | 超过 Java 探针 64 MiB，直接接线必拒绝；改有界限额或评估可重复瘦身，不能去掉限额 |
| 工具路径 | 历史独立候选目录，未安装到 Java 固定 /opt 路径 | 部署与 Java 核验/调用需同一配置；不公开本机私有路径 |
| 方言 | 历史 help 为 create cri cpu load，支持 CPU 参数和 timeout | Java 仍 create docker；需修改并测试 |
| Docker | 历史 VM 29.8.2，最低 API 1.40；旧官方固定1.24冲突 | api 协商只读探针曾成功，不等于实际故障兼容 |
| UID、status/destroy JSON | Java 使用旧方言合成响应 | 真实响应未采集，未知；不能用 help 成功代替 |
| 状态目录 | Java存身份标识，实际候选存储绑定未完成 | 核对源码与只读现场，再固定；不对未知 UID 查询 |
| 权限 | sudo需用户本地输入；候选需要 Docker/namespace/cgroup 能力 | 最小特权本地调用方案待定，不授予整个后端 root |
| 后代生命周期 | Java会清理存活后代；候选创建工作进程/延迟恢复进程 | 必须先无害兼容验证，不能简单关闭清理 |
| 隔离与恢复 | 历史 sandbox 限额、无网络、冷快照、空闲定时停止通过 | 非真实恢复认证；执行前新鲜检查 |

## api3 是否必须继续修改

目前证据不足以要求 api4。先冻结源码，解决 Java 契约不匹配和明确监督范围。若无害实验复现工具缺陷，再分别说明不改的后果、更简单替代和补丁维护责任。
systemd scope 不是自动答案：候选会迁移 helper 到目标 cgroup，仅查看调用 scope 为空可能漏掉工作进程。必须同时考虑目标 cgroup 和恢复助手归属。

## 现场核验待填

执行节点/状态目录可信标识、候选及资源清单摘要、文件权限、工作目录、完整目标身份、精确 argv、CPU 参数、UID 格式、status/destroy 语义、限时与紧急停止策略。均不能从历史记录自动标记 PASS。

## Phase 2A：实验及源码结论（2026-10-04）

### A. 第一优先级无害实验

新增测试 `BoundedProcessRunnerTests.legalLongLivedHelperConflictsWithCurrentRunnerContract`，复用 ProcessFixture：父 JVM 启动休眠60秒的子 JVM，子输出重定向 DISCARD，父等待500ms后正常退出。500ms用于让20ms轮询有机会观察，不模拟所有竞态。没有调用 Blade、Docker API、namespace 或 cgroup。

| 实测项目 | Windows JDK24 | 隔离 Linux Corretto21 |
| --- | --- | --- |
| 父进程退出码 | 0 | 0 |
| outcome | DESCENDANTS_REMAINED | DESCENDANTS_REMAINED |
| helperAlive | false | false |
| cleanupComplete | true | true |
| 测试范围 | 新增定向用例1项 | 整个 BoundedProcessRunnerTests，13项 |

两次 Maven 均退出0。Linux 使用 --init、禁用网络、只读源码/依赖挂载，复制到容器临时目录编译；未挂载 Docker socket。helper 被既有 Runner 清理，PID存活断言通过。

结论：它没有违反自身“调用退出后不得有已观察后代”的契约，但这个契约对合法长期 helper 是语义误判。不是 stderr/stdout 管道被继承导致的阻塞。`cleanupComplete=true` 只表示已观察进程终止，并不表示实验恢复。

源码定位：`BoundedProcessRunner.run` 的父退出+后代存活分支及 finally→terminate；terminate 无条件强制终止已观察的存活后代。快速派生并重挂父节点可能未被观察，所以真实 CLI 不保证每次都返回同一 outcome；这种偶然漏检不是兼容方案。

### B. 真实源码生命周期（静态结论，不是实际故障进程树）

本地 CLI 基线 e8e0d3adc90c414d9dc9a04a13f53ebadfbda8dc + 已保存 api3 补丁；exec-cri 基线 fc16e67d2ca469a37c9ec98878fe0f618a0712d9 + api3；依赖 spec-go v1.8.0、exec-os v1.8.1。以下路径相对这些源码根，行号以本轮本地版本为准：

1. exec-os `exec/cpu/cpu.go:75` CPU ActionProcessHang=true；`:133` 动作规范名 fullload；`:278–330` 单核路径启动 Go burn goroutine 并持续循环，不是一次性命令。不能看到 ActionPrograms=chaos_burncpu 就猜测一定独立启动这个文件。
2. exec-cri `exec/executor_common_linux.go:130,159–274`：create 的 hang 分支启动 `<CLI目录>/bin/nsexec -s -t PID -p -n -- <CLI目录>/bin/chaos_os ...`；Setpgid=true，AddProc 将 nsexec 放入目标 cgroup，复核身份后发送 SIGCONT；成功 handedOff=true，返回 helper PID而不 Wait。这就是需要在 CLI 返回后继续运行的路径。
3. CLI `nsexec.c:117–141` 是修补后的暂停握手；`:160–185` 按参数 setns；`:192` fork，子进程`:259` execvp，父`:268` waitpid。外层传 -p/-n，因此进入网络namespace，PID namespace 对后续子进程生效；外层没有 -m，不能宣称外层一开始进入目标mount namespace。子进程继承目标cgroup。后续 chaos_os 的 ns_pid/ns_mnt 参数用于其 NSExecChannel 操作，不可与外层实际参数混为一谈。
4. CLI `cli/cmd/create.go:197–224` 检查挂起型工作进程存在、更新记录并把 Result 改成 UID返回；`:251–279` PostRun启动延迟恢复。
5. `cli/cmd/recovery_command.go:22–39`：CLI启动短命 /bin/sh，再后台启动 sleep→确切候选destroy UID 的shell；该后台链初始属于 CLI 后代，之后可能重挂父进程，输出重定向 /dev/null。它没有被 exec-cri 的 AddProc移入目标cgroup。若依赖 --timeout，这条链必须活到destroy被调用；内置计时只是尽力而为。
6. create.go 只对 scope=container/pod 额外加60秒，CRI不应机械套用该分支；最终实际时序仍待真实实验。计时在PostRun开始，10秒参数不等于从平台意图提交起恰好10秒的硬上限。

因此当前 Runner 可能同时破坏两类合法后代：目标cgroup内的 nsexec/chaos_os 工作链，以及目标cgroup外的延迟恢复链。只是取消 `--timeout` 不能解决第一类冲突。只把 CLI 放进 systemd scope也不能假定所有负载仍在原scope。

**是否修改 Runner：**若继续通过现有直接派发路线支持 create，必须调整有界生命周期策略（区分前台查询和已受控移交的负载），不能全局关闭异常清理。替代方案是独立有界监督入口，但本轮不引入。status/destroy 的既有严格前台语义应保留。必须先验证受控移交和失败兜底，再允许真实create。

**是否修改 api3：**本轮没有复现候选自身新bug；不要求修改，也没有修改。此次明确复现的是 Java 通道契约冲突。

### C. CLI 与配套文件/状态目录

现场仅执行 file、sha256sum、ls、version 和 `create cri cpu load --help`（Cobra帮助，不运行create handler）。三项ELF均x86-64静态链接；CLI为Development/api3，构建时间2026-10-03 14:18:13 UTC。

- CLI SHA256：`c0c987bbd0aa9d158e90ab48f737680fe1b7eefdbf6c9e74744444c49c847e96`。
- bin/nsexec SHA256：`693219257100421d3c2321c2b1bfb3d285d1b6eed1fa82feabd033797a51e793`。
- bin/chaos_os SHA256：`dee72446e32411f6a0d7a29c71b0ba0cc4dabbab1fcaba212c6c87d0ea965cfb`。
- 现场独立候选目录已有 chaosblade.dat（49,152字节，用户拥有）。未读取或修改实验记录；内容、真实UID、数据库实际打开路径为 UNKNOWN。

spec-go `util/util.go:57–100` GetProgramPath按可执行文件路径计算根目录，bin/yaml/lib相对于它，而非任意工作目录。CLI `cli/cmd/exp.go:297` 使用 yaml/chaosblade-cri-spec-1.8.1.yaml；同函数还加载相关 JVM spec。只改 CLI路径、不带配套目录不能视作完整部署。

CLI `data/source.go:34,77–113` 默认SQLite路径为 GetProgramPath()/chaosblade.dat；支持 CHAOSBLADE_DATAFILE_PATH 为目录或文件，部分错误会回退默认目录。Java Runner目前清空环境且白名单不含该变量；若想分离只读工具与可写状态，必须仅接受可信部署钉选的该环境变量，并验证实际位置，不能让请求设置。

权限：静态路径需要Docker查询访问、cgroup写入、setns、执行配套程序和状态文件写权限；准确最小capability组合、低权限helper入口与所有文件ACL现场尚未验证，标UNKNOWN。Docker socket并非低权限接口。不会为方便让整个后端root运行。

### D. argv（静态支持，尚非执行许可）

使用参数数组而非可执行shell文本，P是核验后的候选绝对路径，C是明确绑定的完整容器ID：

```text
[P, "create", "cri", "cpu", "fullload",
 "--container-runtime", "docker", "--container-id", C,
 "--cpu-percent", "10", "--cpu-count", "1", "--timeout", "10"]
[P, "status", U, "--type", "create"]
[P, "destroy", U]
```

help确认 load/fl 为 fullload别名，runtime默认docker；显式写出以避免歧义。status显式type=create避免默认查询实验失败后回退preparation记录（status.go:88–121）。禁止async、nohup、名称/标签匹配、自定义UID、force-remove。timeout保留与否要由监督契约决定，不能默默删掉当作修复。

### E. UID 和响应模型（源码推导，非现场真实回执）

- spec-go `util/util.go:102–109`：8个随机字节→16位小写hex。CLI command.go:77–130 自行生成并检查数据库碰撞，或接受外部UID；M1禁止外部UID。Java的16–64位白名单可容纳默认UID，不必为此改RecoveryHandle格式。
- spec-go `spec/response.go:128–155` envelope为 code整数、success布尔，error和result使用omitempty。成功通常 `{"code":200,"success":true,"result":...}`，并非必须存在error字段。
- create.go:223–224：result为UID字符串。仅表示CLI回执；仍需持久化和资源观察。
- data/experiment.go:30–39：status result包含Uid、Command、SubCommand、Flag、Status、Error、CreateTime、UpdateTime八个字符串字段（无json重命名）。command.go:92,112使用Cobra CommandPath保存，规范CPU动作是fullload，因此预计Command=cri、SubCommand=cpu fullload。help规范名与源码一致；现场真实记录仍UNKNOWN。
- status.go:36–41,88–139：实验路径状态Created/Success/Error/Destroyed；Success不代表销毁，Destroyed才是引擎恢复证据。缺记录为失败，不推断恢复。
- destroy.go:163–182：已Destroyed返回描述字符串；首次成功返回ExpModel对象。spec-go spec/executor.go:30–49中对象字段target/scope/action/flags/programs/categories带omitempty，ActionProcessHang为默认JSON字段名布尔。不是同一八字段数据库记录，也不保证result包含UID。
- destroy.go:206–218 只有executor成功后更新数据库Destroyed；仍必须后续查询同UID，不用destroy回执替代。
- cli/cmd/cli.go:40指定stdout；实际create/status/destroy的stderr内容、退出码边界、真实载荷、CPU效果与销毁完整性 **UNKNOWN（未执行）**。不为通过而删除解析器的失败保护。

### F. create 前身份核验复用

在现有 BladeLocalIdentityVerifier 内提取共享的节点/目录/文件摘要检查，新增一个清晰的“无UID意图检查”入口；原verify(saved)继续要求UID用于恢复，禁止伪造UID绕过。成功只表示本地部署身份匹配，不是执行授权。
现有类内使用有限清单核验CLI及必要配套文件，受控调整64MiB上限以容纳74,921,322字节候选（例如128MiB并测试上下界），保持禁止链接、读前后变化检查。状态路径必须与进程实际环境绑定。无需新增Validator家族。

### G. Phase 2B 最小 Change Set（提案，不在本轮实施）

严格区分“支持CRI命令契约”与“完成平台M1”：下面8个现有生产Java文件是建议的下一阶段契约收敛范围，不是8个文件即可完成整个真实闭环。

| 文件（engine/infrastructure/blade） | 标签 | 必要变化 |
| --- | --- | --- |
| DockerCpuCommandPlan.java | MUST CHANGE | 显式CRI规范argv和可信部署路径；保留旧快照命令语义，不接受用户路径 |
| BladeProcessChannel.java | MUST CHANGE | 核验/调用路径一致；create与前台命令生命周期策略分开 |
| BoundedProcessRunner.java | MUST CHANGE | 有界受控移交支持、异常兜底和可信状态目录环境；保留默认严格模式，不简单放任后代 |
| BladeResponseDecoder.java | MUST CHANGE | 明确CRI/fullload版本化解析，保留严格结构/失败语义 |
| BladeLocalIdentityVerifier.java | MUST CHANGE | 无UID准入入口、共用文件核验、大小/配套清单 |
| BladeRecoveryHandle.java | MUST CHANGE | status显式type=create、恢复调用使用相同可信路径，不改UID/归属白名单 |
| BladeExecutionSnapshot.java | MUST CHANGE | 区分旧Docker与新CRI计划、保持既有deadline不可重置 |
| JdbcBladeExecutionJournal.java | MUST CHANGE | 新格式读写与旧V1兼容；可用现有snapshot_format列，无需本阶段迁移 |

相应已有测试必须修改/补齐，计数不含测试。若2B只先解决进程生命周期冲突，可以再拆为Runner/Channel及其测试，不必一轮改完8个。

SHOULD NOT CHANGE：ChaosEngine接口、Fake语义、ApplicationService编排、恢复Gate/Contract/Validator、数据库migration、调度器、api3源码、Go原型、Demo业务、权限/前端/K8s。本阶段不新增RealChaosEngine。

完整M1还需要后续sandbox目标校验适配、最小真实适配器、Journal接线、事务/证据持久化和权限部署；不把这些隐藏在“8处即可完成”承诺中。不新增通用抽象可以完成契约收敛，但现在不能承诺完整M1的精确文件总数。

### H. 停止结论

Phase 2A完成。比Phase 1更接近明确实现方案：生命周期冲突已在两种系统无害复现，状态目录/UID/规范命令已由源码定位，候选文件现场摘要匹配。但不更改“NOT READY”：真实响应/副作用/恢复/权限仍未验收。没有真实create，没有CPU/内存/网络/进程故障，没有RealChaosEngine，没有api3修改。等待用户审批2B。

## Java Process Lifecycle Contract — Phase 2B-1

本节是已实现的受限通道契约，取代前文“所有调用均清理后代”的当前行为描述；前文2A实验仍是修改前的真实记录。没有真实工具调用或api3修改。

### 两种模式

- `STRICT_FOREGROUND`：原有两参数Runner.run默认模式；Channel.status/destroy显式使用。父进程退出而已观察后代仍活，返回DESCENDANTS_REMAINED并清理。非零退出不代表业务成功。
- `CONTROLLED_HANDOFF`：仅Channel.create使用。继续观察后代、检测取消/中断、限制输出和等待预算。必须父进程exit=0、stdout/stderr读取均结束、无读错/超限、在期限内且未观察到取消/中断，才返回HANDOFF。即使未观察到后代，也保持HANDOFF，不能据此证明没有后代。
- `HANDOFF`：前台完成并转移后续生命周期责任；不主动清理存活helper，`cleanupComplete=false`。它不是EXITED伪装，不是ChaosBlade业务成功，不证明任何工作进程合法或已被其他监督器接管。
- `cleanupComplete`：保持原义，仅说明本Runner负责的根进程与已观察后代已结束。不清理不能标true；也不能证明逃逸后代清除或故障恢复。

Runner无论模式均保留有限观察的局限：快速派生/重挂父节点可能漏检，不是安全沙箱；CONTROLLED是显式调用契约，不是已实现OS级supervision。尚未接入真实引擎，不允许将HANDOFF直接映射RUNNING。

### 失败与时间预算

START_FAILED不产生已启动进程；超时、取消、中断、输出超限、I/O错误、非零退出均不得HANDOFF，进入现有最多约2秒的清理。中断标志恢复。非零退出若留有后代可返回DESCENDANTS_REMAINED且保留exit=7等原退出码；没有后代时EXITED+非零仍为失败事实。

命令等待预算和2秒清理预算分开；操作系统进程启动本身仍非严格实时。输出流未完成不能交接：若helper继承输出导致流不结束，仍可能超时并清理，不能为保留helper忽略读失败。取消是采样式协作取消，不承诺HANDOFF返回之后再收到取消会自动追回进程。

### exit=0 / 非法JSON窗口

父exit=0 → HANDOFF且helper仍活 → 上层无法解析create响应：必须按CREATE_UNCERTAIN/保留PREPARING处理，保留占用，不重放create，不假定副作用不存在。本轮只固定通道契约，未实现应用状态转换。

HANDOFF后的解析失败不会触发Runner二次清理；已保存UID可用于后续status/destroy，否则需可靠现场证据/人工止损。保留helper并不等于安全：依赖已安排的恢复责任与必要的应急措施，不能宣布恢复成功。

`--timeout`助手是secondary safety net，必须兼容其存活，但不是M1 primary recovery mechanism。未来主路径仍为ChaosLab主动destroy同UID，再status确认Destroyed，然后残留/健康核验。当前BladeResponseDecoder未改，仍拒绝HANDOFF；这故意防止在2B-2前被误接入成功路径。

### 实现范围与测试

修改Runner、Channel、ProcessRunResult（仅新增HANDOFF outcome及说明）；测试增加无害fixture和ControlledHandoffTests。Channel新增包级runner注入供路由测试；Runner新增包级输入流装饰测试入口用于确定性模拟IOException，生产构造器仍使用原流。没有新的框架或外部运行依赖。

测试覆盖STRICT清理；HANDOFF正常及非法输出保留；timeout/cancel/interrupt/output limit/read failure/nonzero清理；启动失败/启动前取消；create路由到HANDOFF且status/destroy路由STRICT。成功交接的测试在finally显式终止自己创建的休眠进程，避免测试遗留。

Windows JDK24本轮定向报告：Runner13项、Handoff6项（包含多场景循环）、Decoder46项，共65项，0失败/错误/跳过。Linux Corretto21运行同三组测试，Maven退出0；使用--init、无网络、无Docker socket、只读源码/依赖缓存及容器临时副本。两端均有Mockito动态agent警告，无测试失败。不是完整项目verify或真实ChaosBlade集成验收。

### Phase 2B-2 最小后续范围（2B-1 时的历史提案）

先适配DockerCpuCommandPlan/BladeResponseDecoder的CRI规范参数及HANDOFF输入语义，补其对应测试；处理快照格式关联时同步审查Snapshot/Journal，不能悄悄改写旧V1语义。LocalIdentityVerifier无UID入口与配套核验属于后续批准范围，不能被这次HANDOFF替代。无需修改api3；无RealChaosEngine、migration或调度器。当前仍NOT READY。

## Phase 2B-2：CRI 执行契约收敛（2026-10-04）

本节取代前文有关“Decoder尚不接受HANDOFF”“2B未实施”的当前状态描述。历史实验不改写。本阶段没有运行真实 blade create、真实故障、修改api3或应用编排；没有新增引擎、框架、迁移或调度器。

### 1. stdout/stderr FD 继承实验

第一项实验为 `ControlledHandoffTests.inheritedOutputDescriptorsAreNotAssumedClosed`：无害JVM父进程启动继承两条输出管道的休眠helper，等待500ms后正常退出；使用CONTROLLED_HANDOFF、2秒命令预算。Windows JDK24与Linux Corretto21现场均输出：

```text
M1_FD_INHERIT: outcome=TIMED_OUT cleanup=true helperAlive=false
```

因此“父exit=0”本身不足以移交：输出未关闭会触发有界失败。测试保留跨JDK差异容许，但本轮两端观测均为上面的超时结果；不能描述成FD继承必定成功。

与候选对照：exec-cri `exec/executor_common_linux.go:159–274` 的helper采用Go exec.CommandContext且未设置Stdout/Stderr；nil不是继承CLI的输出管道。CLI `cli/cmd/recovery_command.go:22–39` 明确重定向 `>/dev/null 2>&1`。此前detached fixture正常HANDOFF且helper存活。本轮没有复现候选FD缺陷，不构成新的BLOCKER，无需修改api3。真实CLI进程链的现场运行仍UNKNOWN，未用真实create去验证。

### 2. 规范argv与可信路径

`DockerCpuCommandPlan.Deployment` 是现有类内的两个路径值，不是部署框架。P=可信绝对规范CLI路径，S=可信绝对规范状态目录；不得来自HTTP。

```text
[P, create, cri, cpu, fullload, --container-runtime, docker,
 --container-id, <64位容器ID>, --cpu-percent, <10..40>,
 --cpu-count, 1, --timeout, <1..30秒>]
[P, status, <16位小写hex UID>, --type, create]
[P, destroy, <UID>]
```

Verifier、Plan、Channel必须传入同一Deployment值。Channel在剥除argv[0]之前检查路径，create同时检查整个Deployment相等；不允许核验A后把参数交给B。Channel拒绝调用者在environment中提供CHAOSBLADE_DATAFILE_PATH，内部固定其值为S。Runner仅增补该一个环境白名单项，不改变生命周期算法。CLI `data/source.go:77–116` 使用这个目录中的chaosblade.dat；工作目录不是状态目录来源。

路径绑定不是文件不可变性的证明：部署文件/目录必须由可信主体管理，禁止非可信用户写入。现有链接拒绝、摘要和读前后属性检查仍不能消除核验后替换的TOCTOU；部署权限与实际状态文件写入位置尚需2C/现场准入核验。api3的环境路径失败回退行为未被修改。Windows默认路径只用于纯契约测试，实际Linux部署必须显式使用锁定候选路径，不能假定默认 `/opt/chaosblade/blade` 已安装候选。

### 3. Transport 与响应契约

| 操作 | 可解码transport | 成功payload |
| --- | --- | --- |
| create | HANDOFF、exitCode=0、cleanupComplete=false | result为16位小写hex UID |
| status | EXITED、exitCode=0、cleanupComplete=true | 精确八字符串字段；Command=cri，SubCommand=cpu fullload；Created/Success/Error/Destroyed |
| destroy | EXITED、exitCode=0、cleanupComplete=true | 下述两种源码形状；仅REQUIRES_STATUS_CHECK |

共用一个Decoder，保持stdout严格JSON、重复键/尾随文档/未知字段拒绝、stderr非空拒绝、64KiB上限、envelope类型约束。失败和DataNotFound不被解释成恢复。未知格式拒绝。根据snapshot.format显式选择旧/新方言，禁止凭payload猜格式。

destroy首次成功的细节：CLI `destroy.go:224–247` 调用spec-go `spec/model.go:411–426` 的ConvertCommandsToExpModel，重建target=cpu、action=fullload、flags字符串map；Scope/Programs/Categories保持空并被omitempty省略，ActionProcessHang默认false但没有json omitempty。因此新方言接受精确字段 `target/action/flags/ActionProcessHang`，不要求并不存在的scope=cri。已Destroyed分支 `destroy.go:153–157` 返回 `command: cri cpu fullload <flags>, destroy time: <time>` 字符串。两者均不携带可信恢复确认，不替代同UID的status Destroyed。测试是源码构造的合成JSON，不冒充实际回执。

HANDOFF后JSON非法、UID不匹配/无法保存等仍属CREATE_UNCERTAIN：保留占用及helper，不重放create、不假设fault不存在、不声明安全恢复。Runner不会因为上层解析失败追回helper。本轮未实现应用状态转换。--timeout只为secondary safety net，M1主恢复必须主动destroy UID并再次status UID==Destroyed，随后核验残留与健康。

### 4. 快照版本化与旧数据

- 新intent明确写CRI_CPU_V1，复用已有snapshot_format列。Flyway V1–V11完全不改，无新migration。
- 历史构造器仍为DOCKER_CPU_V1；旧数据读回保持create docker cpu load、status UID（无--type）、原固定CLI路径，以及16–64位UID约束。不会读成CRI或自动迁移。
- 新快照/RecoveryHandle都携带format，CRI仅16位UID。Journal读写保留format，绑定UID时要求format相等；跨方言绑定、未知持久化格式拒绝，inventory继续按无效快照处理。
- Decoder默认锁定CRI；恢复旧快照必须显式new BladeResponseDecoder(saved.format())，旧status的docker/cpu load不会被新方言接受。旧destroy保留原兼容解析。create统一必须HANDOFF，不能用旧格式绕过新的transport门槛。
- deadline仍在intent时确定并持久化，读取或重启不重新计算。

### 5. 无UID的create前身份检查

`verifyBeforeCreate(intent, plan)`复用现有Verifier的本地检查：要求CRI、UID为空、执行/目标/百分比/时长与Plan一致、Deployment一致；不伪造UID。恢复入口verify(saved)继续强制真实UID。

新增CRI构造器要求固定三项配套SHA-256：bin/nsexec、bin/chaos_os、yaml/chaosblade-cri-spec-1.8.1.yaml；与CLI同根。CLI摘要和版本标签绑定快照，节点和状态marker也匹配。缺少清单、文件缺失、摘要不符、符号链接/路径重定向、变化读取都fail closed；CLI及bin文件要求可执行。每文件上限由64MiB提高到128MiB，测试接受74,921,322字节无害文件、拒绝128MiB+1字节；没有取消上限。

只使用旧Verifier构造器不能批准CRI恢复。返回LOCAL_IDENTITY_MATCHED_NEEDS_TARGET_CHECK仅证明所检查的本地身份匹配；不是目标复核、权限授权或REAL EXECUTION READY。

### 6. 修改范围

生产代码：DockerCpuCommandPlan、BladeResponseDecoder、BladeRecoveryHandle、BladeLocalIdentityVerifier、BladeExecutionSnapshot、JdbcBladeExecutionJournal；加上必要的BladeProcessChannel路径/状态目录绑定，以及BoundedProcessRunner单项环境白名单扩充。ProcessRunResult本阶段无新增修改；工作区其HANDOFF变更属于2B-1。

测试：DockerCpuCommandPlanTests、BladeResponseDecoderTests、BladeExecutionJournalTests、BladeLocalIdentityVerifierTests、ControlledHandoffTests；新增CriExecutionContractTests。本节即docs/08更新。先前阶段的其他工作区改动保留，不计入本阶段范围。

### 7. 验收结果与边界

最终Windows JDK24执行 `./mvnw.cmd -q verify`：317项，0失败、0错误、1跳过；其中本阶段相关选择集142项。跳过的是主机不支持创建符号链接的identity测试，并非跳过合同断言。Windows首轮曾发生一次JUnit临时目录删除时占用（survivingDescendantIsNotReportedAsNormalCompletion）；进程终止断言已通过，重跑和后续完整verify通过，未据此修改生产清理算法。此项按测试稳定性风险保留，不伪装成每次首跑均成功。

Linux验证使用Corretto21、--init、--network none、没有Docker socket、只读源码/依赖缓存复制到容器/tmp后离线Maven运行。所有进程fixture仅休眠/输出，不调用Blade，也无需真实Docker daemon。

最终Linux选择集 `*Blade*,*DockerCpu*,*Handoff*,*BoundedProcess*,CriExecutionContractTests` Maven退出0：blade包139项全通过（含符号链接检查），另含3项inventory controller测试。具体包内计数：Journal12、Identity19、RecoveryContract12、EvidenceGate2、既有EvidenceValidator4、Decoder46、Runner13、Handoff7、CRI契约7、CommandPlan17。Windows最终同选择集0失败/错误，Identity的符号链接1项跳过。测试最后未发现遗留Java fixture进程。`git diff --check`通过；无migration/ApplicationService改动。未提交或推送。

契约层没有已确认的新BLOCKER，可以提交审核以进入Phase 2C的最小RealChaosEngine接线工作；本轮不实施。仍非REAL EXECUTION READY：真实响应与资源效果UNKNOWN、部署权限/状态路径现场绑定、专用sandbox目标复核、UID持久化不确定性和主动恢复闭环仍需后续验证。无需api3修改，不能把无害测试通过当成真实注入授权。
