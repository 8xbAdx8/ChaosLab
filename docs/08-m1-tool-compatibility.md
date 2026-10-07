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

## Phase 2C：最小 Real Adapter 与平台接线（2026-10-04）

本节为当前状态，取代前文“没有真实Adapter实现”的历史描述；仍然NOT REAL EXECUTION READY。真实候选未执行，没有真实create/CPU故障，没有root/sudo或socket权限修改。

### Git 隔离

先检查status和diff --check，将已审核2B-1/2B-2的相互依赖契约作为一个可编译独立提交 `81d3874`（Converge approved Phase 2B lifecycle and versioned CRI contracts）。提交明确排除原有BladeRecoveryEvidenceValidator及其测试、docs/05增量、tools/recovery-evidence，以及docs/07、09。没有reset/clean/push。2C改动保留工作区供审核；docs/05原有增量未删除、未混入2B提交。

### 结构与默认配置

```text
ExperimentExecutionApplicationService
  → ChaosEngine
      → FakeChaosEngine（缺省/fake）
      → ChaosBladeEngine（仅显式blade）
          → 已有TargetIdentityVerifier / Plan / IdentityVerifier / Journal / Channel / Decoder
```

仅新增一个Adapter与一个Spring Configuration，无Manager/Registry/Factory/Workflow层。配置名为 `chaoslab.engine`，仅blade注册Real；未知值不会回退为Real，应用因缺少ChaosEngine而启动失败。

blade模式要求10个 `chaoslab.blade.*` 设置：executable、state-directory、node-marker、node-id、state-id、tool-version、cli-sha256、nsexec-sha256、chaos-os-sha256、cri-yaml-sha256。构造时校验路径/标签/摘要格式；操作前复用文件摘要与marker检查。路径不来自请求。所有协作者共享同一Deployment实例。未在application配置中填任何可直接启用真实执行的配置值。

已有AutomaticExperimentRecoveryJob限定为fake/缺省注册，防止本阶段接线后自动调用真实destroy；没有新增调度器。现有LocalDemoTargetIdentityVerifier保持原有可信端口与限制，没有放宽专用环境准入。

### Create 事务时间线

1. 应用短事务：幂等检查、目标/全局占用检查、安全检查，插入PREPARING；提交后离开事务。
2. Adapter确认无ambient transaction，从TargetRepository读取Target并经现有TargetIdentityVerifier取得fresh identity；Plan比较请求identity与fresh identity。
3. 生成CRI_CPU_V1 snapshot，Journal.recordIntent使用REQUIRES_NEW；现有SQL验证已提交且匹配的PREPARING。重复intent拒绝，不重新create。
4. intent提交返回后verifyBeforeCreate核验本地身份；失败仍是派发前确定失败。
5. Channel.create（测试中只有Stub）→ HANDOFF → 严格decode native UID。
6. Journal.recordUid用REQUIRES_NEW提交原生UID；成功才返回EngineCreateResult(RUNNING)。平台engineExperimentId为 `blade-<executionId>` Journal引用；绝不从executionId猜测原生UID。
7. 应用短结果事务写execution RUNNING及experiment RUNNING。结果事务失败则重新读取状态，仅当仍PREPARING时写CREATE_UNCERTAIN，不覆盖更新的状态。

Intent前/本地身份失败可FAILED，Channel调用次数为0。从调用Channel开始采用保守边界：即使通道报告START_FAILED，也不在Adapter猜测，统一可能有副作用。timeout、HANDOFF非法JSON、非法UID、其他decoder失败、recordUid失败均抛EngineCreateUncertainException。结果事务失败同样保留不确定性。不自动重试create。若连不确定性事务也无法写入，已提交PREPARING仍保留占用，异常向上报告，不宣称CREATE_UNCERTAIN已经落盘。

同Idempotency-Key返回已保存记录，无第二次dispatch；另一实验使用相同占用目标被拒绝。测试使用数据库与独立线程连接在Stub入口检查：PREPARING和CRI snapshot已经可见且无活动事务。

