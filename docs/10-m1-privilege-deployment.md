# Phase 2D-3：权限部署记录

## 2026-10-04：修改前盘点，等待管理员认证

本阶段已获准部署到专用 chaoslab-executor VM，但仍禁止真实 Blade create、真实故障及生成 create 授权文件。当前只执行了 SSH 只读检查，**未做任何 VM 修改**，未创建账号、安装文件、调整 sudoers 或切换快照。

SSH 地址 192.168.32.131；管理员 w 为 uid/gid 1000，组为 w、adm、cdrom、sudo、dip、plugdev、lxd。`sudo -n -l` 返回需要密码。因此尚未取得 sudo 规则内容、完整 include 审计及管理员级回退备份；不能把下面的普通用户可见信息当成完整回退点。

| 项目 | 修改前可见事实 |
| --- | --- |
| chaoslab 用户、同名组 | getent 未找到 |
| /etc/sudoers | root:root 0440，1800 字节；内容尚未审计 |
| /etc/sudoers.d | root:root 0755；可见目录项仅 README（不含 . / ..） |
| /usr/local/libexec | 不存在 |
| /usr/local/libexec/chaoslab-m1-wrapper | 不存在 |
| /etc/chaoslab-m1 | 不存在 |
| /var/lib/chaoslab-m1 | 不存在 |
| /var/log/chaoslab-m1 | 不存在 |
| /opt/chaoslab/m1/api3-identified | 不存在 |
| 原候选根 /home/w/chaosblade-api3.r3cL98 | w:w 0700 |
| 原候选 blade-chaoslab-api3-identified | w:w 0764 |
| 原候选 bin/nsexec、bin/chaos_os、CRI YAML | w:w 0755 |

原候选只读 SHA-256：

| 文件 | SHA-256 |
| --- | --- |
| blade-chaoslab-api3-identified | c0c987bbd0aa9d158e90ab48f737680fe1b7eefdbf6c9e74744444c49c847e96 |
| bin/nsexec | 693219257100421d3c2321c2b1bfb3d285d1b6eed1fa82feabd033797a51e793 |
| bin/chaos_os | dee72446e32411f6a0d7a29c71b0ba0cc4dabbab1fcaba212c6c87d0ea965cfb |
| yaml/chaosblade-cri-spec-1.8.1.yaml | f8deebf2b90c44f414745cc6316e0cef545332173a7ef9a1998b9804531e7880 |

## 计划修改范围（尚未执行）

- 创建专用 chaoslab system 账号及独立组；仅由用户管理工具更新相关账号数据库（/etc/passwd、/etc/shadow、/etc/group、/etc/gshadow 等），不把这些文件内容打印进报告。无交互登录、无 sudo/docker/管理员组。
- /usr/local/libexec 及唯一入口 /usr/local/libexec/chaoslab-m1-wrapper。
- /etc/chaoslab-m1 下 policy.json、node-id；/var/lib/chaoslab-m1/state 下 state marker。不创建 authorization.json。
- /var/lib/chaoslab-m1/audit：root-only 验证/部署记录（用户于 2026-10-05 批准替代原计划 /var/log/chaoslab-m1）。
- /etc/sudoers.d/chaoslab-m1：仅 chaoslab 对固定无参数 wrapper 的规则；不修改 /etc/sudoers，不增加 w 的免密能力。
- /opt/chaoslab/m1/api3-identified 及缺失父目录：新的 root-owned 固定发行，不覆盖 /home/w 原候选。
- 独立 fake fixture 目录与临时 staged 部署/回退包：精确路径必须在实际操作前记录；不能将 fake 文件混入正式发行。
- 管理员级回退备份保存在专用 root-only 路径，正式确定路径后补记。仅记录/备份，不自动恢复 VM 快照。

## 当前阻塞

