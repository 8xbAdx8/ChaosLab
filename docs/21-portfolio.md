# ChaosLab：Java 后端 / 全栈作品集

## 项目概述

教学型混沌工程平台，用可控的实验生命周期、安全边界和可追溯证据，区分“操作完成”与“恢复确实发生”。
本地 V1 可登录完成持久化 Fake 实验；真实 CPU 实验已经在独立受限环境验收并归档，普通 Web 用户不能注入真实故障。

## 技术与流程

Java 21、Spring Boot 4.1、Spring Security Session/CSRF、JPA/Hibernate、Flyway、H2/MySQL；
React/TypeScript strict、Vite、Tailwind、TanStack Query、ECharts；Micrometer、可信只读 Prometheus 指标查询、已有 Grafana 配置。
Go root-owned wrapper 是独立真实执行边界，Fake 本地启动不使用它。
架构与状态机见 [V1 架构](19-v1-architecture.md)，操作见 [演示流程](20-v1-demo.md)。

核心链路：配置 → Schema 校验 → SafetyGuard 预检 → durable PREPARING/占用 → 引擎 → RUNNING →
主动/自动恢复 → 证据确认 → 最终状态事务 → 审计 → 持久化报告。

## 可展示的工程难点

1. **不是按钮幂等**：启动意图键、数据库约束/事务和占用控制，防止重复 dispatch；响应不确定不自动重试。
2. **状态与事务分离**：调用执行器前后分别提交必要状态；UNKNOWN 保留占用，不为了终态漂亮强制成功。
3. **权限不依赖 UI**：Session、CSRF、VIEWER/ADMIN、exact path、实际 Fake 引擎身份共同判断写操作。
4. **恢复证据闭环**：15 秒有界 settling window，fresh same-subject Engine/Residual/Health/Gate；不凭 exit=0 判断恢复。
5. **真实/模拟分离**：Fake 成功不写 VERIFIED；历史 JSON SHA/pointer 展示，不导入 Fake 数据库伪造 live 实验。
6. **指标质量**：Prometheus 地址服务端固定、查询有界；缺失不填零；充分采样/标签/窗口一致才允许对比。
7. **交付安全**：Wrapper/lockfile 固定依赖，前台会话只停止自己创建的直接 child，H2 保留，历史模式没有数据库。

## 已验证的真实成果

M1 Core：CPU **0% → 10.91% → 0%**；same-UID Native Destroyed，Residual CLEAR、Health HEALTHY、
生产 Gate VERIFIED，Backend SUCCESS/占用释放；恢复原因 **UNKNOWN**。
原始证据和验收报告见 docs/evidence 与 docs/12；R1/R2 仍是原始 INCOMPLETE，不重写历史。

Windows/Ubuntu Java 21、Node 24 CI 覆盖后端、前端、无害 root fixture、历史 SHA 和 V1 交付流程。
测试数量以 PR CI 的实际报告为准，不在简历写虚构通过率/吞吐量。

## 简历示例（按你的实际参与方式调整）

**ChaosLab 教学型混沌工程平台｜Java 后端 / React 全栈项目**

- 基于 Spring Boot、JPA 和 Flyway 实现实验配置、状态机、幂等执行、占用与自动恢复，提供可审计的本地 Fake 全流程。
- 使用 Spring Security Session/CSRF 和 VIEWER/ADMIN 权限，联合运行模式/实际引擎检查隔离真实与模拟操作。
- 实现可追溯报告与 React 控制台，按数据来源、主体及时间窗口展示证据；缺失指标保留 UNKNOWN/NOT_COLLECTED。
- 在独立受限环境完成 CPU10% 故障与物理恢复闭环，生产 Recovery Gate VERIFIED；SHA 校验归档可复现展示，因果来源保留 UNKNOWN。
- 提供 Windows/Bash 启动与安全停止工具、持久化 H2 演示、跨平台 CI 和工程文档。

这是个人教学项目，可说明 AI 辅助开发/代码审查与自己掌握的实现，不包装成 AI Agent 产品。
不要声称企业用户、生产 SLA、高并发吞吐、Kubernetes Operator 或多租户已经实现。

## 面试讲解顺序

先说明用户能完成什么，再选一个具体链路：为什么 PREPARING 先提交、为什么幂等仍需占用、
为什么 CSRF 与角色不同、为什么 Fake SUCCESS 不等于物理恢复。最后打开实际演示和 CI。
明确当前限制：仅本地教学 V1、无公网生产部署、多节点/K8s/精细权限/原生恢复因果仲裁未实现。
能诚实解释 UNKNOWN 比只展示绿色 SUCCESS 更能体现工程判断。
