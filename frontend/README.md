# ChaosLab Web Console

React + TypeScript（strict）+ Vite + Tailwind CSS + React Router + TanStack Query + ECharts。
普通 console 只读；[M4 fake-demo](../docs/16-m4-fake-demo.md) 增加 ADMIN 本地模拟创建/编辑/校验/启动/停止/报告。
没有真实 create/destroy、授权生成或 root 命令入口。不连接 VM，不自动导入历史证据。

## 操作本地 Fake 实验

按 [M4 启动说明](../docs/16-m4-fake-demo.md) 配置自己的 BCrypt 账号并启动 `fake-demo` 后端，
然后 `npm ci`、`npm run dev:fake`，访问 `/login`。无需数据库安装，使用独立可写 H2 文件数据库。
ADMIN 完成创建→校验→预检→启动→主动停止或自动恢复→SIMULATED 报告；VIEWER 仅查看。
操作必须经过服务端实际模式、引擎、角色与 CSRF 校验；前端 mode 本身不是授权。
报告缺失的指标保持 NOT_COLLECTED/INSUFFICIENT_DATA，不生成物理恢复或 CPU 曲线。

普通控制台已接入 [M3 会话登录与只读 RBAC](../docs/15-m3-console-auth.md)。访问 `/login`，
VIEWER 读取固定工作区公开数据，ADMIN 额外读取权限摘要，二者均不能执行故障。
启动前按 M3 说明配置受信 BCrypt 账号；没有默认账号或密码。M2 历史演示仍无需账号/数据库，
明确显示“本地历史演示 · 非登录身份”。

## 直接查看真实 M1 Core 历史（无需数据库）

使用独立 SHA 校验只读证据投影，不导入/伪造业务表。
按 [M2 本地启动说明](../docs/14-m2-history-demo.md) 启动历史后端，再运行
`npm ci`、`npm run dev:history`，打开 `http://127.0.0.1:5173/history/m1-core`。
页面明确为「历史真实实验 · 已结束 · 非实时监控」，展示真实 CPU 三阶段、恢复回合、
审计与来源。无需 MySQL、Docker、root wrapper 或 VM；普通数据库控制台的启动方式仍如下。

## 启动前端

Node.js 22.12+（推荐 24 LTS）。在仓库的 `frontend` 目录：

```bash
npm ci
npm run dev
```

打开 `http://127.0.0.1:5173`。Vite 默认代理至 `http://127.0.0.1:8080`。
需要换后端时，将 `.env.example` 复制为本机 `.env.local`，修改 `API_PROXY_TARGET`。
默认 `VITE_API_BASE_URL` 为空，浏览器请求同源；不硬编码 VM 地址。
跨源直连只用于受控的、有服务端鉴权和 CORS 配置的只读网关，不能绕过安全部署要求。
前端 env 都可能被浏览器读到，**不得放密码/token/root 配置**。

## 启动后端只读模式

使用本机**已经完成 V1–V11 的专用数据库**和仅 SELECT 权限的数据库用户，设置项目既有
`CHAOSLAB_DB_URL` / `CHAOSLAB_DB_USERNAME` / `CHAOSLAB_DB_PASSWORD` 环境变量。
Java 21：

```bash
# 仓库根目录，Linux/macOS
bash backend/mvnw -f backend/pom.xml spring-boot:run -Dspring-boot.run.profiles=console
```

```powershell
# Windows，仓库根目录
./backend/mvnw.cmd -f backend/pom.xml spring-boot:run '-Dspring-boot.run.profiles=console'
```

`console` profile：HTTP 127.0.0.1；Flyway 关闭（不修改已有数据库）；Hibernate 仅 validate；
拒绝 POST/PUT/PATCH/DELETE；关闭自动恢复扫描任务。普通账户运行，不需要 Docker/root/wrapper 权限。
这不是 VM 部署命令。本轮没有对 R1/R2/R3 数据库或 VM 应用任何改变。
默认 fake 引擎即可读取既有持久化结果；仪表盘显示的 engineMode 是注册配置，不表示某条历史记录执行来源已核验。

## 数据来自哪里

全部运行时页面只读真实 API，失败不会回退到 mock：

| 页面 | GET API |
| --- | --- |
| 概览 | `/api/v1/console/overview`, `/actuator/health` |
| 实验列表/详情 | `/api/v1/console/experiments`, `/api/v1/console/experiments/{id}` |
| 执行列表/详情 | `/api/v1/console/executions`, `/api/v1/console/executions/{id}` |
| 历史 CPU/恢复证据 | `/api/v1/console/executions/{id}/evidence` |
| 公开审计 | `/api/v1/console/audit-logs` |
| 已存报告 | `/api/v1/console/reports`, `/api/v1/console/reports/{id}` |
| 目标/场景名称 | 既有 `/api/v1/targets`, `/api/v1/scenarios` |
| 独立 M1 Core 归档 | `/api/v1/console/history/m1-core`（固定 SHA 只读投影，不查业务 DB） |
| 当前身份 / CSRF | `/api/v1/auth/me`, `/api/v1/auth/csrf` |
| ADMIN 权限摘要 | `/api/v1/console/access`（VIEWER 服务端403） |

