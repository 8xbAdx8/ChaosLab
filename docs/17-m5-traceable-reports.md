# M5：可追溯监控与报告 V1

基线：已核对并合并 PR #6，main `42d9f572e5b04784b488071f6e5045021acf01d7`。
M5 使用独立开发分支，不合并自身 PR。不操作 VM、现场数据库、Blade、授权或真实策略。

## 实现范围

复用 `ExperimentReportApplicationService`、`ExperimentReport`、现有 observations JSON、
报告持久化、公开审计及同主体 M1 证据投影。没有第二套报告服务、指标数据库或迁移。

报告详情 `/reports/{reportId}` 增加：

- 实验/执行/目标/场景、起止时间和持续时间；名称明确为当前定义。
- 参数取自该报告绑定的成功 START 审计快照，不用当前表单补造历史参数。
- 精确关联的执行审计时间线、原生 UID 和独立物理恢复证据。
- HTTP 三阶段请求数、平均吞吐量、5xx 错误率、P95 柱状图与原值表格。
- 来源版本、job/instance/route、数据质量、比较资格与缺失解释。
- 目标 CPU 只展示已有同主体 cgroup 审计样本；未保存 after 数字仍为 null。
- 目标内存 NOT_COLLECTED；不以 JVM 指标、percent 参数或 HEALTHY 代替数值。

SIMULATED、物理恢复与指标充分性独立。Fake SUCCESS 不会被升级成物理 VERIFIED；
有 HTTP 观测也不证明发生过故障。M1 物理 VERIFIED 不补齐 HTTP P95/错误率，恢复来源仍 UNKNOWN。
M2 历史页面 `/history/m1-core` 仍使用校验后的原归档，0% → 10.91% → 0%，不导入 Fake 数据库。

## 数据流与时间窗口

Micrometer 在 order-service 中统计真实请求、计数器和延迟直方图。
现有 Prometheus 每 5 秒读取 `/actuator/prometheus`；Grafana 使用同一数据源作独立看板。
生成报告时：现有服务核验本机 Demo 的精确 container/image、启动时间和 Prometheus 抓取绑定，
确认 after 窗口已结束，再通过后端固定查询只读采集。结果保存到原 observations JSON。
React 读取已存报告与审计组合 DTO，不接收 PromQL，不向 Prometheus 或目标直接探测。

设持久化 startedAt=S、finishedAt=E、D=E-S：
Before=[S-D,S]、During=[S,E]、After=[E,E+D]，PromQL range 的毫秒精度与窗口一致。
Prometheus range 的左边界采用其自身的左开右闭语义。查询 evaluation time 必须与窗口末端相差不超过 1ms。
这是三个聚合观察窗口，不是连续实时曲线；图表不插值、不补缺失点。

V2 的受信标签固定为 job=order-service、instance=order-service:8080、uri=/orders/{orderId}。
沿用已有绑定脚本对本机专用 Compose/网络/抓取地址及唯一目标的验证；新增窗口内 up 序列唯一性检查，
不混入库存服务、其他 instance 或后台 JVM。该绑定只适用于受信的本机 Demo，不声称支持任意生产环境。
本轮不运行绑定脚本、不安装或启动 Docker/Prometheus。

### 数据质量门槛

- 每阶段 20–3600 秒，最多 3 阶段。
- 5 秒抓取周期，至少 max(3,floor(窗口秒数/5×0.8)) 个样本，期间 up 恒为 1，up 序列唯一。
- 至少 20 次实际请求。它是教学可比门槛，不是 P95 的统计置信保证。
- 5xx 必须是明确的有限非负值且不超过总请求数；缺失 5xx 不再填 0。
- 延迟直方图必须存在、P95 有限非负，+Inf bucket 的请求增量与 count 在 1%（最少 .001）容差内一致。
- 不能靠 clamp 把不一致错误数变成健康的 0%/100%。缺失/不一致/不可用一律 INSUFFICIENT_DATA。
- errorRate=5xx/total；平均吞吐量=窗口实际总请求增量/窗口秒数；P95 为 histogram_quantile 的估计值。
- Prometheus increase 可能因边界外推得到非整数估计计数，UI 不伪称精确逐请求日志。

旧版会将不存在的 5xx 系列当零，M5 修正未来采集，不修改旧报告。
已有 Demo Python 报告脚本也同步缺失值、固定标签和样本/直方图规则，未增加新的报告入口；
它仍是独立手工教学工具，不作为控制台运行时数据源，也不升级其 manual_unverified 执行结论。
新增可选 `ReportWindow.collectionVersion=PROMETHEUS_HTTP_V2`，复用 observations JSON，无 migration。
旧 JSON 缺少版本时按 null 读取，原值和结论保留，但不会被重新认证为 M5 可比结果。

