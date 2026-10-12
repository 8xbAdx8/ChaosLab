# R1 — V1 Release Candidate 验收清单

目标 `v1.0.0-rc1`；正式 `v1.0.0` 尚未发布。状态只用 PASS / FAIL / BLOCKED / NOT_RUN；PASS 必须说明是本机自动化、CI 或人工，而不是把三者混在一起。

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
- 首个发布代码提交 `3690522` 的 [Delivery CI](https://github.com/8xbAdx8/ChaosLab/actions/runs/38200706464) Windows/Ubuntu均因新增null/undefined预期错误失败，不是随机红灯；修正原reader的scalar/null映射并验证后完整重跑通过。未删除测试、未放宽已有安全断言，没有尚未定位的偶发失败。
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