### UID 持久化失败：明确的 Phase 2D 阻塞

**目前没有第二份可靠durable receipt。** native UID在内存里解码后，UID事务若回滚，快照仍无UID，平台CREATE_UNCERTAIN且保留目标。不能按容器名、时间邻近或UUID猜测UID，也不能重放create。工具自己的状态目录可能有信息，但尚未建立可靠的执行归属证据，不可当作已经具备人工恢复保证。

下一阶段最小设计候选（未实施）：在已解码UID与DB绑定之间保存有界、本地可信、可fsync的单执行receipt，包含execution ID、native UID、节点/状态目录身份、工具摘要、格式与时间，并定义写失败/回放/归属核验；这仍不能自动覆盖“工具已产生副作用但尚无可解码回执”的窗口，必须另外预检/设计人工止损。不要把本节当作已交付证据存储。

若UID已成功提交、只是RUNNING结果事务回滚，Journal仍保有UID；测试验证此时CREATE_UNCERTAIN与UID并存，可提供后续人工恢复依据，但本阶段不增加不确定状态自动destroy的入口。

### status / destroy 与事务边界

- status由Journal引用加载持久化snapshot，只接受CRI_CPU_V1；缺UID、其他格式、节点/状态目录/工具不符、fresh target变化即失败，不猜测或回退。
- status通道保持STRICT，观察UID交给BladeRecoveryContract核对。不匹配抛unsafe结果；Destroyed只说明engine状态。
- destroy先核验并使用同一保存UID；decodeDestroy后重新核验并status同UID，只有RecoveryContract.CONFIRMED_RECOVERED才返回EngineStatus.ENGINE_RECOVERED。
- 应用事务1提交execution与experiment DESTROYING；事务外等待Adapter；事务2写结果。沿用JPA实体@Version，测试在Stub等待期间提交另一个恢复决策，原结果被乐观锁拒绝，不能覆盖新状态。
- 原平台SUCCESS意味着完成，而本阶段没有残留/健康证据。最小调整是在**引擎结果枚举**增加ENGINE_RECOVERED，不新增平台状态/migration。应用把它记为现有ROLLBACK_FAILED，原因 `ENGINE_DESTROYED_RECOVERY_UNVERIFIED: residual and health evidence required`，finishedAt为空且保留占用；这不表示Blade destroy一定失败，而表示平台恢复验证未完成。
- Fake仍返回原DESTROYED并沿用原SUCCESS路径。真实Adapter不返回该最终成功结果。没有将Engine Destroyed冒充RecoveryVerified，也未连接EvidenceValidator/Gate。

### 文件与测试范围

新增生产文件：ChaosBladeEngine.java、BladeEngineConfiguration.java。
修改生产文件：EngineStatus.java、FakeChaosEngine.java、ExperimentExecutionApplicationService.java、AutomaticExperimentRecoveryJob.java（仅注册条件）。
新增测试：ChaosBladeEngineTests、ChaosBladeWiringTests、BladeEngineConfigurationTests。
文档：docs/08本节、docs/05顶部时效说明及末尾接线增量。没有修改api3、migration、目标验证策略、前端/RBAC/K8s、Go恢复原型或旧证据工具。

测试覆盖：fresh目标变化、本地身份失败、intent提交失败均0次dispatch；HANDOFF/UID持久化/RUNNING；非法JSON、timeout、UID提交失败、不确定性占用与幂等重放；RUNNING结果提交失败保留已存UID；保存UID查询、UID不匹配、缺UID/归属变化拒绝destroy；destroy回执但未Destroyed不恢复；Destroyed仅engine recovered；旧destroy结果乐观锁冲突；Fake默认、显式Real、每项缺配置启动失败。提交失败由真实事务beforeCommit回调抛错注入，断言事务回滚，而非仅模拟不存在的数据库结果。

