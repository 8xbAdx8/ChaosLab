# Changelog

本项目没有虚构的历史发布序列。M1–M6 是开发里程碑，不是六个已发布版本。

## 1.0.0-rc1 — 发布准备（2026-10-12，尚未打 tag / 发布）

### V1 能力

- M1：独立受限 sandbox 的真实 CPU 与物理恢复闭环已验收。归档显示 CPU 0% → 10.91% → 0%，Native Destroyed、生产 Gate / physical recovery VERIFIED，恢复原因 UNKNOWN。保留早期 INCOMPLETE，不改写历史。
- M2：固定 SHA 的只读历史投影、JSON Pointer、CPU 三阶段及恢复时间线；已结束的历史，不是实时监控，不导入 Fake 数据库。
- M3：Spring Security Session、Cookie、CSRF、VIEWER/ADMIN；无默认账号，ADMIN 不获得真实故障或 root 权限。
- M4：Fake 创建/草稿编辑/校验/预检/幂等启动/主动与自动恢复/审计/持久化报告，复用已有状态机和占用管理。
- M5：报告与执行/审计关联；可选受信 Prometheus 观测；缺失、主体/时间窗口/样本不足明确标记，不补零，不把 JVM 或 Fake 参数当目标物理指标。
- M6：PowerShell/Bash doctor、Fake/history 启动与受控停止；独立 H2 文件；WRITE_DELAY=0；README、实际截图、Mermaid、演示和作品集。

### RC 整理

- `VERSION`、后端 Maven、前端 npm/lockfile 一致为 `1.0.0-rc1`，启动与产物检查使用同一受信版本。未升级 Spring Boot、Java、Node 或依赖。
- 增加发布失败矩阵、完整草稿/权限/旧 Session/重启验收、无关 Java/Node 进程存活检查及历史字段来源核对。
- 缺少账号时提示先运行交互启动入口；未增添业务功能、迁移或真实权限。
- R2 发布阻塞清理：修正 Node readline 向 Java Console 的输入所有权交接；隐藏密码校验保留长度/BCrypt限制，提供有界重试。扩展只用于验收的 stdin 测试账号配置，不产生部署默认账号或明文凭据文件。
- 新增发布检查表和待审批 Release Notes；原有安全断言、root harmless CI 与归档保护保留。

### 支持与限制

- 源码交付面向 Windows 11 / Ubuntu：Java 21、Node 24。Windows 11 本机 JDK24 是补充验证，不冒充本机 JDK21；Windows/Ubuntu Java21 自动化由 CI 证明，Windows hosted runner 也不等于 Windows11。
- Bash 入口可供 macOS 使用，但无 macOS 验收。首次依赖下载需要网络；允许全局 Maven/npm 缓存，不复用其他工作区的构建/配置/数据库。
- 本地教学平台，不是公网生产混沌执行系统。Fake SUCCESS 仅模拟生命周期，不代表物理恢复；ADMIN 无 REAL authorization / Blade / root 入口。
- Windows 私有 NTFS ACL 是使用前提；不能把 POSIX mode 检查当 NTFS ACL 验证。真人隐藏密码交互、正式发布批准仍待完成，见检查表。
- H2 提交刷盘/停止重启验收不保证未提交事务或所有硬件断电场景。未实现 K8s/Operator、多租户、SSO、多目标真实执行或原生恢复因果仲裁。
- 已有 ESLint 弃用提示及 ECharts chunk 大小警告被记录，未通过升级整栈或隐藏警告处理。

## 1.0.0 — 尚未发布

正式版要在 RC 复现、人工验收和维护者批准后另行决定。不存在自动升级、tag 或 Release。
