# ChaosLab Web Console

第一版只读控制台。React + TypeScript（strict）+ Vite + Tailwind CSS + React Router + TanStack Query + ECharts。
没有 create/destroy、授权生成、命令执行、实验修改或报告生成入口。不连接 VM，不自动导入历史证据。

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

新接口只是有界 SELECT 投影；没有调用 ChaosEngine、wrapper 或采样进程。
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

当前项目**没有登录鉴权**。菜单隐藏、GET-only 客户端、开发代理白名单都不是认证/RBAC。
开发与 preview 代理拒绝写方法和非白名单路由，但直连后端还要启用 console profile；不要公网暴露。
生产建议静态资源与后端放在同源、仅允许上表 GET 路由的受限网关后，使用 SELECT-only DB 用户。
Vite preview 只是本机预览，不是生产服务器。

未来鉴权：Spring Security filter chain → 读权限授权 → ConsoleReadController；在网关限制来源和速率。
前端会话接在 `src/api/client.ts`，路由权限展示接在 `App.tsx`；服务端才是最终授权边界。
未来 RBAC 与任何执行入口必须单独设计/审核，不应通过开启本页面的隐藏按钮实现。

公开审计采用固定字段白名单；不返回任意 parameters、sourceIp、原始 stderr 或工具路径。
前端错误只显示安全 HTTP 信息或契约错误，不渲染原始异常正文。React 文本转义，不注入 raw HTML。

## 验证

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

认证/RBAC、写操作、报告生成、实时 root 探针、未持久化的 after CPU 数值、占用锁查询、跨 R1/R2/R3 数据库聚合。
本页只显示所连接数据库已有记录，不会读取 VM 文件、导入 Git evidence 或初始化历史数据库。