1. **管理员认证**：SSH 会话不能免密执行 sudo。已请求用户仅在 VM 本地输入密码，提供 `sudo -l -U w`、`sudo visudo -c` 输出。不能用 docker/lxd 等旁路获得 root，也不能为方便临时开放 NOPASSWD ALL。在 VM 另一终端运行 sudo -v 不保证 SSH 会话获得凭据缓存。
2. **无授权文件与 create 成功路径的验证边界**：已审核生产 wrapper 在缺少 authorization.json 时必然拒绝 create-cpu。因此本阶段不能通过生成授权或添加运行时绕过标志来完成 HANDOFF。可以验证拒绝路径；完整 create 入口的授权成功路径无法在此禁令下证明。隔离的编译型 fake harness 若用于 runner HANDOFF，必须明确仅证明 runner/特权链，不冒充生产 create 入口验证。
3. Java 预分配 UID 接线及 CPU/residual/health probe 仍未完成，安装权限边界本身不会消除这些已有阻塞。

当前无 root 生命周期、sandbox 现场检查或 CPU baseline 的新结果，不沿用普通用户 fixture 测试作为特权验收。

**Phase 2D-3 未完成。NOT REAL EXECUTION READY。**

## 管理员截图与前置审计包

用户随后提供 VM 终端截图：`sudo -l -U w` 显示 `(ALL : ALL) ALL`；Defaults 包括 env_reset、mail_badpass、secure_path、use_pty。`sudo visudo -c` 对 /etc/sudoers 与 /etc/sudoers.d/README 均 parsed OK。这确认 w 可用密码进行管理，不表示 SSH 获得免密 root，也没有证明新服务账号的有效权限。

已新增本地 `tools/m1-wrapper/deployment/predeploy.py`，上传至 VM 的 `/home/w/chaoslab-m1-2d3-20261004/predeploy.py`（目录 w 私有 0700）。Python 语法检查通过；普通 w 调用被正确拒绝，未执行 root 审计。语法检查在该 staging 目录生成 __pycache__，不涉及系统目录。

最终脚本 SHA-256（本地/VM 一致）：`12c56cb0f4fde01f5d66a895ec203db5fcd8581359eed73af436fbea3140459b`。

脚本不是安装器。获管理员本地认证后，只进行：

- 新建 `/var/backups/chaoslab-m1-2d3-20261004`，root 0700；保留 sudoers/账号数据库的 root-only 0600 回退副本，不打印/外传 shadow 内容。
- sudoers 基线白名单、哈希、规则数和未知规则哈希的脱敏摘要；不匹配基线即列阻塞，不自行修改。
- 候选摘要检查；固定 Docker socket 的只读 GET，检查 sandbox 完整身份、配置、no-new-privileges；全部准入属性满足时才对同一 PID/start-time/cgroup 采五个 CPU 只读点。
- root 私有原始审计报告保存在备份目录；另在 staging 下生成无密码/密钥/token 的 `predeploy-report.json` 供读取。后者不作为未来特权决策输入。
- 不创建账号、sudo 规则、wrapper、policy、binding 或 create 授权，不执行真实 Blade，不恢复快照。遇到已存在备份/报告拒绝覆盖；部分失败留待人工核查，不自动重跑/回滚。

需要用户仅在 VM 本地执行：

```bash
sudo /usr/bin/python3 -I /home/w/chaoslab-m1-2d3-20261004/predeploy.py
```

完成后再读取脱敏报告，才能确定后续安装是否存在新的阻塞。**当前仍未部署权限边界。**

## 已读取审计及 NNP 误报修正

管理员已执行前置审计。root-only 回退备份已建立，visudo 校验通过，sudo 规则符合审计基线，四个候选摘要匹配。chaoslab 账号和正式 wrapper/权限目录仍未部署。

原配置正确、旧脚本误报：实际 SecurityOpt 为 `no-new-privileges=true`，旧判断遗漏此写法。现已接受三个明确启用写法，拒绝 false、未知值、重复或冲突值；Linux 普通用户执行两项单元测试（含各子用例）通过。未修改容器配置，未覆盖原审计脚本、报告或 root 备份；修正版另存 staging/predeploy-fixed.py。

真实阻塞为容器 Running=false，尚无 baseline。用户有条件批准启动原容器，但必须先核验 Entrypoint/Cmd 等。SSH sudo 仍要求密码，已上传 inspect-idle.py：仅固定 Docker socket、完整容器 ID 的 docker inspect，输出筛选配置（包含 Healthcheck，不输出环境变量），绝不启动容器。等待管理员执行并审查 idle-inspect.json；未生成授权文件，未运行 Blade。启动及启动后验收尚未完成。

