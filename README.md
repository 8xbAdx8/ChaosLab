# ChaosLab

ChaosLab 是一个教学型混沌工程平台，目前提供可运行的 Java 后端控制面。当前执行器是 `FakeChaosEngine`：它模拟故障的创建与销毁，不会对真实进程、容器或网络注入故障。本仓库处于开发中，适合学习与本地演示，不应作为可对外暴露的生产服务。

## 当前实现

- 注册目标、查询预置场景、创建与验证实验。
- 安全检查与 Dry Run；默认拒绝生产环境目标，限制时长和单次作用范围。
- 带幂等键的启动、状态查询、手动销毁、超时自动恢复与紧急停止。
- 单目标互斥、全局并发上限，以及记录成功、拒绝和失败的追加式审计日志。
- MySQL + Flyway V1–V8；测试使用 H2 的 MySQL 兼容模式。

已加入独立的 [Demo Services 靶场](demo-services/README.md)，用于观察订单到库存的调用及慢/错传播；后端与靶场现可导出 [Prometheus 指标](docs/03-observability.md)。可选的本地 Prometheus 可采集 Demo 指标，Grafana 看板展示订单请求量、5xx 错误率和 P95；报告脚本可核验后端执行、审计事件和本机 Demo 容器／指标 job 的绑定，生成实验前、中、后的观察文件。Docker Target 的 Dry Run 和启动已接入本机 Demo 身份安全门，未通过校验会拒绝启动；当前仍只有 Fake 执行器，尚无真实 ChaosBlade 故障注入、平台内持久化报告、认证授权、前端和 Kubernetes 集成。完整计划见[路线图](docs/00-roadmap.md)，设计说明见[架构文档](docs/02-architecture.md)。当前 API 没有认证，请仅在本机运行，不要向公网开放。

## 环境要求

- JDK 21 或更高版本；编译目标是 Java 21。
- 本地运行后端需要 MySQL 8.x 和一个可写的测试数据库。
- Maven 不必预装：`backend/mvnw` 与 `backend/mvnw.cmd` 会下载固定版本的 Maven，并校验发行包的 SHA-256。

## 运行测试

测试使用内存数据库，无需安装 MySQL。首次运行需要联网下载 Maven 与依赖。

Windows PowerShell：

```powershell
cd backend
$env:JAVA_HOME = 'C:\path\to\jdk-21'
./mvnw.cmd verify
```

Linux/macOS：

```bash
cd backend
bash ./mvnw verify
```

Demo Services 的测试命令为 `./backend/mvnw.cmd -f demo-services/pom.xml verify`；报告与看板配置的测试命令为 `py -3 -m unittest discover -s demo-services -p 'test_*.py'`。GitHub Actions 在 Java 21 环境执行两个 Maven 项目的 `verify`，并运行 Python 标准库测试。

## 本机启动

先用具备管理权限的 MySQL 账号在本机创建专用数据库和用户（将示例密码换成你自己的本机密码）：

```sql
CREATE DATABASE chaoslab CHARACTER SET utf8mb4;
CREATE USER 'chaoslab'@'127.0.0.1' IDENTIFIED BY 'replace-with-a-local-password';
GRANT ALL PRIVILEGES ON chaoslab.* TO 'chaoslab'@'127.0.0.1';
```

后端启动时 Flyway 自动执行数据库迁移，不需要手工导入表结构。不要把真实密码提交进仓库。

根目录的 [`.env.example`](.env.example) 列出了所需变量，但 Spring Boot 不会自动读取 `.env` 文件。请在当前终端设置真实的本机凭据。例如在 PowerShell 中：

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-21'
$env:CHAOSLAB_DB_URL = 'jdbc:mysql://127.0.0.1:3306/chaoslab?serverTimezone=UTC'
$env:CHAOSLAB_DB_USERNAME = 'chaoslab'
$env:CHAOSLAB_DB_PASSWORD = '<your-local-password>'
cd backend
./mvnw.cmd spring-boot:run '-Dspring-boot.run.arguments=--server.address=127.0.0.1'
```

访问 `http://127.0.0.1:8080/actuator/health`，预期返回 `{"status":"UP"}`。如需调整端口，可在启动参数中加入 `--server.port=8081`。数据库凭据请保留在本机环境变量或未跟踪的 `.env` 文件中。

## 最小 API 流程

以下命令以 PowerShell 为例，假定后端运行在 `127.0.0.1:8080`：

```powershell
$base = 'http://127.0.0.1:8080/api/v1'
$target = Invoke-RestMethod "$base/targets" -Method Post -ContentType 'application/json' -Body '{"name":"local-demo","type":"JAVA_APPLICATION","environment":"CHAOS_LAB"}'
$scenario = (Invoke-RestMethod "$base/scenarios" | Where-Object code -eq 'CPU_LOAD' | Select-Object -First 1)
$body = @{ name='CPU demo'; hypothesis='Service stays available'; targetId=$target.id; scenarioId=$scenario.id; durationSeconds=30; parameters=@{percent=40} } | ConvertTo-Json -Depth 4
$experiment = Invoke-RestMethod "$base/experiments" -Method Post -ContentType 'application/json' -Body $body
$null = Invoke-RestMethod "$base/experiments/$($experiment.id)/validation" -Method Post
$plan = Invoke-RestMethod "$base/experiments/$($experiment.id)/dry-run" -Method Post
$plan.accepted
$execution = Invoke-RestMethod "$base/experiments/$($experiment.id)/executions" -Method Post -Headers @{'Idempotency-Key'='local-demo-1'}
Invoke-RestMethod "$base/experiments/$($experiment.id)/executions/$($execution.id)/destroy" -Method Post
Invoke-RestMethod "$base/audit-logs?experimentId=$($experiment.id)"
```

只在 Dry Run 返回 `accepted=true` 后启动实验。`Idempotency-Key` 的相同值用于重放同一次启动请求。当前的 `RUNNING` 表示 Fake 引擎的模拟状态，不代表真实资源被施加故障。紧急恢复入口为 `POST /api/v1/emergency-stop`。

## 项目结构

```text
backend/   Spring Boot 后端、Flyway 迁移、单元测试与集成测试
demo-services/   独立的订单/库存靶场与 Docker Compose
docs/      路线图、ChaosBlade 调研和架构设计
```

## 许可证

本项目采用 [Apache License 2.0](LICENSE)。
