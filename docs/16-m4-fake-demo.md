# M4：完整 Fake 实验交互闭环

基线：PR #5 已合并，main `9e3c8bdb8f4668d106fe310bfb8575ae29dbf187`。
本版不操作 VM，不调用真实 Blade，不改变 wrapper/sudoers/真实策略，不修改 R1/R2/R3 或已有迁移。

## 运行模式与边界

只有后端 `fake-demo` profile 的明确 `FAKE_DEMO` 模式允许模拟写操作：

- 实际注册对象必须是 `FakeChaosEngine`，不是仅检查一个配置字符串。Blade 配置在该 profile 下不加载。
- ADMIN + 有效 Session + CSRF + loopback 请求 + 精确业务路径同时成立才放行。
- VIEWER 能读，但直连后端创建、编辑、校验、启动、停止、生成报告均为 403。
- ADMIN 不具有真实执行、REAL authorization、Docker/root 或修改目标权限。
- `console` 保持精确 GET/HEAD 白名单、受控 OPTIONS，业务写仍全部拒绝；保留 SELECT-only MySQL 用户建议。
- `history-demo` 仍为匿名本地历史身份，仅固定归档，不需要账号、数据库、Docker 或 VM。
- 后端 bind `127.0.0.1`，本机 HTTP Cookie 为 HttpOnly/SameSite=Lax；Session/CSRF 沿用 M3。
  仅用于本地教学，不可直接公网暴露。

### 独立、持久化的演示数据库

为让本地演示不需要安装数据库，使用 **H2 file（MySQL compatibility mode）**，不是临时内存 mock。
它由现有 JPA Repository 和 Flyway V1–V11 操作，记录刷新/重启后仍存在。
**这是独立本地 Fake 功能验收，不是正式 MySQL 或真实 M1 部署验收。** 原 MySQL 生产配置不改。

在 `backend` 目录运行时默认文件位置：`.local/chaoslab-fake-demo/chaoslab_fake_demo.mv.db`。
目录/数据库文件已加入 Git ignore。只运行一个进程，不将该目录共享给其他实例。
可设置 `CHAOSLAB_FAKE_DEMO_DIRECTORY` 指向专门目录；末级必须叫 `chaoslab-fake-demo`，
Windows 路径使用 `/`。不要指向其他数据目录，不把数据库文件提交 Git。

启动前验证 exact H2 file URL、driver、目录与模式；拒绝 MySQL/网络 URL、另设 Flyway URL、
JNDI/Hikari URL 替代、symlink、console/history overlap、真实 engine、外部 binding script。
校验在 DataSource/Flyway bean 建立前完成，不会先迁移其他数据库再拒绝。
普通 OS 用户运行，不需要提权或真实工具。

## 两终端启动

依赖 Java 21、Node 22.12+（建议 24）。仓库根先构建：

```powershell
.\backend\mvnw.cmd -f backend/pom.xml verify
```

生成自己的 BCrypt hash（交互密码不回显，不接受 argv 密码）：

```powershell
java -Dloader.main=com.chaoslab.console.security.PasswordHashTool -cp backend/target/chaoslab-backend-1.0.0-rc1.jar org.springframework.boot.loader.launch.PropertiesLauncher
```

终端 A 配置受信本地账号，没有默认账号/密码。可仅配置 ADMIN；验证 VIEWER 时再生成独立 hash：

```powershell
$env:CHAOSLAB_ADMIN_USERNAME='your-admin-name'
$env:CHAOSLAB_ADMIN_PASSWORD_HASH=Read-Host 'Paste your locally generated ADMIN BCrypt hash'
# 可选：配置只读账号；不要与 ADMIN 重名
$env:CHAOSLAB_VIEWER_USERNAME='your-viewer-name'
$env:CHAOSLAB_VIEWER_PASSWORD_HASH=Read-Host 'Paste your locally generated VIEWER BCrypt hash'
cd backend
java -jar target/chaoslab-backend-1.0.0-rc1.jar --spring.profiles.active=fake-demo
```

保持工作目录一致，避免相对路径创建第二个演示数据库。无需设置现场 MySQL 凭据。
可选 `CHAOSLAB_FAKE_DEMO_DB_PASSWORD` 仅用于自己的嵌入式数据库，不是登录密码；启用后重启必须使用同一值。
登录 hash、数据库密码都不要提交仓库、放进 URL/命令参数或截图。

终端 B：

```powershell
cd frontend
npm ci
npm run dev:fake
```

打开 `http://127.0.0.1:5173/login`。Vite 默认代理到本机 8080；换端口时设置受信
`API_PROXY_TARGET`，不硬编码 VM IP。`dev:fake` 只增加精确 Fake 业务路径，服务端仍最终授权。
普通 `npm run dev`/preview 默认仅允许原只读和登录/退出路径，不是 Fake 写入口。

## 亲自完成一次实验

1. ADMIN 登录，确认页面显示「FAKE 模拟实验 · 不执行真实故障」。
2. 实验管理 → 创建 Fake 实验；选固定 classroom target 与 CPU Load。
3. 输入名称/假设、持续 10 秒、参数 `{"percent":10}`，保存 CREATED 草稿。
4. 需要时「编辑草稿」；仅 CREATED 可编辑，带 version 防止覆盖其他修改。
5. 「校验参数」→ VALIDATED；「安全预检 / Dry-run」→ READY，查看真实服务检查结果。
6. 「启动 Fake 实验」并二次确认。页面跳转到已持久化 Execution，状态 RUNNING。
7. 可以立即「停止 Fake 实验」并确认；也可等待原自动恢复任务在持续时间后处理。
8. 查看 SUCCESS、模拟执行审计、实际保存时间线；它只表示模拟流程完成。
9. 「生成模拟报告」，查看 SIMULATED、NOT_COLLECTED、NOT_VERIFIED、INSUFFICIENT_DATA。
   没有真实 CPU/P95/物理恢复指标，不填假零值。
