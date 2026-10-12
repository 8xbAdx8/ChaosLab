# 5–10 分钟课堂 / 答辩 / 面试演示

提前：按 README 配好自己的 ADMIN 和 VIEWER，运行 `start-fake`。依赖下载不计入演示时间。
不需要 VM、Docker、root、MySQL 或真实工具；不要打开现场凭据或运行日志。

| 时间      | 操作                                          | 讲解重点                                                                          |
| --------- | --------------------------------------------- | --------------------------------------------------------------------------------- |
| 0–1 分钟  | ADMIN 登录，仪表盘                            | Session + CSRF；ADMIN 不是 root，也不能真实注入                                   |
| 1–2 分钟  | 实验 → 创建 Fake CPU                          | 名称/假设，classroom target、CPU Load、30 秒、`{"percent":10}`；10 是输入不是测量 |
| 2–3 分钟  | 保存 → 校验 → 安全预检                        | CREATED → VALIDATED → READY；状态由服务端决定                                     |
| 3–4 分钟  | 启动并确认，查看执行详情                      | 持久化 Execution / RUNNING，幂等键防止同意图重复 dispatch                         |
| 4–5 分钟  | 主动停止并确认，或等待自动恢复                | DESTROYING → SUCCESS，只表示模拟生命周期完成                                      |
| 5–6 分钟  | 查看审计、生成模拟报告                        | 来源与关联 ID、参数、时间窗口、NOT_COLLECTED、INSUFFICIENT_DATA；不补假 CPU/P95   |
| 6–7 分钟  | 刷新；退出；VIEWER 登录                       | 数据仍在 H2；VIEWER 没管理按钮，直连 API 也 403                                   |
| 7–9 分钟  | stop-dev → start-history → `/history/m1-core` | 独立匿名历史：0 → 10.91 → 0%，Gate/physical VERIFIED，cause UNKNOWN               |
| 9–10 分钟 | 展示架构图、测试/CI                           | 历史 JSON SHA/pointer，证据与权限边界；K8s/多节点尚未做                           |

报告生成在执行结束之后。不要直接修改 SQL 跳过 READY、让前端假装 SUCCESS 或为了截图伪造指标。
等待自动恢复时持续刷新/有界轮询即可；如果测试不确定/失败状态，说明事实，不强制改终态。

## VIEWER 演示的验证点

同一个已持久化执行/报告可以读；创建、编辑、校验、预检、启动、停止、生成报告均不允许。
登录后查看权限页。前端入口隐藏只是 UX，服务端角色 + 模式 + CSRF + 精确路径才是最终边界。
自动化 `delivery-smoke.mjs` 和原 MockMvc 测试还验证直连写入 403、缺少 CSRF 403、登出后 401。

## 避免错误解读

- **Fake SUCCESS**：模拟服务调用、状态和持久化完成，不代表目标 CPU 真实变化。
- **M1 physical VERIFIED**：归档中生产 Gate 通过物理恢复证据，不是现在重新实验。
- **UNKNOWN cause**：主动请求已发出，但不能证明 ACTIVE 而非 timeout 是恢复执行者。
- **INSUFFICIENT_DATA**：没有足够同主体样本计算应用指标；不是故障恢复失败，也不是数据等于零。

## 数据可追溯

查看报告 ID / Execution ID / audit IDs 和参数来源；M1 页面查看原始 JSON pointer、commit 和 SHA。
刷新/重新启动后 Fake 记录仍在自己的 H2。原 R1/R2/R3 的数据库和归档均不参与本地演示。