## 现有 sandbox 无害启动与 baseline 结果

用户执行只读检查后，idle-inspect.json 确认 Entrypoint=null、Cmd=[sleep,3600]、Path=sleep、Args=[3600]、Healthcheck=null。完整 ID 为 `18bb4f8734edbd6e1dc35582692677bcfcf98fef175832fc1d1e29361faab97f`，镜像为 `sha256:5291449c3df73caf6ed85e649dec1b9e818b39a5d8c871e97afc13e9cd5e8fa8`，均与原审计一致。启动前为 exited，历史 ExitCode=137，不能仅凭此认定 OOM 或退出原因。

在用户明确授权后，管理员执行 start-idle.py。脚本先重新检查固定身份、idle 命令及安全配置，再按完整 ID 启动现有容器（避免名字复用），不创建、重建、exec、update 或调整配置。读取生成的 idle-baseline.json，结果如下：

- 启动后及采样结束 Running=true，容器与镜像身份未变；非 root 65534:65534、只读根、无挂载/设备、非 privileged、CapDrop=ALL、无 CapAdd、NNP、network none 均保持。资源为 0.5 CPU、128 MiB、32 PIDs、restart=no。
- 五个 cgroup v2 样本覆盖约 4.257 秒；PID=2290、startTime=54849 与 cgroup 路径保持不变，成员只有该 sleep 进程，无嵌套 cgroup；实际 UID、NoNewPrivs、CapEff 检查通过。此结论限于采样时刻和被检查的目标 cgroup，不宣称全 VM 残留已排除。
- 网络命名空间仅 lo；未执行网络访问测试。无配置的 Docker Healthcheck，因此只证明进程/状态 baseline，不证明应用健康。
- usage_usec 五次均为 107670，四个区间单核 CPU 使用率均为 0.0%。累计 nr_throttled=1、throttled_usec=47991 在窗口内不变；累计计数不等于本次采样产生的节流。
- 未生成 create 授权、未运行真实 Blade 或压力负载。sleep 3600 会自然到期，不保证未来仍 Running，后续操作必须重新核验。

本次无害启动与 baseline **PASS**。正式服务账号、sudo wrapper 权限部署和 root 生命周期验证仍未完成；health/residual readiness 不能由此替代。**NOT REAL EXECUTION READY；REAL EXECUTION NOT AUTHORIZED。**

## 2026-10-05：权限边界安装包，等待 VM 管理员执行

用户再次明确批准实际部署，不开启真实实验。SSH 只读复查确认 chaoslab 仍不存在，正式 wrapper/目录仍不存在，sudo 仍需要密码。已准备并上传 install-boundary.py 与审核版 Linux wrapper；不利用 w 的 docker/lxd 等能力绕过认证。

审核 wrapper SHA-256：`17a7ec21e7b3e9a74a15f5bba7fb9528ade23d0683f5e5ecbeb53c8dfc8696a2`。安装脚本对该摘要硬编码验证，使用内存中的已验证字节安装，不重新从可写 staging 路径取执行内容。

该包只实施权限边界第一部分：核对原 sudo/account 回退基线；创建 chaoslab system/no-home/nologin 独立账号；建立 root-only policy/state/log 目录；安装 root:root 0755 固定 wrapper；安装经 visudo 预检的精确规则 `chaoslab ALL=(root) NOPASSWD: /usr/local/libexec/chaoslab-m1-wrapper ""`。无参数限制必须通过负向授权查询。root 所有权、父目录模式、symlink、ACL/capability 检查失败即停止。

安装后以 chaoslab 查询禁止命令的 sudo 有效授权（不是运行这些命令），验证额外 wrapper argv 被拒绝，并实际通过 sudo 执行无 policy 的 preflight，期望 IDENTITY_REJECTED。验证 w/chaoslab 均无相关目标写权限。此结果不等于 CREATE_DISABLED 的完整现场证明，更不等于 root child 生命周期通过。