三阶段比较 AVAILABLE 要同时满足：报告绑定成功、V2、三个完整 OBSERVED 窗口、
绑定的成功 START/recovery 审计具有相同 experiment/execution/target/scenario、执行起止与报告窗口相同。
不符则 NOT_COMPARABLE，缺失参数来源 UNKNOWN，关联 REJECTED。读接口不更新报告或 execution。

## 最小 API 扩展与安全

唯一新 API：GET/HEAD `/api/v1/console/reports/{uuid}/analysis`。
它组合现有报告、执行、公开审计、目标标签、恢复证据与质量说明。审计最多返回 100 条并标记截断。
没有实时查询代理、任意 URL/PromQL、任意探针、root 或真实执行入口。
原报告 API/分页 DTO 保持兼容；新窗口版本为可选增量字段。

服务端精确只读白名单、Spring Security、Vite proxy、客户端允许路径均加入该精确后缀；
其他后缀或实体的 `/analysis` 不放行。VIEWER/ADMIN 均可读，未登录 401，无角色 403，POST 拒绝。
Session/CSRF/Fake ADMIN 写权限及 SELECT-only console 数据库建议不变。
`history-demo` 仍无数据库、账号、root 或额外服务依赖。

Prometheus 只能配置受信 loopback HTTP 根 URL（沿用现有约束）；不接受 HTTP 请求提供的地址。
不跟随重定向，查询表达式最多 2048 字符，响应最多 64KiB，完整响应体含流式读取最多 3 秒，
返回最多一个 vector 样本，结果时间不匹配拒绝。异常只转成安全缺失原因，UI 不回显内部 URL/Token/原始输出。

## 两终端启动并查看报告

使用 [M4 完整启动说明](16-m4-fake-demo.md) 配置自己生成的 BCrypt 账号，不存在默认密码。
无需 MySQL、Docker、Prometheus 或 VM，即可展示真实持久化的 Fake 生命周期/审计与明确的数据缺失。

```powershell
# 仓库根构建（Java 21）
.\backend\mvnw.cmd -f backend/pom.xml verify
# 先按 M4 设置 CHAOSLAB_ADMIN_USERNAME / CHAOSLAB_ADMIN_PASSWORD_HASH
cd backend
java -jar target/chaoslab-backend-1.0.0-rc1.jar --spring.profiles.active=fake-demo
```

```powershell
# 另一个终端，仓库根
cd frontend
npm ci
npm run dev:fake
```

打开 `http://127.0.0.1:5173/login` → ADMIN 登录 → 创建/校验/预检/启动一次 Fake →
停止或等待原自动恢复 → 执行详情「生成模拟报告」→ 报告详情。
重启保持同一工作目录和独立 `.local/chaoslab-fake-demo/chaoslab_fake_demo` 数据库即可保留历史。
VIEWER 可看报告、不能生成或执行。普通 console 使用已迁移的专用 SELECT-only 数据库，不开启写操作。
API_PROXY_TARGET 来自本机受信开发配置，未硬编码 VM IP。

Fake-demo 固定 classroom target 没有受信 order-service 指标绑定，因此 HTTP/CPU 指标缺失是预期，
不是启动失败。要展示已有 HTTP 指标，应使用原正式报告生成服务与已核验本机 Demo 的已存报告；
本轮不扩展 Fake 目标或部署监控平台。测试 HTTP fixture 只验证查询与渲染，不作为运行时数据。

只看 M1 的完整真实 CPU 三点：按 [M2 启动说明](14-m2-history-demo.md)，打开 `/history/m1-core`。
该模式仍无需登录/数据库，与 Fake 报告分离。

## 验证

```powershell
.\backend\mvnw.cmd -f backend/pom.xml verify
python backend/scripts/check_auth_artifact.py
python -m unittest discover -s demo-services -p 'test_*.py'
python tools/m1-wrapper/deployment/test_m1_core_contract.py
cd frontend
$env:CONSOLE_CONTRACT_DIR='../backend/target'
npm test
npm run lint
npm run build
```

新增测试：缺失零值、counter/histogram/少量样本、唯一标签、stale evaluation timestamp、响应大小/完整 body deadline、
受信地址拒绝、窗口错位、审计主体错位、legacy JSON 兼容、报告只读幂等、Fake 完整生产 Security 流程、
VIEWER/ADMIN/未登录/其他角色、精确 console 白名单，以及真实 Spring DTO → TypeScript 契约测试。
保留 Java 无害进程/root fixture、历史 SHA 和 Fake 并发/恢复回归。测试不产生真实故障。

## 尚未完成

目标内存、通用目标指标绑定、原始高频时序持久化、后台 JVM 专用观测页面、在线 Grafana 嵌入、
报告 PDF、指标/故障因果推断均未实现。M1+ 原生恢复因果仍 UNKNOWN，不在 M5 扩展。
缺失信息是有效报告结论，不因“演示效果”补造数据。