业务数据库接口只是有界 SELECT 投影；独立历史接口读取已校验打包资源。
二者均没有调用 ChaosEngine、wrapper 或采样进程。
报告读取复用原有 ownership-aware report service，不改写报告结论。
分页从 0 开始，默认20，最大100。实验支持 name search/status，执行支持 experimentId/status；
审计支持 experimentId/operation/result/from/to（ISO Instant，UI 将本地时间转 UTC）；报告支持 executionId。
URL 保留筛选、分页。TanStack Query 缓存15秒，可通过右上角刷新；不进行自动故障操作。

## 证据语义

- execution SUCCESS、physicalRecovery VERIFIED、recoveryCause UNKNOWN 是三个独立字段。
- CPU 和恢复结论只从 `BLADE_M1_CORE` 的同 execution/target/UID/node/state/container/image/tool 审计与快照投影。
- 无快照、无匹配审计或缺结论时显示 UNKNOWN；不凭 SUCCESS 推断 VERIFIED。
- 历史 recovery audit 没保存数值型 after CPU；该项显示「未采集」，图表留空，不能用 HEALTHY 推导0%。
- Native status、Residual、Health、Gate 是**保存时**的评估，不是现在的实时探针。
- 时间线只显示已有时间，不编造 DESTROYING 或各中间状态发生时间。
- 成功率 = SUCCESS 数 / 全部执行数；无执行时无可计算值。它不是物理恢复证明或因果成功率。
- 报告 UNVERIFIED / NOT_VERIFIED / NOT_COLLECTED / INSUFFICIENT_DATA 保留原义，不能被执行成功覆盖。
- 占用锁没有查询接口，因此界面只说明状态下的占用语义，不宣称锁已实际释放。

## 安全边界与以后接入点

当前普通控制台已有 Spring Security Session + 服务端 VIEWER/ADMIN 权限。菜单隐藏、客户端和代理仍不是最终授权边界。
开发与 preview 代理拒绝业务写方法和非白名单路由；服务端身份与只读授权始终生效。推荐 console profile 的受限部署，不要直接公网暴露。
console 模式的服务端过滤器默认拒绝所有其他路径，GET/HEAD 仅放行上表中页面实际使用的精确接口。
原始审计、恢复清单及其他 Actuator 路径不会因使用 GET 就被放行。
OPTIONS 仅对白名单路径返回 `204` 和 `Allow: GET, HEAD, OPTIONS`，不交给 MVC 宣告写方法；
带 Origin / Access-Control-Request-Method / Access-Control-Request-Headers 的跨域预检拒绝，不自动启用 CORS。
M3 在非 console 模式也缺省拒绝未授权读取及全部旧业务写操作；SELECT-only 数据库权限建议保持不变。
生产建议静态资源与后端放在同源 HTTPS 受限网关后，仅允许上表读取及精确 login/logout POST，使用 SELECT-only DB 用户。
Vite preview 只是本机预览，不是生产服务器。

认证已接入 Spring Security filter chain → 固定角色/精确路径 → ConsoleReadController。
前端会话由 SessionBoundary 与 api/auth 管理；服务端才是最终授权边界。
后续账号管理、细粒度 RBAC 和任何执行入口必须单独审核，不能开启隐藏按钮来实现。
普通 console 代理与服务端仅增加两个受 CSRF 保护的精确 POST：auth/login、auth/logout。其他写操作仍全部拒绝。
仅 `dev:fake` 增加 M4 列出的精确业务写路径；服务端必须是真实 FAKE_DEMO + Fake 引擎，ADMIN + Session + CSRF 才可写。

公开审计采用固定字段白名单；不返回任意 parameters、sourceIp、原始 stderr 或工具路径。
前端错误只显示安全 HTTP 信息或契约错误，不渲染原始异常正文。React 文本转义，不注入 raw HTML。

## 验证

M5 报告详情改用 GET `/api/v1/console/reports/{uuid}/analysis`，复用已存报告及公开审计/恢复证据。
客户端、开发代理与服务端精确白名单同步；无实时 PromQL/探针 API。
启动与数据质量含义见 [M5 说明](../docs/17-m5-traceable-reports.md)。Fake 无监控服务也可显示持久化流程与缺失说明。

```bash
npm run build
npm run lint
npm test
```

跨语言契约测试：先在 `backend` 跑 `./mvnw -Dtest=ConsoleReadApiTests test`，它在
`backend/target/console-contracts.json` 生成真实 Spring 序列化（仅隔离 H2 测试数据）。再在 frontend：

```bash
CONSOLE_CONTRACT_DIR=../backend/target npm test
```

```powershell
$env:CONSOLE_CONTRACT_DIR='../backend/target'
npm test
```

CI 必须运行此联测；普通 frontend-only 测试会明确跳过该项，不伪称做了后端联测。
测试 fixture 只存在 `src/test`，不进入运行时页面。H2 兼容性测试不代表 MySQL 现场部署验收。
构建的 `dist` 为静态产物，生产 HTTP 网关必须支持 SPA history fallback，并保持 API GET 白名单。

## 本版暂未提供

账号管理/多租户、真实写操作、物理报告生成、实时 root 探针、未持久化的 after CPU 数值、占用锁查询、跨 R1/R2/R3 数据库聚合。
本页只显示所连接数据库已有记录，不会读取 VM 文件、导入 Git evidence 或初始化历史数据库。