10. 刷新、退出重新登录，实验/执行/报告/审计仍来自本地持久化记录。VIEWER 可查看，但没有操作入口。

NETWORK_DELAY 使用 `{"delayMs":100}`，同样仅模拟，不调用网络工具。
参数由原场景 Schema 校验；CPU percent 为 10–80 整数，delayMs 为 10–3000 整数，
平台安全持续时间为 5–30 秒；非法参数/状态不绕过服务进入执行。

## API 与复用

| API | 行为 |
| --- | --- |
| GET `/api/v1/targets`、`/api/v1/scenarios` | 已有目标/场景读取 |
| POST `/api/v1/experiments` | 已有创建服务，FAKE_DEMO ADMIN-only |
| PUT `/api/v1/experiments/{id}` | 本轮唯一新增业务路由：`{definition,version}`，CREATED-only |
| POST `/api/v1/experiments/{id}/validation` | 已有场景 Schema 验证 |
| POST `/api/v1/experiments/{id}/dry-run` | 已有 SafetyGuard/状态机，返回 checks |
| POST `/api/v1/experiments/{id}/executions` | 已有执行事务/占用/幂等，需要 Idempotency-Key |
| POST `/api/v1/experiments/{id}/executions/{executionId}/destroy` | 已有审计恢复服务，仅 Fake |
| POST `/api/v1/experiments/{id}/executions/{executionId}/reports` | 已有持久化报告，需要 Idempotency-Key |
| GET `/api/v1/console/...` | 原分页实验/执行/公开审计/报告/证据投影 |
| GET `/api/v1/auth/me` | 增量声明 executionMode/fakeExecutionAllowed；realExecutionAllowed 始终 false |

没有第二套执行器、数据库表、迁移、报告框架或恢复调度器。创建/编辑/校验/预检增加 Fake 审计；
原 start/destroy/automatic/report 审计继续复用，actor 为真实登录用户名，参数按原公开字段白名单脱敏。

## 状态、幂等与重启

前端只读服务端状态；不能把错误改成 SUCCESS。写请求不自动重试，有 busy guard 和二次确认。
启动/报告的单次意图键存 sessionStorage，按账号/实验/操作隔离，刷新与响应未知时保留同键。
仅保存非敏感 ID，不保存密码、Session、CSRF。相同键并发只能返回同一持久化执行；另一个键
在占用中被拒绝，重复停止返回原终态。2 秒有界轮询，页面打开后最多 90 秒，必要时手动刷新。

Fake 引擎本身是内存状态，启动时在调度任务之前只将 **已提交 RUNNING、精确 fake-ID、
实验状态一致** 的记录恢复到模拟 map，不重新调用 create。文件数据库不被重置。
PREPARING/CREATE_UNCERTAIN/DESTROYING 或恢复失败不自动冒充成功；崩溃在不确定窗口仍保留占用，
需要单独人工诊断。本版没有清空数据库、强制释放占用或 REAL 控制按钮。

## 学习这条链路

- **Controller** 接收路由/DTO，做输入校验和安全审计；不直接改实验 SQL 状态。
- **Service** 执行 Schema/SafetyGuard、状态机、事务、占用和引擎调用，决定是否能启动/恢复。
- **Repository** 将业务对象读写数据库；version/事务/唯一约束参与并发保护。
- **Spring Security** 先检查 Session、CSRF、角色和精确路径，再确认部署真的为 FAKE_DEMO + Fake 引擎。
  ADMIN 本身不够；改请求参数或 URL 不能切换到真实引擎。
- **CSRF** 防止其他网页借用浏览器自动携带的 Session Cookie 偷发启动/停止请求。
  登录后每次修改前取新鲜 masked token，缺少它服务端拒绝。沿用 Spring 标准配置，不关闭 CSRF。
- **FakeChaosEngine** 只维护模拟状态，模拟结果不是物理证据；ChaosBladeEngine 才涉及受信工具/UID/
  root 边界/真实探针，本演示模式不加载它。
- **幂等键** 是同一次“启动意图”的标识；数据库和占用事务阻止重复 dispatch，不仅靠按钮禁用。

## 验证与已知范围

`FakeDemoApiTests` 走生产 Security 和 persistent H2：完整 ADMIN/编辑 version、
三线程同键启动、重复停止、自动恢复、审计 principal、报告独立维度、VIEWER 403、CSRF、未知 engine
输入、REAL/root 路由拒绝、已提交 RUNNING 恢复。`FakeDemoFailureTests` 保留 CREATE_UNCERTAIN
占用、同键不 redispatch、错误脱敏。配置测试拒绝错误 engine/DB/mode/路径。
原 console/auth/history/Java/root harmless 测试保留；跨语言测试消费实际 Spring Fake DTO，不依赖页面 mock。

```powershell
.\backend\mvnw.cmd -f backend/pom.xml verify
python backend/scripts/check_auth_artifact.py
cd frontend
$env:CONSOLE_CONTRACT_DIR='../backend/target'
npm test
npm run lint
npm run build
npm run build:history
```

本轮本地浏览器还验证 ADMIN 创建→校验→预检→启动→主动停止→模拟报告→刷新，
再以 VIEWER 读取同记录但无管理入口。截图为本地测试产物，不是历史 M1 证据。
M4 不提供账号 CRUD、多租户、远程执行、真实故障、WebSocket、多节点或 Fake 物理指标。
正式 MySQL Fake 部署、崩溃不确定流程管理和更细权限作为后续独立工作，不在本地演示中伪称完成。