新增 root-only 回退/记录位于既有备份目录：boundary-plan.json、boundary-before-subuid/subgid（存在才备份）、sudoers-chaoslab-m1.fragment、boundary-result.json。脱敏结果另写 staging/boundary-result.json。不覆盖旧报告、不自动回滚；部分失败需读报告后再处理，不重复运行安装器。

本包不部署 policy 或候选，不创建 binding/authorization，不访问 Docker、不启动真实工具；fake 特权生命周期验收后再切换正式只读候选。当前仅通过 Python 语法检查，**尚未获得 root 安装结果**。下一操作由管理员在 VM 本地认证执行已上传脚本，密码不经聊天传递。

### 安装前置检查停止及已批准的审计路径调整

管理员执行旧安装器，在任何账号/安装写入之前被 trusted(/var/log) 拒绝。现场 stat/namei 确认 /var/log 为 root:syslog 0775（gid 104），并非满足严格父目录边界的路径；不是密码错误，也不将其视为可忽略误报。未生成 boundary-result.json，账号和安装目标仍不存在。

用户批准仅将审计目录改为 /var/lib/chaoslab-m1/audit，root:root 0700；/var/lib 及其父目录现场为 root:root 0755。修正版移除不再使用的 /var/log 前置检查，但对新审计目录及所有实际安装父路径保留相同所有权、模式、ACL、capability 和 symlink 检查。不修改 /var/log，不放宽 trusted()，不变更 wrapper 二进制。旧审计/备份不覆盖；修正版上传为 install-boundary-v2.py，等待管理员执行。

### v2 安装结果：权限边界 bootstrap PASS

已读取管理员执行后的 boundary-result.json，completed=true，并通过 SSH 只读复核账号与已安装 wrapper 摘要/模式。

- chaoslab：uid=999、gid=987，仅 groups=987(chaoslab)；无 sudo/docker/lxd 等附加组。安装流程使用 system、no-create-home、/nonexistent、/usr/sbin/nologin。
- wrapper root:root 0755，摘要仍为 `17a7ec21e7b3e9a74a15f5bba7fb9528ade23d0683f5e5ecbeb53c8dfc8696a2`；安装器 owner/ACL/capability/symlink 检查通过。
- sudo -l 仅显示 `(root) NOPASSWD: /usr/local/libexec/chaoslab-m1-wrapper ""`，默认 use_pty 保留。sh/bash/docker/env/python3/systemctl/systemd-run/id/blade 的负向授权查询全部拒绝；wrapper 额外 argv 被拒绝。这是有效权限查询，不是执行这些禁止程序。
- 实际 chaoslab→sudo→wrapper 的无 policy preflight 返回 IDENTITY_REJECTED，符合预期；没有运行 privileged child，因此不能记作 root child 生命周期通过。
- w 和 chaoslab 对 wrapper、sudoers fragment 及所有新增目录的 test -w 均为 false。
- policy/state/audit 目录已建立，未创建 policy、binding 或 authorization；候选尚未部署、未执行，sandbox 未变更。

后续仍需 fake 部署与 root child 生命周期、完整 CREATE_DISABLED 重复现场验证，再进行固定真实候选部署/只读 preflight。当前 **NOT REAL EXECUTION READY；REAL EXECUTION NOT AUTHORIZED**，不是 Phase 2D-3 最终验收。

### A 阶段开始前发现的测试准入冲突（非 root 生命周期实测失败）

再次只读核对已安装 wrapper 摘要未变。现有 main.go 的 serve 在 create-cpu 路径中先调用 authorize；state.go 的 authorize 必须读取并消费 authorization.json，成功后才允许到达 runChild(create=true)。HANDOFF 不能由 status/destroy 触发。将后端 executable 换为 fake 也不会绕过此检查。

现有 runner_linux_test.go 的 HANDOFF、取消和信号用例会创建 authorization.json，不能在本轮禁止生成授权文件的条件下直接复跑。fixture_test.go 的 fixture-wrapper 是独立测试二进制入口，接受测试参数；不是当前固定、无 argv sudoers 所允许的生产二进制。不得声称普通 root 测试或替换后的测试入口等同于已安装生产链路验证。

