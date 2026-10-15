# R1 / R2 — V1 Release Candidate 验收清单

目标 `v1.0.0-rc1`；正式 `v1.0.0` 尚未发布。状态只用 PASS / FAIL / BLOCKED / NOT_RUN；PASS 必须说明是本机自动化、CI 或人工，而不是把三者混在一起。

## R2 最终验收更新（2026-10-15）

以下是最新状态；后续 R1 章节保留 2026-10-12 当时的验收记录，不将旧测试或旧 CI 冒充本次结果。

- 起始 PR #9：OPEN / CLEAN，HEAD `ca816146063bfb3260c807a812d11e0aaefee6eb`，Java/Web/Delivery Checks 全绿；main 仍为 `ddcb89e6bd1fe8d5528a68e1b51eb005c6181e43`。没有自动合并、tag 或 Release。
- 独立 `--no-local` clone：`C:\Users\W\ChaosLab-rc2`，工作分支 `codex/rc1-final-acceptance`。没有从两个旧 RC1 目录复制账号、H2 或产物；旧目录均无源码修改，原 D 盘用户未提交文件也保留。
- 指定 ADMIN 及独立 VIEWER 测试身份仅在这个新目录创建。密码经内存 pipe 送到 test-classpath BCrypt 工具，未进入 argv、环境变量、源代码、Git 或日志；只有用户名/hash 的 accounts.json，**没有明文 smoke-credentials.json**。账号不是项目默认值，也不是发布资产。工具不存在于生产 JAR；CREATE_NEW 拒绝覆盖。