所有Adapter/Application/配置测试均使用mock BladeProcessChannel；没有启动真实候选，现有Runner测试仅无害JVM fixture。H2数据库事务验证不是MySQL生产耐久性/崩溃恢复验收。双平台最终结果在下方补录。

### Phase 2D 剩余条件（本轮不实施）

最小可靠receipt及未知UID止损方案；专用sandbox身份验证适配；Linux最小权限、不可变部署与状态路径/文件权限预检；恢复责任和崩溃窗口；CPU baseline/during/recovery、可信residual/health观察、证据持久化、最终Gate与占用释放规则。--timeout仍仅secondary safety net。实际回执、CPU效果、真实destroy与恢复尚未验收。

本轮不为通过测试放宽root/sudo/socket，也不进入Phase 2D。代码接线完成不构成真实执行授权。

### 最终验收记录

- Windows JDK24：`./mvnw.cmd -q verify`退出0；341项，0失败、0错误、1跳过（已有符号链接测试，主机不可创建链接）。
- Linux Corretto21：离线完整`mvn -o -q verify`退出0。最终复验明确`uid=1000 gid=1000`，`--network none`、`--init`、源码/缓存只读挂载、没有Docker socket，测试副本位于容器/tmp。没有以root部署真实服务；没有启动候选程序。
- 新增测试两端均24项全通过：ChaosBladeEngineTests 10、ChaosBladeWiringTests 10、BladeEngineConfigurationTests 4，无跳过。缺配置测试的预期启动失败日志不代表验收失败。
- 集成测试实际验证PREPARING/intent/DESTROYING跨连接提交可见性，intent/UID/RUNNING事务beforeCommit失败，及destroy结果的乐观锁冲突。默认Fake相关既有测试通过。
- 首轮新测试曾因测试用UID重复、重新stub时触发旧Answer而失败；修正测试fixture后全量验收通过，未放宽生产约束。
- `git diff --check`通过；Flyway V1–V11无变化。仅2B已提交为81d3874；2C工作区改动待审核，没有push，原无关改动保留。

PHASE 2C COMPLETE

REAL EXECUTION NOT YET AUTHORIZED

## Phase 2D：只读现场预检进展（2026-10-04，未完成）

本节不是 Phase 2D 完成声明。没有运行真实 create、destroy 或故障，没有调整 sudo、Docker socket、组成员、capabilities 或文件权限。

SSH 在虚拟机启动后重新连通，地址 192.168.32.131。实际检查用户为 w（uid/gid 1000），组列表不包含 docker；这不是已经批准的最终 Adapter 服务账号。`docker inspect chaoslab-cpu-sandbox` 返回 socket permission denied，因此容器完整 ID、image ID、Running、安全配置、目标 cgroup 和 CPU baseline 均 UNKNOWN，不能根据旧记录填充。

### 当前部署文件

根目录 `/home/w/chaosblade-api3.r3cL98`。只读 sha256sum 结果：

| 文件 | SHA-256 |
| --- | --- |
| blade-chaoslab-api3-identified | c0c987bbd0aa9d158e90ab48f737680fe1b7eefdbf6c9e74744444c49c847e96 |
| bin/nsexec | 693219257100421d3c2321c2b1bfb3d285d1b6eed1fa82feabd033797a51e793 |
| bin/chaos_os | dee72446e32411f6a0d7a29c71b0ba0cc4dabbab1fcaba212c6c87d0ea965cfb |
| yaml/chaosblade-cri-spec-1.8.1.yaml | f8deebf2b90c44f414745cc6316e0cef545332173a7ef9a1998b9804531e7880 |

前三项与历史候选摘要一致；摘要一致不代表权限安全或 READY。部署根目录为 w:w 0700，CLI 为 w:w 0764，nsexec 为 w:w 0755；运行用户能够改写部署。`namei -l` 检查 nsexec 路径未显示符号链接，部署根下 maxdepth=3 的符号链接搜索无输出；尚不等同于所有未来配置路径的核验。根下 maxdepth=2 搜索 `.chaoslab*` 无输出；实际 node/state marker 路径和身份仍 UNKNOWN，不能凭空创建身份以通过检查。