因此，“现有生产 wrapper 全链路成功 HANDOFF”和“任何授权文件都禁止生成”不能同时满足。需用户选择是否明确允许隔离 fake-only 授权，或允许仅对同源码 runner 的独立特权 harness 验证并承认生产成功入口仍未测。未擅自增加绕过开关、修改 sudoers、替换已审核 wrapper 或生成授权。A 未通过，按用户顺序要求未开始 B/C；这不是已发现 root child 清理缺陷。

### 已批准的最小槽位/授权修订：代码测试完成，VM 替换待执行

用户随后明确批准 fake-only 授权例外及最小 wrapper 修订。实现仅在 root-owned policy 增加 deployment/executable/stateDirectory；请求 schema 不变，不接受槽位、路径或环境变量。REAL 固定为原 api3 路径和 state；FAKE 固定为 /opt/chaoslab/m1-fixture/v1/fake-blade 和 /var/lib/chaoslab-m1/fixture-state。其他路径、槽位或错配 fail closed。没有改变目标 Docker 检查、账号或 sudoers。

Binding/authorization 显式包含 deployment、executable、四项工具 SHA、policyDigest 和 expiresAt。policyDigest 是完整 Policy 结构按 Go json.Marshal 字段顺序编码的 SHA-256（语义规范化，不是源文件空白格式摘要），包括 CPU/duration/state/node/target 所有字段。FAKE TTL 上限 30 秒，独立 expiresAt 必须尚未到期且晚于 createdAt；REAL 原五分钟上限保留，但本轮没有 REAL 授权。旧缺失字段的 policy/授权不作兼容放行。single-use 仍通过 O_EXCL 持久化 binding、消费授权实现；恢复 binding 不因 expiresAt 过去而失效。dispatch 前再次加载 policy 并检查完整摘要一致。

变更检查范围：policy.go 固定槽位解析；state.go 授权完整身份/期限；main.go 槽位选择及 dispatch 前 policy 复核；fixture_test.go 新字段；deployment_test.go 新增路径/身份/期限/双向交叉授权负测。未改 Runner、请求协议、Java、sudoers 或真实工具。只读复核后认为该变更范围符合本轮授权；这不等于独立外部审计。

Linux：golang:1.25、uid1000、--network none、无 Docker socket，全套 go test 与 go vet 通过。Windows：交叉编译后原生运行 wrapper-v2.test.exe，全套可用测试通过，包括双向交叉授权、未知路径、错误 SHA、policy 变化、到期/超长/缺失期限。现有无害生命周期回归通过；尚非 VM root 生命周期证据。

新 Linux wrapper SHA-256：`00e3b03f4e88621890bce0f7abea092e09a4d13e13200637439ccf77d31a8657`，工作站与上传 VM 字节一致。已上传 upgrade-wrapper.py，语法检查通过：要求安装旧 SHA 正确且当前没有 policy/binding/authorization，备份旧二进制后原子替换，检查 sudo 内容摘要不变，再通过实际 sudo 链请求三次无 policy create（期望 IDENTITY_REJECTED）。有有效 FAKE policy 时的 CREATE_DISABLED 及 VM 交叉授权验收仍待后续执行，不能混淆。

替换只修改 wrapper 与独立备份/结果文件；不创建 policy/授权，不运行 fixture 或真实工具。管理员尚需在 VM 认证执行升级脚本；未据此宣称 PRODUCTION WRAPPER ROOT HANDOFF VERIFIED。

### VM wrapper 升级结果已核验

已读取 wrapper-upgrade-result.json：completed=true，sudoUnchanged=true。SSH 独立读取已安装二进制摘要为 `00e3b03f4e88621890bce0f7abea092e09a4d13e13200637439ccf77d31a8657`，与测试/上传版本一致。实际 chaoslab→sudo→root wrapper 三次无 policy create 均返回 IDENTITY_REJECTED，cleanupComplete=false、handoff=false；shell/docker/env/python/systemctl/systemd-run 负向授权查询均拒绝。未创建 policy 或 authorization。

