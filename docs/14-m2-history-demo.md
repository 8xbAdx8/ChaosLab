# M2：真实 M1 Core 历史演示

当前 V1 首选 [交付指南](18-v1-delivery.md) 的独立启动脚本；本页保留 M2 接口说明与手动入口，不代表新的现场验收。

## 选择与边界

只实现一套 **SHA 校验后的只读证据投影**，没有数据库导入程序。
归档不包含完整业务实验名称、假设或 audit UUID；强行写入业务表会制造历史。
独立启动器只加载历史 Controller、Reader 与既有 ConsoleReadOnlyFilter，不扫描
生产应用服务，不注册引擎、Wrapper 通道、JPA、DataSource、Flyway 或恢复调度器。
不需要 MySQL、Docker、root wrapper、ChaosBlade 或 VM。不会访问 R1/R2/R3 数据库。

原生产启动器保持不变：正常 Console 也能读取同一个历史接口；原来的数据库页面
仍使用正式 REST API。原 SELECT-only 数据库权限建议继续有效，见
[Console 说明](12-readonly-web-console.md)。本演示启动器完全不连接数据库。

## 两个终端即可启动

依赖：Java 21（JAVA_HOME 指向 JDK）、Node >=22.12。首次构建需要下载 Maven/npm 依赖。
从仓库根目录执行以下命令；不需要配置任何数据库环境变量。

### 终端 A：独立历史后端

PowerShell：

```powershell
cd backend
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.main-class=org.chaoslab.historydemo.HistoryDemoApplication"
```

Linux/macOS：

```bash
cd backend
./mvnw spring-boot:run -Dspring-boot.run.main-class=org.chaoslab.historydemo.HistoryDemoApplication
```

它自动加载 `history-demo` 配置，HTTP 绑定 `127.0.0.1:8080`。
若 8080 已被其他应用占用，附加
`"-Dspring-boot.run.arguments=--server.port=18080"`，并在终端 B 设置对应代理目标。
不要停掉不属于本演示的应用。

也可先 `./mvnw package` / `.\mvnw.cmd package`，再从仓库根目录运行打包产物：

```bash
java -Dloader.main=org.chaoslab.historydemo.HistoryDemoApplication -cp backend/target/chaoslab-backend-1.0.0-rc1.jar org.springframework.boot.loader.launch.PropertiesLauncher
```

正常 `java -jar` 仍启动原 ChaosLabApplication，而不是演示应用。

### 终端 B：前端历史模式

```bash
cd frontend
npm ci
npm run dev:history
```

打开 **http://127.0.0.1:5173/history/m1-core**。
history 模式的首页也会跳到该页面，导航只显示历史入口，不会请求不存在的业务数据库 API。
正常 `npm run dev` 保留原有全部控制台导航，并增加这个历史入口。

默认 Vite Proxy 是 `http://127.0.0.1:8080`，不是任何 VM IP。
自定义端口示例（PowerShell）：

```powershell
$env:API_PROXY_TARGET='http://127.0.0.1:18080'
npm run dev:history
```

Linux/macOS：`API_PROXY_TARGET=http://127.0.0.1:18080 npm run dev:history`。
生产前端历史构建可用 `npm run build:history`，然后 `npm run preview`；仍需运行历史后端。
不得将前端导航隐藏当作权限边界；未来认证/RBAC 的接入位置与原 Console 相同。
本地演示不应公网暴露。

## 精确接口与来源

`GET /api/v1/console/history/m1-core`（亦允许 HEAD）。没有文件路径、执行命令或导入参数。
服务端只为这个精确路径扩充 Console 白名单，受控 OPTIONS 与拒绝其他 GET/写操作的规则不变。
Vite 开发/预览代理与前端 GET 客户端同步增加该路径，不放开 `/history/**`。

Maven 不过滤地打包两个源文件。每次读取先做 64 KiB 上限和双 SHA 校验，再产生稳定 DTO；
任何缺失、损坏或 SHA 不匹配返回安全的 503，不显示伪造的成功回退。
反复启动/读取幂等，没有数据库写入、导入 marker 或新的业务历史。