Docker socket 为 root:docker 0660。w 的 CapEff/CapPrm/CapAmb 均为零；三个可执行文件的 getcap 无输出。cgroup v2 已挂载，根和 system.slice 为 root 所有，未发现已经配置给本账号的目标 cgroup 委派证据。CRI 源码 `exec/executor_common_linux.go:214–226` 加载目标 cgroup manager 并加入 helper，失败会走失败清理；所以只有 CLI execute 权限远远不足。namespace 所需权限和具体最小授权尚未完成核验，不宣称仅添加某一个 capability 即可运行。

### 当前阻塞及边界

- 权限模型 BLOCKED：普通用户无法读取 Docker 目标，且尚无已核验的受限提权执行路径。不得通过 docker 组、宽泛 sudo、整个 Spring Boot root 或任意用户可访问 socket 来消除本阻塞。
- 部署不可变性 BLOCKED：服务运行身份不能同时拥有候选及其父目录的改写权限；目前用户目录部署不满足该条件。
- sandbox、CPU、residual、health 和最终恢复 Gate 的现场验证未完成；残留状态不能标记 CLEAR。
- Native UID 源码初审发现 create 的持久 flag `--uid`、recordExpModel 优先采用非空 UID、experiment.uid UNIQUE 和普通 INSERT。尚未完成无害调用链/冲突测试，因此未改变 Java UID 持久化方案，也未实现 receipt。不得把源码初审当成已消除 UID durability 窗口。

后续涉及可信安装目录、专用账号或受限特权边界的落地，应先明确范围再授权实施；本轮没有自行扩大部署权限。其余 Phase 2D 实现与双平台测试仍待完成，不引用 Phase 2C 测试结果充当本阶段验收。

**Phase 2D INCOMPLETE — NOT REAL EXECUTION READY.**

## Phase 2D-2：M1 Privileged Wrapper（2026-10-04）

本节更新运行身份设计：未来 Real Backend 使用专用 `chaoslab` 服务账号，no login、无 sudo/docker 组、无 capabilities；`w` 仅为管理员。历史现场用户 w 的检查结果不等于未来服务账号验收。本轮未创建用户、未安装 wrapper、未修改 VM 权限/sudoers/systemd，没有以 root 运行 wrapper，没有执行真实 Blade 或故障。

实现位于 `tools/m1-wrapper`，Go 1.25 标准库独立小模块，无第三方框架。详细协议、状态模型、源代码摘要、测试边界及应用前风险见该目录 README。生产入口 Linux euid=0、无 argv；没有可由环境变量/请求开启的测试模式。未来固定安装入口 `/usr/local/libexec/chaoslab-m1-wrapper`；本轮产物仅在被忽略的测试输出目录，fixture 全部位于临时目录。

stdin 单 JSON，4 KiB、2 秒输入期限；拒绝未知/重复/大小写别名字段、尾随内容、非法 UTF-8、非法 UUID/UID。仅 preflight/create-cpu/status/destroy/observe。root policy 固定目标完整 ID/image、安全参数及文件摘要，不接受请求中的路径、容器、PID、namespace、cgroup、flags 或环境。固定 Docker Unix socket 只 GET 指定容器；没有任意 Docker 透传。`observe` 尚未实现探针，明确返回 OBSERVATION_UNKNOWN。

### 预分配 UID：ACCEPT，但 Java 接线仍待后续

对锁定 api3 的 disposable source copy 添加单独测试，所有 Executor 均替换为进程内 fake；未修改候选源树/二进制。真实 Cobra create 的持久 `--uid` 传入模型，真实 SQLite UNIQUE INSERT 在 fake 执行器入口之前对独立连接可见；重复 UID 在第二次执行器调用前拒绝，无替代 UID。create 返回、status 查询、destroy 查找/上下文/更新都保持同一 UID。

