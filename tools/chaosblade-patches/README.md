# ChaosBlade 实验性补丁（api2）

仅用于独立 Linux 靶场研究。不是官方发布版，不启用 ChaosLab 真实引擎，不构成注入许可。

## 源码基线与应用

将两个仓库克隆为相邻目录，目录名必须为 `chaosblade-patched` 和
`chaosblade-cri-patched`（CLI 的本地 module replace 使用该相对目录）：

- https://github.com/chaosblade-io/chaosblade ：`e8e0d3adc90c414d9dc9a04a13f53ebadfbda8dc`
- https://github.com/chaosblade-io/chaosblade-exec-cri ：`fc16e67d2ca469a37c9ec98878fe0f618a0712d9`

两者均来自 v1.8.1 标签。分别在干净源码中先执行 `git apply --check`，
再应用本目录的 `cli-api2.patch` 和 `cri-api2.patch`。不覆盖官方安装目录。

## 补丁范围

- Docker SDK 自动协商 API，握手失败或缺少版本信息时拒绝继续。
- 同步 create 的延迟恢复使用当前可执行文件，动态参数不拼接成 shell 源码。
- cgroup v2 仅接受已存在、非根、无符号链接的规范路径；加载失败直接返回，
  不创建 cgroup，不回退到宿主机根 cgroup。
- 开发构建标识；版本号保留 1.8.1 用于找到配套 YAML。
- 独立只读探针仅执行 Docker Ping 和 ContainerList。

## 验证与构建

在 Linux Go 1.25 环境中，从 CLI 仓库运行（建议限制构建并发）：

```sh
go test cli/cmd/recovery_command.go cli/cmd/recovery_command_test.go -v
go test -p 2 github.com/chaosblade-io/chaosblade-exec-cri/exec github.com/chaosblade-io/chaosblade-exec-cri/exec/container/docker -run 'TestTargetCgroupGuard|TestNegotiatedContainerList|TestHandshakeFailure' -v
CGO_ENABLED=0 go build -p 2 -ldflags '-X github.com/chaosblade-io/chaosblade-exec-cri/version.BladeVersion=1.8.1 -X github.com/chaosblade-io/chaosblade-exec-os/version.BladeVersion=1.8.1' -o blade-chaoslab-api2 ./cli
```

候选程序需要官方 v1.8.1 Linux amd64 包的 bin/yaml 等配套资源，放在独立目录，
保留官方 blade 文件。官方包 SHA-256：
`cab903ef50b04fca56df16af27acc13fcd92e93f1798c8f3e2967b71f6436fe7`。

2026-10-03 验证记录：上述定向测试通过；候选程序在 Ubuntu 上的 version/help
通过；api1 的同一 API 补丁只读探针在 Docker 29.8.2 上握手和查询通过。
整个 cli/cmd 包测试被上游初始化解析测试参数的问题阻挡，不能声明全量通过。
项目 GitHub Actions 不等同于独立补丁的 Linux 集成验收。

## 仍然阻止真实注入的问题

- 定时恢复仍是非持久化后台 shell；宿主机重启和计时进程丢失未闭环。
- nsexec 进程先启动再加入 cgroup，但 `-s` 会在执行负载前等待 SIGCONT，
  不能把此顺序直接解释为“负载先于隔离启动”。信号握手竞态、异常退出清理、
  PID 复用/目标删除仍未完成验证。
- 异步 create 保留上游路径逻辑，不得使用。
- 尚未验证真实 create/status/destroy 响应、限时恢复和残留清理。
- ChaosLab 平台仍使用 Fake 引擎，现有 docker 命令/解析器未迁移至 cri 方言。

不上传虚拟机磁盘、镜像、SSH 密钥、可执行文件或私有环境日志。

### 恢复定时器追加验证

`TestRecoveryTimerExecutesExactCandidate` 在 Linux Go 容器内连续三次通过。
假程序路径包含空格和引号，旁边放置同名 blade 干扰程序；约 2 秒后仅指定
假程序收到 `destroy` 和原 UID。测试未调用 Docker、ChaosBlade 或真实负载。
这只验证正常定时调用，不证明机器重启、定时进程被杀、目标消失后能恢复。