| 项目 | 状态 | 依据与验证类型 |
|---|---|---|
| 本机 Java24 全量 verify | PASS | AUTO；425 tests、0 failures/errors、1 symlink 能力跳过；新增4项隐藏密码校验/重试/清理测试，未删改原安全断言 |
| React/TypeScript/lint/两种build | PASS | AUTO；47项全部通过，显式使用 backend/target 的真实契约。最初未设置契约环境时42通过/5跳过，补齐契约配置后重跑47通过，不混淆两次结果 |
| Node/发布测试 | PASS | AUTO；21项，Windows19通过/2个平台跳过；真实临时 stdin 账号、拒绝覆盖、无明文文件、无输入泄漏；终端 raw mode/keypress 所有权释放 |
| 指定本地测试账号 | PASS | AUTO；只保存 BCrypt，登录实际生产 Session 链；不等于首次真人输入已验收 |
| Fake 完整业务 | PASS | AUTO；CREATED编辑、参数校验、SafetyGuard、RUNNING、相同幂等键同Execution、主动停止SUCCESS、审计、报告/analysis；physical UNKNOWN、CPU null、INSUFFICIENT_DATA，未伪装物理故障 |
| Session/RBAC/CSRF/REAL边界 | PASS | AUTO；未登录401、有效VIEWER读且写403、缺CSRF403、退出后旧Session401、REAL/root拒绝、请求中切换engine拒绝；服务端回归保留 |
| 立即停止和重启 | PASS | AUTO；Execution/Report读回，无关Java/Node仍活着，保留WRITE_DELAY=0；未增加sleep掩盖持久化，未删除任何个人H2 |
| history独立回归 | PASS | AUTO；匿名无DB、原H2 mtime不变、固定SHA/JSON Pointer；CPU0→10.91→0、Destroyed/CLEAR/HEALTHY/VERIFIED，原因UNKNOWN不升级ACTIVE |
| 新RC2目录及五个私有路径NTFS ACL | PASS | AUTO只读；Owner=W，W/SYSTEM/Administrators完全控制；没有Users、Authenticated Users、Everyone、CodexSandboxUsers；没有用mode/chmod推断NTFS，也未改ACL |
| 旧manual目录ACL | BLOCKED | AUTO只读；root及.local/dev/H2继承CodexSandboxUsers的ReadAndExecute；没有额外写权限，但可读取hash/数据。不使用其账号/DB，修复仅在授权后执行 |
| 真人 Windows Terminal 隐藏密码 | NOT_RUN | HUMAN待验；工具伪终端的输入显示不能证明真人终端不回显。没有把自动配置或模拟TTY记成人工PASS |
| 本机Java21 / Ubuntu人工 | NOT_RUN | 本机Java24为补充验证，Java21和Ubuntu依赖对应新HEAD CI；不操作VM/WSL/SSH |
| R2修复代码Windows/Ubuntu CI + Unix真实PTY | PASS | AUTO；代码commit `ba99ca6764d4fa053cd84f0cae9129e1589745d5`的[Java](https://github.com/8xbAdx8/ChaosLab/actions/runs/38722201325)、[Web](https://github.com/8xbAdx8/ChaosLab/actions/runs/38722201321)、[Delivery](https://github.com/8xbAdx8/ChaosLab/actions/runs/38722201305)全部通过，含原harmless root。Ubuntu真实PTY的Node→Java、错误确认后重试及无密码回显通过；Windows按条件跳过该Unix步骤，不冒用POSIX结果。最终仅文档HEAD仍需核对独立Checks |

### 密码问题调查与最小修复

Windows伪终端复现了 `readline.pause()` 后 `stdin.isRaw=true`、Java读取停滞；关闭readline后 `isRaw=false`，同一无害密码fixture可以完成并退出0。这证明输入交接缺陷，**不能断言此前真人两次“不匹配”必定只有这个原因，也不能归咎用户**。JDK24测试不冒充JDK21人工验收。

修复仅让每个非秘密问题创建/关闭自己的readline，并在交接前等待libuv释放读取；不改Java Console隐藏输入机制。PasswordHashTool保留至少12字符/最多72 UTF8字节与BCrypt12，失败分类提示、不含输入、最多三次原地重试，数组finally清零。EOF/失败不写账号；单独configure可复用已有JAR，正常start仍构建当前源码。未更改执行器、Security、CSRF、状态机、数据库migration或root边界。

本机虚拟终端输入适配器的显示不作为密码隐藏验收依据；Windows真人首次流程仍NOT_RUN。Node的输入释放语义见[官方readline说明](https://nodejs.org/api/readline.html#rlclose)，隐藏读取契约见[Java Console](https://docs.oracle.com/en/java/javase/24/docs/api/java.base/java/io/Console.html)。

修订后的实际Windows虚拟终端fixture还完成了“不匹配→同进程重试→匹配→退出0”；没有重建或写账号。Ubuntu Java21 CI则在真实Unix PTY中证明无输入回显。这两者都不能自动升级为真人Windows Terminal的HUMAN_PASS。

### R2 自动化记录与失败说明

Fake Experiment `4377cc43-cea4-46b4-8d65-9d8b14bdf4c0`；Execution `1b14bce4-9f59-49b3-bab4-f2560493e3a4`；Report `a92e90ad-d3b0-421f-83bd-9c214344b39f`。全部为SIMULATED，非M1现场证据。服务退出码0，session/start.lock不存在，验收端口已释放，账号/H2留在原新目录。

- 一次Node测试误用本机默认JDK17，doctor正确先拒绝，后续“缺npm/无账号/锁”断言因此失败；使用当前进程JAVA_HOME24后21项按平台正常通过，未改断言。
- 一次验收编排误启动两个Maven，停止的仅是已核对RC2路径的自有测试进程树；不将中断结果计为PASS，重新串行全量verify取得425项结果。
- 最初内存pipe测试驱动自行重建Windows环境遗漏PATHEXT且ComSpec采用了不适合Node cmd参数处理的拼写，启动前被doctor拒绝（0次create）；修复驱动环境后实际完整流程退出0，没有修改生产guard以迁就驱动。
- WindowsApps的python别名不作为执行依据；改用明确的Python3.12运行产物检查和原历史保护测试，2项通过。Unix PTY本机NOT_RUN，等待Ubuntu CI；没有调用真实Blade或现场脚本。
- 补充本机Python回归：观测/报告16项、旧M1禁用保护8项（1项Linux strace格式fixture在Windows跳过）、Core操作器无害测试5项均通过。以上运行的是`test_*.py`，没有调用真实实验入口。Ubuntu PTY随后已在上述R2 CI通过。

### ACL最小修复建议与审批结论

旧 `C:\Users\W\ChaosLab-rc1-manual\.local` 的额外读取来自继承ACE。最小建议是**仅此.local范围**关闭外部继承并保留W/SYSTEM/Administrators，移除不必要读取，随后逐项复核；不改用户主目录、不删除数据、不递归重置系统ACL。已请求用户批准，未获得明确批准前不执行。

代码可在最终HEAD CI通过后进入PR审核；**rc1 Tag/Release仍不能自动批准**：真人隐藏输入待验，旧manual目录ACL尚未获准处理。可以使用已通过只读ACL检查的独立RC2目录继续本地演示，但不能把它写成旧目录已经修好。所有M1历史证据与下列SHA不变。

## 基线与独立输入

- PR #8 已按授权合并，main：`ddcb89e6bd1fe8d5528a68e1b51eb005c6181e43`。API 核对原 PR HEAD `d214d2f6619b36ddbaf7debf21466a2f89eeb5a1`、CI 全绿；无额外 main 提交。
- [基线 Java CI](https://github.com/8xbAdx8/ChaosLab/actions/runs/38199565171)、[Web](https://github.com/8xbAdx8/ChaosLab/actions/runs/38199565155)、[Delivery](https://github.com/8xbAdx8/ChaosLab/actions/runs/38199565145) 均 PASS。
- 独立分支 `codex/v1-release-candidate`；不合并此发布 PR、不创建 tag/Release。最终 HEAD 和该 HEAD CI 以发布 PR 为准，不能把基线绿灯当候选绿灯。
- 新 Git clone 使用 `--no-local`（无共享文件/alternates），核对 GitHub 已签名 merge 的完整 commit SHA 与 tree SHA `6911273b263554710ee8634f73d1bd6e74986a24`。本机 github.com Git transport 解析至不可用地址，采用本地 Git transport + GitHub API 对象核对；不冒充成功的网络 git clone。
- 建立时 `node_modules` / `backend/target` / `.local` 全不存在。只允许标准全局 Maven/npm 下载缓存；未复制 M6 的依赖、JAR、账号、H2 或进程。
- D 盘初始 clone ACL 有 Authenticated Users Modify，FAIL 使用前提；未改系统 ACL，实际验收改在新用户私有目录 clone，只读 ACL 为本人/SYSTEM/Administrators。原工作区及数据库不动。

## 环境与运行矩阵（2026-10-12）

| 项目 | 状态 | 类型与依据 |
|---|---|---|
| Windows11 干净源码、Node24、JDK24 | PASS | 本机自动化；OS Windows11 Pro 10.0.26100，Java24；非 Java21 手工验收 |
| 本机 Windows11 JDK21 手工流程 | NOT_RUN | 本机仅有 JDK17/24；不凭 CI 冒称人工验收 |
| Windows/Ubuntu Java21 候选代码 CI | PASS | `aaaca103f16a251bea96672c6bf52a39113f0bef` 下Java/Web/Delivery全部绿；本次最终文档提交还须核对其独立CI，不能用旧run冒充新HEAD |
| 本机 Ubuntu 手工流程 | NOT_RUN | 未访问 Ubuntu 开发机，未 SSH/WSL/VM |
| 真人首次账号配置 | NOT_RUN | 必须本人完成隐藏输入/重复确认/可选 VIEWER；随机测试工具不能替代 |
| 自动化随机账号登录 | PASS | test-classpath DeliveryTestAccounts，不进入 JAR；无默认部署账号或密码输出 |
| Windows clone根目录 ACL | PASS | 只读审查为本人/SYSTEM/Administrators；非 chmod，非跨账号攻击测试 |
| 验收生成的账号目录/文件 ACL | BLOCKED | 执行工具给新`.local/dev`及hash-only测试账号加入了本机CodexSandboxUsers读取ACE。未声称严格owner-only；最终真人目录需要独立复核/配置，不自动改用户或系统ACL。随机测试配置不作真人部署凭据 |
| Windows 异常 NTFS ACL 自动拒绝 | NOT_RUN | 现有工具不实现 ACL 引擎；私有目录是人工前提，不伪称 POSIX mode 等价 |
| macOS | NOT_RUN | 无 CI/本机验收，不列为已验证平台 |

## Doctor 负向验收

`node --test scripts/dev.test.mjs scripts/release.test.mjs`；错误归档仅在临时 fixture 改副本，绝不改真实 JSON。

| 项目 | 状态 | 依据 |
|---|---|---|
| JAVA_HOME 缺失 | PASS | 在写私有目录/启动前拒绝；明确安装/配置提示 |
| Java17 | PASS | 本机真实 JDK17 验证；CI 若未安装17明确跳过，不伪造 |
| Node 缺失 | PASS | 实际 PS/Bash入口 PATH 隔离，不全局安装或改配置 |
| npm 缺失 | PASS | doctor PATH 隔离，提示 npm missing |
| 端口占用 | PASS | 现有 socket 测试；原监听仍活着，不抢占/杀进程 |
| 不安全环境覆盖 | PASS | Spring/engine/Java/Node/proxy 默认拒绝；值和任意敏感键后缀不输出 |
| SHA 错误 | PASS | fixture JSON 拒绝，真实归档 SHA 不变 |
| 已有锁/session | PASS | 拒绝重复启动，marker 原内容保留 |
| 无账号 | PASS | doctor 明确提示交互 start-fake，不创建默认用户 |
| Unix 私有目录0755 | PASS | Ubuntu候选CI对实际临时目录执行拒绝测试；本机Windows仍跳过，不将chmod当NTFS |

## 业务与持久化

发布扩展复用 `scripts/delivery-smoke.mjs` 和实际 packaged JAR + Vite + H2，非 Mock Server。**本机最终完整扩展流程 PASS（JDK24 自动化），不冒称人工交互。**

| 项目 | 状态 | 依据 |
|---|---|---|
| ADMIN + CREATED草稿 + 编辑 + 校验/预检 | PASS | 本机扩展执行已到达正确状态；正常生产 REST 链 |
| Fake启动/幂等一次dispatch/同ID停止 | PASS | 既有API，真实持久化；模拟，不是物理 fault |
| SIMULATED / NOT_COLLECTED / INSUFFICIENT_DATA / physical UNKNOWN | PASS | 报告和analysis服务端字段，CPU baseline=null，不填0；cause UNKNOWN |
| 审计、关联报告、ADMIN登出重新登录 | PASS | 实际Session和持久化读API |
| VIEWER读/创建编辑启动停止报告403 | PASS | 有效Session/CSRF直连代理后端接口，非隐藏按钮 |
| 缺CSRF、旧Session重放、REAL/root/engine切换拒绝 | PASS | 写操作403；未知engine字段400；注销Session再读取401 |
| 立即停止 → Execution/Report 重启可读 | PASS | 既有 WRITE_DELAY=0，没有加 sleep掩盖；无关Java/Node仍活着 |
| history独立、数据库mtime不变、字段来源 | PASS | 最终完整smoke验证匿名无DB链、raw pointer/scalar-null映射、3阶段原始CPU及显示四舍五入；停止后H2 mtime不变 |
| 常规console白名单/安全、Fake并发/状态机 | PASS | 本机完整 Maven verify；原断言保留，无真实执行 |

## 测试运行记录和跳过

- 本机独立 clone 首次 Maven Wrapper verify：421 run、0 failures/errors、1 skip，BUILD SUCCESS（JDK24）。跳过 `BladeLocalIdentityVerifierTests.symbolicLinkIsNotAcceptedAsToolEvidence`：Windows无symlink能力；保留原测试，LinuxCI执行。
- Web：47项 PASS，strict/lint/正常及history build PASS；ESLint弃用与大chunk警告保留。
- 启动/发布Node本机：19项，17 PASS、2 skip：Windows 无特权 symlink 测试、Unix chmod（不代表 NTFS）。原安全断言未降低。
- 本地初次 Web 命令与启动器 npm ci 重叠，导致 vitest暂时不可用；序列化后47项通过。属于验收编排问题，不删除测试。
- 新 Java sentinel 首次执行发生在增加 fixture 后、重新编译前；它已提前 ClassNotFound，停止存活断言因此正确失败。新增编译前置检查；重新 build 后 sentinel存活断言通过。不是把进程死亡当正常。
- 新历史字段断言起初将 missing 的JSON projection错认作JS undefined；源码原契约明确 missing/非标量=null。修正新增断言忠实检查原契约，不改 reader或原数据。
- during原始精度为10.911216516130064，10.91只是显示四舍五入；新增测试先逐字段对照raw pointer，再核对2位显示，不能要求原始值被改为10.91。
- 候选代码 `aaaca103f16a251bea96672c6bf52a39113f0bef`：[Java](https://github.com/8xbAdx8/ChaosLab/actions/runs/38201544623)、[Web](https://github.com/8xbAdx8/ChaosLab/actions/runs/38201544692)、[Delivery](https://github.com/8xbAdx8/ChaosLab/actions/runs/38201544702) Windows/Ubuntu全PASS，含原root harmless job。Ubuntu backend 421/0失败/0跳过，Node19/0失败/0跳过；Windows hosted backend421/0失败/0跳过，Node19/17通过/2跳过。本机Windows backend的1个symlink跳过不能混入有该能力的hosted runner统计。最终PR HEAD以Checks实时结果为准。
- 首个发布代码提交 `3690522` 的 [Delivery CI](https://github.com/8xbAdx8/ChaosLab/actions/runs/38200706464) Windows/Ubuntu均因新增null/undefined预期错误失败，不是随机红灯；修正新增断言以遵循原reader的scalar/null映射后完整重跑通过，未修改reader或历史数据。
- `ce695d1` 的[PR Delivery CI](https://github.com/8xbAdx8/ChaosLab/actions/runs/38202308792)发生一次Windows PowerShell入口测试超时：10s后stdout/stderr为空；同提交[push CI](https://github.com/8xbAdx8/ChaosLab/actions/runs/38202305378)完整通过。仅将该无Node入口测试的外部shell启动期限改为30s并显式检查spawn错误；安装提示和非零退出断言完整保留，生产命令/清理期限没有修改。最终HEAD需重新完成Windows/Ubuntu CI，不将重跑或旧HEAD绿灯当成修复证明。
- 既有 M6 夹具/审计时序修订见 docs/18，本轮不再放宽它们。Package启动中的`-DskipTests`仅避免重复verify；以上统计来自独立完整verify，不把package打印的“Tests skipped”算额外漏测。

### 本机自动化标识（SIMULATED，非现场证据）

最终验收 Experiment `4d1d6c70-44c7-4224-9f67-67612aef036d`、Execution `9c5b8016-9a50-4a31-842b-156297998778`、Report `696d4c9b-eccc-4c86-81c5-002630baf79c`；立即停止后原记录可读回。
JDK24本机产物 `chaoslab-backend-1.0.0-rc1.jar` SHA256 `7d73927b690a2dbdb0cf62bf7285dc983cc1ebc6cc6425471b623d9b1bfdc3b6`。这是本机构建校验，不宣称跨平台字节重现或已发布资产；Java21 CI产物另验。
本轮只在新clone生成随机测试配置/数据库；它们原位置保留且服务已停，其他M1–M6工作区及个人数据未删除。测试账号不得作为真人默认账号。

## 不变的历史证据

| 文件 | SHA-256 |
|---|---|
| m1-first-real-20261007.json | `7e8867fcdd2475ecf5c969c0eb6c1bf7539503c9a4ca65d42d0d0178dec5fffa` |
| m1-second-real-20261007.json | `db66f4ac5df2ccbd1dc09bfd68c9e07389f6b73c9b440ff4385dae9f26909e67` |
| m1-core-real-20261010.json | `b57cac027022e3c3b009b6daf20eef22c9146d2c7f32dd5c579841fe6eb84f33` |

本轮未发 REAL create/destroy/authorization，未访问VM/现场MySQL/原binding/native state。物理恢复与原因归属是独立结论，UNKNOWN 不升级 ACTIVE。

## 发布审批停止线

当前 **技术自动化PASS；整体发布审批BLOCKED：真人首次账号流程/最终NTFS配置未验收，发布 PR 未审查/合并，tag/Release 未授权。** 没有尚未定位的代码阻塞；这些也不等于新增M7或降低标准。

1. 完成此 PR CI并审核必要改动；真人在私有新clone使用Java21按README完成首次ADMIN/VIEWER、密码隐藏、退出/二次启动，填本节人工依据。
2. 人工批准合并发布准备PR后，核对**新的 main merge SHA**和对应CI，不从旧 ddcb 基线（当时还是旧artifact版本）直接打rc tag。
3. 明确授权后才在该main提交创建 `v1.0.0-rc1` tag和prerelease；Release Notes在 `docs/releases/v1.0.0-rc1.md`。本轮不执行。
4. RC后按反馈另审 `v1.0.0`，不能把候选自动称为正式稳定版。