wrapper fake-executable 测试另外证明：CSPRNG 8 字节生成 16 位小写 hex；模拟平台 intent 持久化后调用 wrapper；wrapper 的 binding 文件及目录 fsync 完成后 fake executable 才能启动，`--uid U` 的返回值必须仍等于 U。此处不是声称 Java 现有 recordIntent 已支持 UID；本轮仅实现 wrapper，未更改 Java/数据库。

选择 PREALLOCATED_UID，不实现 durable create receipt。单实验 binding 是派发前授权/归属记录，包含 executionId/nativeUid/containerId/imageId/四文件摘要组合身份/state identity/node/createdAt，不是第二套回执机制。仅允许全新专用原生状态目录，已有 chaosblade.dat/WAL/SHM/journal 一律拒绝 create，避免将旧记录的 UID 碰撞误当成自己的实验。绑定 O_EXCL、文件 fsync、目录 fsync 后消耗授权；任何失败保留阻塞，不自动清理绑定或重放。授权最长五分钟，必须匹配全部身份且来自可信状态目录。

### 生命周期及环境

wrapper 不调用 shell，不 PATH 查找候选。生产清理环境、umask 077、额外 FD 标记 close-on-exec，子进程只接收固定白名单。候选有界哈希上限 128 MiB，检查所有父路径所有权/写权限/符号链接/ACL、执行位、companions 和 marker，再次执行前复验。

create 只有 exit 0、输出正常结束、严格成功 JSON 且同 UID 才 HANDOFF，cleanupComplete=false；status/destroy 为严格前台。超时、取消、真实 SIGTERM、非零退出、输出超限、非法回执、不同 UID 均由 wrapper 清理已知子进程，TERM 后 KILL，pidfd 防止向复用 PID 发信号；create 不确定性仍保留绑定。继承 stdout 的 helper 导致有界 TIMEOUT，不会冒充正常交接。清理结果不是 residual CLEAR；快速逃逸/最终路径 TOCTOU/特权部署行为仍有边界。

原 api3 timeout helper 也单独以编译型 fake executable 验证，带引号/空格的固定工具路径、状态环境、UID、euid 均保持。此测试调用候选已有固定 shell timer 实现，但没有调用真实 Blade；wrapper 自身不使用 sh -c。--timeout 仍非主要恢复机制。

### 验证与停止线

Linux uid 1000、无网络、无 Docker socket：wrapper 测试覆盖攻击性输入、绑定及生命周期；另行运行解析器/状态选择测试的 race 检查和 go vet。Windows 原生执行交叉编译的 Go 测试程序，只覆盖可移植逻辑，不声称覆盖 Linux 权限、fsync、pidfd 和进程清理；其生产执行路径被禁用。候选源码 TestM1UIDProof/TestM1TimeoutProof 两项通过。最终测试计数和构建/复验结果见 wrapper README 验收记录。

未修改 api3、Java、migration 或现有 recovery-evidence 工具；没有部署、没有 Git 提交/推送。后续仍需审核 wrapper、Java 预分配 UID 事务/传输接线、专用账号与 sudo/PAM、root-owned 部署、真实目标安全属性、namespace/cgroup 兼容以及 CPU/residual/health 最终恢复门。不能直接把当前 wrapper 替换进现有 Java 的 executable 参数。

WRAPPER IMPLEMENTATION REVIEW READY

VM PERMISSION CHANGES NOT AUTHORIZED

REAL EXECUTION NOT AUTHORIZED

## 2026-10-06 M1 closeout: implemented contracts, not a real execution result

This section supersedes the earlier *pending* Java/probe statements, not their
historical test records. No real create, fault, unknown-UID destroy, REAL
authorization, migration or api3 modification has occurred.