暂停协议的源码依据：[v1.8.1 nsexec.c](https://github.com/chaosblade-io/chaosblade/blob/v1.8.1/nsexec.c)。
其中先设置进程名，再安装信号处理器和调用 pause；父进程仅凭进程名判断就绪，
仍可能存在过早发信号的窗口，必须进一步测试，不能据此宣布注入安全。

### nsexec 握手修补增量（尚未部署到虚拟机）

在 CLI 源码应用 api2 后，再应用 `nsexec-handshake.patch`。Linux 路径改为：
先阻塞 SIGCONT → 发布 pause 进程名 → sigwait 接收信号 → 恢复进程名与信号掩码。
这样就绪之后、等待之前到达的信号不会丢失。非 Linux 路径保留原行为。

在带 GCC 的 Linux Go 环境、CLI 源码目录运行：

```sh
go test nsexec_handshake_test.go -count=3 -v
```

2026-10-03：三轮、每轮 50 次，共 150 次通过。测试只运行 /bin/true，
不传命名空间切换参数、不操作 cgroup、不执行注入。这是握手回归测试，
不是资源隔离、目标退出、宿主机重启或真实恢复验收。尚未替换发布包的 bin/nsexec。
下一步仍需验证异常分支能回收辅助进程，以及 PID/目标身份变化时拒绝执行。

### api3 源码候选：有界握手与直接子进程回收（未部署）

`cri-api3-candidate.patch` 是相对于同一 exec-cri 官方基线的完整替代补丁，
不能叠加到 `cri-api2.patch` 上。必须配套 CLI 的 `nsexec-handshake.patch`，
不能搭配未修补的官方 nsexec；当前虚拟机 api2 未替换。

握手共用 2 秒预算，取消原先无限轮询与重复发信号；失败出口尝试杀死并 Wait
直接辅助进程，回收等待上限 2 秒，未确认则记录日志，不声称已恢复。
Linux 下 cgroup guard、未就绪超时回收、缺失进程三个测试连续三轮通过。
运行方式：`go test github.com/chaosblade-io/chaosblade-exec-cri/exec -run 'TestHandshake|TestTargetCgroupGuard' -count=3 -v`。

未完成：后代进程回收、目标 PID 启动时间/容器身份重新核验、负载启动后失败的
恢复证据、CLI 中断和系统重启恢复。上述测试没有执行故障或命名空间切换。

api3 追加目标进程身份复核：启动辅助进程前记录 /proc/PID/stat 的 starttime
与 cgroup，发送 SIGCONT 前再次读取比较；拒绝退出目标、僵尸进程、变化的
启动时间和 cgroup。stat 读取前后也比较启动时间，解析兼容含括号的进程名。
六项测试（cgroup、握手超时回收、缺失进程、身份变化、目标退出、stat 解析）
连续三轮通过。身份变化测试使用合成不匹配快照，不是真实强制 PID 复用实验。
该复核尚非原子操作，不能阻止检查后再退出/复用，也没有再次核对 Docker
容器 ID 和镜像 ID；后代进程清理仍未完成。虚拟机中的 api2 未替换。

api3 后续增量：辅助进程使用独立进程组。失败清理先核对 PGID 等于尚未回收的
辅助进程 PID，再终止该进程组并 Wait 直接子进程。七项无害测试连续三轮通过，
新增测试验证同组 sleep 子进程停止运行（允许等待 PID 1 回收的僵尸状态）。
这不证明主动 setsid/setpgid 脱离的后代已清理，也不等同于故障恢复确认；
不能据此释放平台目标占用。尚未部署此增量。

负向验收追加：`TestEscapedDescendantIsOutsideGroupCleanup` 在隔离测试容器中
创建 setsid + sleep 后代，确认组清理返回 nil 时该后代仍可能存活，再由测试
终止其已知 PID。使用 --init 回收孤儿；不操作真实故障或虚拟机目标。
八项测试连续三轮通过，其中该负向测试通过表示限制被成功复现，不是限制已修复。
实际执行仍禁止；需独立的执行范围约束与恢复状态核验，不能用进程组清理结果
替代引擎状态、目标身份及稳态恢复证据。
