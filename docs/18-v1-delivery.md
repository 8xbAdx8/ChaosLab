# V1 本地交付：Windows / Ubuntu / macOS

目标是复现 **Fake 平台和只读历史展示**，不是重建真实故障现场。无需 Docker、VMware、MySQL、sudo 或 ChaosBlade。

## 环境

- 完整 JDK 21+；CI 固定 Java 21。本地建议 21，`JAVA_HOME` 指向 JDK，不是 `bin` 或 JRE。
- Node.js 22.12+（建议 24），包括 npm。
- Git；Linux/macOS 使用 Bash。首次构建需要网络下载 Maven 和锁定的 npm 依赖。
- 将仓库克隆到自己的本地用户目录；不要放共享目录、符号链接目录或网络盘。

Java 17 会在启动前失败，不会迁移数据库。脚本不替你安装软件、修改系统环境或删除数据。
在 Windows 当前终端设置环境（不是永久修改系统）：

```powershell
$env:JAVA_HOME='C:/path/to/your/jdk-21'
$env:PATH="$env:JAVA_HOME/bin;$env:PATH"
```

Ubuntu/macOS 根据自己的 JDK 安装路径设置 `JAVA_HOME`；macOS 可使用 `/usr/libexec/java_home -v 21`。

## 最快启动

在仓库根目录：

```powershell
.\scripts\start-fake.ps1
```

```bash
bash scripts/start-fake.sh
```

第一次会检查环境、用 Maven Wrapper 构建当前源码、`npm ci`，然后引导账号配置。