| 来源 | SHA-256 | 校验模式 |
| --- | --- | --- |
| `docs/evidence/m1-core-real-20261010.json` | `b57cac027022e3c3b009b6daf20eef22c9146d2c7f32dd5c579841fe6eb84f33` | 原始字节 |
| `docs/12-m1-core-field-result.md` | `b776e3b54a8ba6b6ec065fe6c2cb15784f6577c68eab6a1d0058454e7bc8a2a0` | UTF-8，CRLF 标准化为 LF |

首次归档提交：`c98cd599891dae7ffd23495e65fddfa9fe957918`。
实验实际执行的代码提交：`0cb29a13690e89a5d4bda2be1c64ebb3408f5536`（JSON `/mergeCommit`）。
两者不是这次演示功能的代码提交。页面链接固定归档提交，不跟随 main 的后续更新。

## 展示语义与可追溯性

所有标量 fact 附原始 JSON Pointer；三阶段样本、时间线、恢复摘要、审计事件都保留来源。
API 仅公开允许字段，不转发 policy executable/state/root 路径或原始 root 输出。
缺失字段为 null，页面显示 UNKNOWN / 未记录，不由 SUCCESS 推导其他证据。

- 实验/执行/UID、固定 sandbox/container/image/node/state、CPU10%、timeout10秒、后端
  create/主动 destroy 请求次数 1/1 均来自 JSON。
- **cpu-count=1 仅在已校验验收报告明确记载**。JSON 没有独立 cpuCount 字段；DTO
  中该字段仍为 null。报告陈述单独携带原句和章节，不冒充 JSON 字段。
- CPU0% → 10.911216516130064% → 0% 是三个直接 cgroup 采样，不是高频实时曲线。
  After 是终态后只读佐证，不是新增加的终态否决条件。时间可展开到原始 ISO 纳秒。
- 两轮恢复摘要分别为 PRESENT/HEALTHY/MANUAL_INTERVENTION/false 与
  CLEAR/HEALTHY/VERIFIED/true。第一轮是中间观察，不是将终态从失败改写成成功。
- `productionAudits` 只有四个已有事件；不生成 UUID，不重复 afterCreate 子集。
  SQL occurredAt 保留原始字符串并注明时区未单列；ISO 时间显示 UTC+8，并保留原值。
- 时间线的执行创建时间不是 UID 提交的精确时间；UID 先 durable commit 的证明来自
  `/commitProof` 陈述，而不是自行构造事务时间。
- SUCCESS（后端终态）、VERIFIED（物理恢复 / Gate）和 UNKNOWN（恢复因果来源）分别展示。
  不使用 CreateTime/UpdateTime 或时间差推断 ACTIVE/TIMEOUT winner。
- 原始数据明确给出 occupancyRetained=false 才显示“已释放”，不根据终态猜锁状态。
- 业务实验名称、假设、应用 P95 / 错误率、Native 完成精确时间、可信 winner 未采集。
  不创建 experiment_reports，不升级原业务报告的 executionMode/metrics/conclusion。

## 测试与复核

后端全量：`cd backend && ./mvnw verify`（PowerShell 使用 `.\mvnw.cmd verify`）。
前后端实际契约联动：先在 backend 执行

```bash
./mvnw "-Dtest=ConsoleReadApiTests,HistoryEvidenceReaderTests,HistoryEvidenceApiTests" test
```

再在 frontend：

```powershell
$env:CONSOLE_CONTRACT_DIR='../backend/target'
npm test
npm run lint
npm run build
npm run build:history
```

Linux/macOS 使用 `CONSOLE_CONTRACT_DIR=../backend/target npm test`。
CI Windows/Linux 强制生成并消费真实 MockMvc DTO，没有模拟 API 替代契约。
单独运行 frontend 测试且不存在后端构建结果时，跨语言/归档 UI 测试明确 skip；不是 PASS。

覆盖原始 SHA/资源逐字节一致、逐字段追溯、缺失值诚实性、读取幂等、双来源损坏失败、
无 DB/引擎/调度器启动、直连后端拒绝未知路径/写请求/CORS、原 Console 安全回归、
TypeScript 运行时契约、只读 GET、错误不回退成功、主题与历史模式导航。
原 Java、Go/root FAKE 与历史证据 CI 保留；此功能无需操作 VM 或实际故障。