- The adapter generates 8 CSPRNG bytes as 16 lowercase hex digits. Committed
  PREPARING precedes the independent journal transaction that inserts CRI_CPU_V1
  intent **with the native UID** into the existing blade_uid column. Only after
  that commit are target/tool identities reverified and the same UID dispatched.
  A different response UID or invalid response is CREATE_UNCERTAIN, never a retry.
  DOCKER_CPU_V1 retains its existing read semantics; no migration is required.
- The ordinary backend uses the fixed sudo/wrapper channel. Root preflight
  returns its verified typed policy plus canonical SHA-256; the existing local
  identity verifier compares deployment, paths, four tool pins, state/node,
  target and CPU/duration against the committed intent. It does not read
  root-only files or fabricate a UID. The historical `order-service` application
  alias maps only to the root policy's exact sandbox, not an arbitrary container.
- CREATE_UNCERTAIN preserves an exact `blade-<executionId>` journal reference.
  This permits recovery using the previously committed UID. The uncertain
  recovery timestamp denotes the recovery attempt, **not** a proven fault start.
  A process crash leaving PREPARING still requires manual journal inventory;
  there is no recovery scheduler or automatic create replay.
- The wrapper persists a cgroup-v2 CPU baseline with its root binding before
  child dispatch. Fixed-target observe can sample during/recovery: same PID,
  start time and cgroup, usage_usec delta over a bounded one-second window.
  Residual scans the target cgroup and reviewed fixed tool/timer scope;
  inaccessible/ambiguous observations remain UNKNOWN. Health is the same running
  sandbox with CPU at most baseline + 1 percentage point, not application health.
- Destroy commits DESTROYING, destroys the same UID, reverifies identities and
  queries that UID's Destroyed state, then collects root observations. The
  existing BladeRecoveryEvidenceValidator and BladeRecoveryEvidenceGate require
  matching execution/UID/target/executor, post-attempt observations no older than
  10 seconds, engine confirmation, residual CLEAR and health HEALTHY. Only then
  does the adapter return final DESTROYED and the application record SUCCESS /
  release occupancy. UNKNOWN, PRESENT, unhealthy, stale or mismatched evidence
  retain occupancy. No intermediate failure is rewritten to SUCCESS in that call.

Windows JDK24 full Maven verify: 352 tests, zero failures/errors, one existing
symlink-platform skip. Linux Corretto21 full offline verify: exit 0, nonroot,
network none, no Docker socket, source/cache readonly. Go Windows portable tests
and Linux full tests/vet passed for the probe build. Production adapter tests
use Stub channels, never the real candidate.

VM root FAKE v3 acceptance verified HANDOFF with helper alive/cleanupComplete
false, bounded failed-create cleanup, strict status/destroy, SIGTERM of wrapper
and sudo, and cross-slot/policy/expiry/replay rejection. Wrapper SIGKILL yielded
no result, therefore UNKNOWN; the test harness cleaned exact observed pidfds.
This is not a guarantee against unobserved fast detach or malicious root.

The v4 probe build is now installed and root FAKE probe regression passed. The
first final admin attempt stopped before replacement on an existing database.
Source proof identifies eager initialization in exec/cplus/executor.go:104 and
exec/jvm/executor.go:263 via data.GetSource() / data/source.go:50-59, so even
version/help can initialize SQLite. Root read-only diagnosis confirmed integrity
OK and experiment/preparation/sqlite_sequence all zero; the exact SHA-locked empty
DB was recoverably archived, not deleted or adopted. The final composite report
passed=true, root FAKE observations showed live helper PRESENT then CLEAR/HEALTHY
after exact helper cleanup; final REAL readiness baseline 0%, residual CLEAR,
health UNKNOWN without a binding, and authorization absent. No-auth REAL create
was rejected three times. The create fresh-store gate remains fail-closed.
The dedicated VM currently has no
Java executable; actual nonroot backend/runtime/configuration verification is
not established by the disposable Linux Stub tests.