Windows 若默认脚本执行策略阻止 `.ps1`，在审阅源码后可用：
`powershell.exe -NoProfile -ExecutionPolicy RemoteSigned -File .\scripts\start-fake.ps1`。
历史、doctor、stop 可替换同一命令中的脚本名。这仅作用于新启动的 PowerShell 进程，
不写系统策略，不能越过组织 Group Policy；不要使用全局 `Set-ExecutionPolicy Unrestricted`。
见 [PowerShell 执行策略与 Process scope](https://learn.microsoft.com/en-us/powershell/module/microsoft.powershell.core/about/about_execution_policies)。
没有默认 ADMIN、VIEWER 或密码。账号名由你选择：1–64 位字母/数字/点/下划线/横线。
已有 Java `PasswordHashTool` 会两次读取**不回显**的密码（至少 12 字符，UTF-8 最多 72 字节），
输出 BCrypt hash。将 hash 粘贴回脚本提示，**不要粘贴明文密码**。可再配置独立 VIEWER。
配置保存于 `.local/dev/accounts.json`；仅包含用户名和 hash，不是默认生产凭据。

后续启动直接复用私有配置，不再问密码。每次启动构建当前源码，首次下载较慢；依赖失败不会启动服务。
打开输出的 `http://127.0.0.1:5173/login`，输入你自己的账号/密码。
启动终端保持打开；它管理两项直接子进程，而不是安装后台系统服务。

已有账号可先检查：

```powershell
.\scripts\doctor.ps1
```

```bash
bash scripts/doctor.sh fake
```

无账号时 doctor 会明确拒绝；首次使用直接运行 start-fake 完成引导。单独配置也可以：
`node scripts/dev.mjs configure fake`。不会覆盖已有账号文件。
更换账号前先停止服务，将旧 `accounts.json` 手动移到自己的安全归档位置，再重新配置；不要把 hash 提交 Git。

## 停止与历史模式

按启动终端 **Ctrl+C**，或另开终端：

```powershell
.\scripts\stop-dev.ps1
.\scripts\start-history.ps1
```

```bash
bash scripts/stop-dev.sh
bash scripts/start-history.sh
```

打开 `http://127.0.0.1:5173/history/m1-core`。这是匿名、无数据库的独立启动入口，
固定只读归档、SHA 校验；不是 Fake 数据库中的记录，也不是实时监控。历史模式无需 accounts 文件。
默认 Fake/history 互斥，因为共享本地端口和同一管理会话；切换前停止。

端口冲突不自动杀进程或静默换端口。明确选择一对新端口：

```powershell
.\scripts\start-fake.ps1 -BackendPort 18080 -FrontendPort 15173
```

```bash
bash scripts/start-fake.sh --backend-port 18080 --frontend-port 15173
```

代理固定连接本次后端的 127.0.0.1 端口。没有 VM IP、任意 URL、引擎或数据库参数。

## 数据与进程边界

- H2 文件固定为 `.local/chaoslab-fake-demo/chaoslab_fake_demo.mv.db`；停止、重启不清空数据。
- 私有账号、管理会话、日志在 `.local/dev/`，均被 Git 忽略。不要上传该目录或完整运行日志。
- Unix 私有目录 0700、文件 0600；脚本拒绝不安全现有权限和 symlink。
- Windows 的 Node `mode` **不能等价替代 NTFS ACL**。在私人用户目录使用继承的账户 ACL；
  共享机器上先用目录“属性 → 安全”只授权当前用户/SYSTEM/管理员，再运行。脚本不自行改变系统 ACL。
- 停止通过随机 token + session identity 的 loopback 管理端口，请求**还活着的启动会话**停止其直接 Java/Vite child。
  不读取保存的 PID 来 kill，不使用 taskkill/pkill/killall，不影响另一项目。
- 正常停止等待后端最多 21 秒；超过后仅终止本会话直接 child。日志、数据库保留。
- Unix 发 SIGTERM；Windows 的 Node child termination 是立即终止，不保证 JVM shutdown hook。
  因此 Fake 专用 H2 固定 `WRITE_DELAY=0`，提交时刷写，不靠任意 sleep 等待默认 500ms 延迟。
  已提交的执行/报告在立即停止后可读回；正在进行、尚未提交的请求不保证完成，也不自动重试。
  依据：[Node child termination](https://nodejs.org/api/child_process.html#subprocesskillsignal)、
  [H2 WRITE_DELAY](https://h2database.com/html/commands.html#set_write_delay)。没有新增停机业务 API、迁移或现场数据库操作。
- 部分启动失败或子服务退出会停止已启动的伴随服务，无自动重试。
- 构建/首次账号阶段尚未创建服务会话，stop-dev 会明确提示回原终端 Ctrl+C，不会谎称取消了启动。
  原子 start.lock 防止并发构建/安装；异常断电留下 stale lock 时，核对原终端后手动归档锁文件。
- 若终端被强制杀死/断电，管理器无法保证善后。stop 会拒绝 stale session，不猜 PID。
  手动核对服务所属命令/工作区后再停止它，并归档 stale `session.json`；不要批量杀 Java/Node。
- 启动拒绝 Spring/ChaosLab/Java/Node/Vite 外部覆盖变量和本地 `.env*`，防止沿用现场 DB、真实引擎、不同代理或公网地址。
  使用干净终端/专用克隆；错误不回显变量值。

这些仍是**本地开发工具**，不是生产部署/秘密管理系统；拥有同一 OS 账户或管理员权限的人不在隔离威胁模型内。

## 可重复验收

正常开发者用自己的账号照演示指南操作。自动化使用**测试类路径**上的 `DeliveryTestAccounts` 随机临时凭据；
工具不会进入交付 JAR，不创建真实部署默认密码。必须在独立干净测试 checkout 执行，拒绝复用个人账号：

```powershell
.\backend\mvnw.cmd -f backend/pom.xml verify
node --test scripts/dev.test.mjs
node scripts/delivery-smoke.mjs
```

```bash
bash backend/mvnw -f backend/pom.xml verify
node --test scripts/dev.test.mjs
node scripts/delivery-smoke.mjs
```

smoke 调用实际 PS/Bash 启动入口，使用 packaged Spring JAR + Vite：ADMIN 登录、CSRF、创建/校验/预检、
幂等启动、停止、真实持久化报告、VIEWER 403、注销失效、H2 重启读回、切换无 DB 历史模式、SHA 和受控停止。
测试记录只属于本地 Fake 演示，绝不是现场 M1 证据。日志和临时测试凭据仅在忽略目录。
已有 Java、Web、root harmless、历史 SHA CI 均保留；新增 V1 Delivery CI 在 Windows/Ubuntu、Java 21/Node 24 运行。
macOS 提供 Bash 入口，但没有 macOS CI，不能宣称已完成该平台验收。

### Windows 测试夹具的边界

Hosted Windows 上，原来承诺“没有后代”的三个短 JVM 夹具偶发得到 DESCENDANTS_REMAINED；同一源码也有通过记录。
夹具本身未创建业务 child，具体原生后代类别未保存，不能宣称已确认是某个系统进程。
现在仅这三个测试在 Windows 使用无关联控制台的 javaw（[Java 21 官方契约](https://docs.oracle.com/en/java/javase/21/docs/specs/man/java.html#javaw)），
仍检查精确 argv、独立 stdout/stderr、工作目录、环境、exit=7 和 EXITED；没有改真实 Runner 或放宽清理断言。
明确启动 helper/后代的测试继续使用 java.exe，STRICT 清理、timeout、HANDOFF 和失败路径全部保留。

## 遇到问题

| 提示                   | 处理                                                                  |
| ---------------------- | --------------------------------------------------------------------- |
| JAVA_HOME / JDK        | 安装完整 JDK 21，修正当前终端 JAVA_HOME                               |
| Unsafe overrides       | 开新干净终端，不沿用现场部署环境变量；移走 Vite 私有 .env 配置        |
| Port occupied          | 先确认端口所属服务；停止它或指定新端口                                |
| Accounts invalid       | 仅粘贴 BCrypt hash；ADMIN/VIEWER 不同名，不能写明文密码               |
| Build/tool failed      | 查看构建输出，检查 Maven/npm 网络；不会启动半成品                     |
| Service timeout/exited | 查看私人 `.local/dev/backend.log`、`frontend.log`；不要将完整日志公开 |
| Session unavailable    | 核对原启动终端/进程身份，不删除数据库，不批量杀进程                   |

## 工程学习要点

Maven Wrapper 固定 Maven 版本，别人无需安装另一套 Maven；它不替代 Java，也不保证离线首次下载。
Profile 是部署边界：fake-demo 加载实际 Fake，history-demo 的独立 launcher 排除 DB/引擎，console 精确只读。
H2 是本地文件数据库，易演示但不等于 MySQL 生产验收；MySQL 的权限/锁/部署要独立验证。
环境检查让错误发生在服务和迁移之前。CI 在干净 Windows/Ubuntu 重做测试；本地测试同时验证你的具体环境。
“可复现交付”指源码、依赖版本、启动路径和可核对结果清楚，不代表自带默认密码或一键开放真实故障。
