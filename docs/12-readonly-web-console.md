# 第一版只读管理控制台

本轮从已审核的 M1 Core field acceptance PR #2 合并后开始。
此前合并 SHA：`ca6763d77b1945e2803ea4ce162998d7cc3bfbf3`。
新开发独立分支 `codex/web-console`，不自动合并 main。

## 实现范围

仪表盘 / 实验分页筛选与详情 / 执行分页与详情 / 实际记录时间线 / 历史 CPU 与恢复证据 /
公开审计筛选 / 原有报告独立结论 / 深浅主题 / 响应式导航 / loading-empty-error。
React、TypeScript strict、Vite、Tailwind4、Router、TanStack Query、ECharts；组件化但不增加通用平台框架。
使用新增薄的 SELECT 查询控制器，复用已有 DTO 和报告读服务；不修改数据库迁移。

新分页接口和启动方式见 [frontend/README.md](../frontend/README.md)。
安全模式应使用 SELECT-only 专用 DB 用户、loopback HTTP、console profile 和生产 GET-only 网关。
Vite proxy/client 白名单是防误操作措施，不是 authentication/RBAC。
未来服务端 Spring Security 读权限边界放在 ConsoleReadController 前；前端会话接在 api/client，路由接在 App。

## 不改变 M1 事实

- SUCCESS 是执行终态；物理恢复证据和恢复因果分别展示，UNKNOWN 不改为 ACTIVE。
- 当前数据库里没有可读的历史 native proof 时，显示 UNKNOWN，不凭 SUCCESS 推导 VERIFIED。
- 证据投影必须匹配 execution/target/UID/node/state/container/image/tool。审计输出仅允许公开白名单。
- 原生状态与探针结论是审计记录时的值，不是此页发起的新 root probe。
- 没有持久化 after CPU 数值时显示未采集；不从 HEALTHY 推导0%，不把 Git 文件偷偷当运行时 API。
- 报告来源 UNVERIFIED、指标不足和绑定未核验保留原始 API 含义。
- 未增加锁查询，不把执行终态伪装为锁实际释放情况。

R1/R2/R3 原始 JSON 未修改；未连接 VM、未变更现场数据库，未运行 create/destroy/authorization。

## 验证证据

- MockMvc：查询分页/过滤/注入字符串/边界、缺失证据、同主体检查、脱敏、报告结论、拒绝写入与关闭后台恢复。
- 真实 Spring DTO 序列化输出到仅测试用 target artifact，TypeScript Zod 联测，避免臆造 API。
- 组件/GET-only transport/proxy tests；明确测试 fixture 不作为运行时 fallback。
- Node strict build/lint/test；GitHub Web Console CI Windows/Linux，原有后端/无害 wrapper CI 保留。
- 本机临时 H2 空数据库的实际 HTTP 浏览器联调：概览、列表、审计、主题和移动导航；不冒称 MySQL 现场验收。
- Linux Java21 无网络、无 Docker socket 的现有无害后端测试。

## 后续（不在本轮）

服务端登录与 RBAC、写入能力独立审批、持久化数值型 after CPU、占用锁只读接口、跨隔离数据库历史聚合。
不能为了让控制台“看起来完整”改写 R1/R2/R3、补齐不存在的 metric 或扩大 root 权限。