**NOT REAL EXECUTION READY — REAL EXECUTION NOT AUTHORIZED.**

### 2026-10-07 final deployment acceptance supersedes the runtime blocker

The dedicated VM now runs Java21.0.12.1 as chaoslab uid999/gid987, zero effective,
permitted or ambient capabilities, no supplementary privileged group. Spring
uses the unchanged 698a905 artifact, actual ChaosBladeEngine/Channel beans and
dedicated MySQL8.0.46 at 127.0.0.1:3306/chaoslab_m1. Flyway V1-V11 validation,
repository commit/rollback and loopback HTTP health/target read succeeded.
The administrator observed the root wrapper's ancestry containing the actual
Java PID, and the root report passed=true. No mocks or bean overrides were used.
An independent read-only entry point invokes existing private seams so the
locked production artifact stays unchanged. Unbound observation is obtained
through preflight's actual root observeTarget, not a fabricated experiment UID.

Full REAL policy/pins/target/node/state matched configured Java identity;
Docker socket directly denied, privileged files not writable, sudo unchanged.
Final root checks: REAL authorization/native store absent, experiments/executions/
snapshots all zero, no pinned tool/helper process, sandbox isolation unchanged.
The original idle container was restarted under the already-approved exact
inspect/start procedure after sleep expired: same container/image, new runtime
PID7575/start792426, stable cgroup and only sleep3600. Readiness residual CLEAR,
probeReady=true, CPU0%; no-binding health UNKNOWN is intentional, not a recovery
success. Independent SSH reconfirmed live Java PID7596, loopback HTTP UP and
unchanged usage_usec99015 over a further two-second sample.

**REAL EXECUTION READY — WAITING FOR USER CONFIRMATION.**
REAL authorization remains ABSENT; no real create/fault/destroy was executed.

### 2026-10-07 first approved REAL M1: INCOMPLETE, stop for manual review

The previous statement is historical pre-experiment readiness. The user then
approved exactly one real create. One-shot operator first-real-m1.py reserves a
durable no-replay marker, verifies fresh fixed identities and baseline, then
uses the normal backend API. The backend generated executionId/UID; an
independent MySQL READ COMMITTED connection observed the committed CRI intent
before root created a 15-second, full-policy/tool/target-bound authorization.
No manual Blade create or application-code change was used.

Execution8781633f-5915-47cf-86b7-87abae66a7cf, UID21d2d20071b3f449, one create.
Root binding/native record and successful strict adapter receipt matched that
UID. CPU0% baseline rose to9.800499% over1.256354s; both samples retained pinned
nsexec8573/chaos_os8574 in the exact target cgroup. Primary backend destroy was
requested immediately after that evidence, and the same UID reached Destroyed
before the 10-second timeout. No second create was attempted.

The backend nevertheless returned ROLLBACK_FAILED / engine destroy failed:
IllegalStateException; there is no final successful recovery decision. Later
same-subject root probes returned CLEAR/HEALTHY/CPU0%, but cannot certify the
earlier platform flow retroactively. Existing ACTIVE_STATUSES retains occupancy
for ROLLBACK_FAILED; live HTTP GET independently confirmed that state. Root
authorization was consumed and is absent. No state/evidence/UID was cleared.

The early Gate timing is consistent with a still-live timeout helper, but the
generic exception does not prove the exact source of failure. Do not claim a
diagnosis or fix from that inference alone. No repair, repeated destroy, fresh
authorization or second experiment was undertaken after the stop line.

**M1 INCOMPLETE — RECOVERY NOT VERIFIED — OCCUPANCY RETAINED.**

### 2026-10-07 root-cause-only: bounded recovery evidence settling

The single-observe defect is reproduced, not inferred from a process name. Before
changing production code, a harmless Java test supplied Destroyed, then PRESENT,
then CLEAR/HEALTHY. The unmodified adapter threw immediately, made only one
observe call and never reached the second observation. That characterization
test passed against the old code. The retained regression now requires VERIFIED
after the second, fresh status/observe pair, with exactly one active destroy.

