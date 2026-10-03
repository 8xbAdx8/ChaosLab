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
- 故障进程先启动再加入 cgroup，存在隔离窗口；PID 复用/目标删除竞态未解决。
- 异步 create 保留上游路径逻辑，不得使用。
- 尚未验证真实 create/status/destroy 响应、限时恢复和残留清理。
- ChaosLab 平台仍使用 Fake 引擎，现有 docker 命令/解析器未迁移至 cri 方言。

不上传虚拟机磁盘、镜像、SSH 密钥、可执行文件或私有环境日志。