注意：该结果只证明升级、sudo 边界保持和缺少部署身份时拒绝请求。有效 FAKE policy 下 CREATE_DISABLED、VM 双向交叉授权、root child HANDOFF/失败清理与终止场景尚未验证，不把本次报告当作 A 完成。真实候选仍未部署，B/C 未开始。

进度口径：Phase 2D-3 暂估约 50%，仅为工作项估算而非安全评分。已完成回退审计、sandbox 历史只读 baseline、服务账号、sudo/文件边界及 wrapper 槽位升级；主要剩余 fake 部署与特权生命周期、真实候选/正式 policy 部署、真实只读 preflight 及最终 Gate/探针/Java UID 接线核验。旧 sleep/baseline 不视为当前实时就绪证据。NOT REAL EXECUTION READY。

## 2026-10-05 M1 收尾执行记录（未完成）

已构建并上传独立无害 Go fake executable，SHA-256 `3c9ed3e06b629297d503cfe2659604f1c74459ad226dd6d8b3feff50fbc33dc9`。仅 sleep/固定 JSON/有限输出，不访问 Docker 或执行真实工具。fake-root-suite.py 使用实际 runuser chaoslab→sudo -n→已安装 wrapper，准备了 HANDOFF、detached、失败清理、STRICT 和信号场景，绑定完整 FAKE policy 的 15 秒授权，退出清理仅针对固定 fake 文件/已知 pidfd。

管理员执行结果：`passed=false`、`tests=[]`、`blocker="fake preflight failed"`、`authorizationAbsent=true`、`realAuthorizationCreated=false`、`realBladeInvoked=false`。已经建立 FAKE 文件、fixture-state、FAKE policy 与节点标记；尚未进入授权/child 测试。没有 A 的通过证据，因此没有部署真实候选。禁止重复运行该初始化脚本覆盖已有现场；下一步只读取 preflight 拒绝码定位原因。已请求管理员执行只读 preflight 命令，尚未收到输出。该失败不是 root 生命周期实测失败。

Java 本地改动：CSPRNG 8 字节/16 位 lowercase hex UID，在 CRI intent INSERT 同一 REQUIRES_NEW 事务持久化，commit 后再次检查目标；dispatch 携带同 UID，响应不同即 CREATE_UNCERTAIN，不再 create 后 recordUid。复用原 blade_uid 列，不修改迁移；旧 DOCKER_CPU_V1 读取保留。增加独立连接可见 UID、外层回滚后 UID 仍存在、响应 UID 不匹配和 RUNNING 结果提交失败测试。

生产 Channel 已改为固定 /usr/bin/sudo -n -- /usr/local/libexec/chaoslab-m1-wrapper，以有界 stdin 传 operation/executionId/nativeUid；调用方不传 executable/target/path。只有完整合格 wrapper 成功 envelope 才转换为 HANDOFF/EXITED，Java 进程清理结果不能当作 root child cleanup 证据。现有直接工具通道仅保留无害测试/旧契约构造。该接线尚未完成现场 root policy 与 Java intent 身份一致性确认；本地 identity verifier 仍直接读取 root-only 状态路径，普通 chaoslab 无权读取，因此当前仍 fail closed，不能宣布生产可执行。后续需在既有只读 preflight 中完成可信身份对照，不应开放状态目录权限。

验证：Windows JDK24 + Maven wrapper3.9.16 完整 verify 退出0，345项、0失败、0错误、1跳过。Linux Corretto21/Maven3.9.16 在非 root、--network none、无 Docker socket、源和依赖只读挂载的临时副本完成 verify，退出0。首次旧 Maven3.8.8 离线运行因镜像/旧插件依赖未缓存失败，改用项目锁定 Maven 版本后通过，未为测试打开网络。

仍阻塞 M1：fake root preflight 拒绝未定位；A 未通过故 B/C 未执行；Java/root policy 身份对照及服务账号只读 identity 路径未接通；CPU/residual/health probes 与最终 Validator/Gate 主流程未实现；CREATE_UNCERTAIN 在平台 destroy 入口仍被拒绝，虽然 native UID 已可从 journal 找回，尚不能称为完整恢复闭环。未修改最终恢复状态流来制造 SUCCESS。当前 NOT REAL EXECUTION READY，无 REAL authorization，无真实故障。