An opt-in isolated Linux root fixture also passed using the existing FAKE
dispatcher and **unchanged** production residual probe: fake HANDOFF leaves a
harmless, naturally exiting helper, strict fake destroy/status confirms the same
UID Destroyed, residual is PRESENT while the pinned fake helper lives and CLEAR
after it exits. The cgroup-members file is synthetic and confined to a temporary
directory; root process visibility is real. No Docker socket, host PID namespace,
REAL policy, installed VM wrapper or real Blade is involved. CPU/health and
final Gate correlation are supplied by the harmless Java fixtures, not claimed
as new VM health acceptance.

The real incident's exact first-observe evidence was not retained, so the
specific claim that its timeout helper caused the original failure remains
**NOT CONFIRMED**. This fix addresses the confirmed single-observe failure path;
it does not retroactively certify or alter the first M1.

After one active destroy and same-UID Destroyed, the adapter admits read-only
settling attempts for a fixed 15-second monotonic window, sleeping at most
250ms between attempts. Each later attempt rechecks identity and obtains a new
status before a new observe. The existing Validator retains its 10-second
freshness limit, full subject matching and unchanged Gate. No create/destroy is
replayed. A VERIFIED result is accepted only before the monotonic deadline.
The window is an admission/acceptance deadline, not a promise of a 15-second
HTTP response: already-running verification/transport calls finish under their
existing bounded timeouts (10 seconds per transport plus bounded cleanup);
one verification consists of two sequential preflights. Late evidence cannot
produce SUCCESS. The application keeps committed DESTROYING/occupancy throughout.

- Fresh PRESENT through the window: MANUAL / RECOVERY_RESIDUAL_PRESENT.
- UNKNOWN, unavailable, stale, unhealthy or otherwise incomplete evidence:
  INCOMPLETE / RECOVERY_EVIDENCE_INCOMPLETE, never SUCCESS.
- UID/target/node/execution mismatch: immediate MANUAL / RECOVERY_IDENTITY_REJECTED.
- Destroy/status not confirmed: ENGINE_RECOVERY_NOT_CONFIRMED, never recovered.
- Interruption preserves the flag and yields incomplete evidence.

A small typed EngineRecoveryException carries only these four allowlisted
reasons to the application; the persisted errorMessage uses the reason, never
raw exception messages, stderr, file paths or root details. Existing terminal
behavior remains ROLLBACK_FAILED with occupancy held on failure; SUCCESS is only
written after the unchanged Validator/Gate yields VERIFIED. No transient
ROLLBACK_FAILED-to-SUCCESS transition was added.

Verification: Windows JDK24/release21 and isolated nonroot Linux Corretto21 full
backend verify each ran 360 tests, zero failures/errors (Windows one existing
symlink skip, Linux zero skips). Demo reactor verify passed on both, 10 tests
each. Wrapper: 14 Windows portable tests, 22 Linux nonroot tests plus go vet;
the opt-in root settling fixture passed separately. Windows Python observation
tests 15 and existing one-shot operator pure/AST tests 4 passed. Sources/dependency
cache were read-only for Linux, network none and no Docker socket.

Production changes are limited to ChaosBladeEngine, BladeProcessChannel's
identity-failure classification, the two-line application reason mapping and
one tiny typed exception. Probe/Validator/Gate, api3, migrations, sudoers and
installed binaries are unchanged. This revision is **not deployed to the VM**.
Original first-M1 evidence SHA remains
7e8867fcdd2475ecf5c969c0eb6c1bf7539503c9a4ca65d42d0d0178dec5fffa.

**ROOT CAUSE CONFIRMED (single-observe code path; exact first-incident trigger not confirmed)**

**MINIMAL FIX VERIFIED — SECOND M1 NOT YET AUTHORIZED.**
