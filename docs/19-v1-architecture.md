# V1 实际架构（M1–M6）

本图描述当前源码，不是 Kubernetes/Operator 目标架构。旧 `02-architecture.md` 是演进设计。
Profile 不只是 UI 开关，服务端 Bean、路由、数据源和权限也不同。

## 三种控制台模式

```mermaid
flowchart TB
  UI[React / TypeScript / TanStack Query / ECharts]
  UI --> Proxy[Vite loopback same-origin proxy]
  Proxy --> Security[Spring Security Session / CSRF / VIEWER ADMIN]
  Security --> Filter[ConsoleReadOnlyFilter exact routes]
  Filter --> Controller[REST Controllers]
  Controller --> Service[Application Services / state and transaction rules]
  Service --> Repo[JPA / JDBC Repositories]
  Repo --> DB[(fake-demo: dedicated H2 file<br/>normal console: configured MySQL read-only)]
  Service --> Audit[Persisted audit / public field projection]
  Service --> Reports[Existing ExperimentReport service]
  Service --> Fake[fake-demo only: FakeChaosEngine]
  Reports --> Prom[Optional trusted loopback Prometheus queries]
  Micrometer[Backend Micrometer metrics] -. scrape in configured monitoring deployment .-> Prom
  Prom --> Grafana[Optional existing Grafana dashboards]
  UI --> Hist[history-demo independent launcher<br/>anonymous loopback / no DB / no engine]
  Hist --> Archive[SHA-verified fixed Git archive reader]
  Archive --> JSON[M1 JSON + acceptance Markdown]
  subgraph Closed[Separate reviewed real deployment - NOT enabled by local launchers]
    Blade[ChaosBladeEngine] --> Channel[BladeProcessChannel]
    Channel --> Boundary[Exact sudo / root-owned wrapper]
    Boundary --> Native[Fixed Blade / namespace / cgroup / sandbox]
    Native --> Evidence[Production CPU / Residual / Health / Gate]
  end
```

console 只读白名单不因为 ADMIN 放开业务写；fake-demo 同时要求真实 Session/ADMIN/CSRF、
精确 Fake 写路径、固定模式及实际 Fake 引擎。history-demo 排除 DataSource/JPA/Flyway，只导入归档与身份组件。
本地启动器不装 Prometheus/Grafana、没有包装到真实边界的命令入口。
Prometheus 的指标是另一个配置部署可选能力，不是 Fake 目标的物理指标。

## 两个状态机，不混用状态

对应 `experiment/domain/Experiment.java`。自环幂等检查略去；校验/预检失败保持原状态。

```mermaid
stateDiagram-v2
  [*] --> CREATED: create
  CREATED --> VALIDATED: validate
  VALIDATED --> READY: accepted dry-run
  READY --> RUNNING: successful create result persisted
  RUNNING --> DESTROYING: beginDestroy
  DESTROYING --> SUCCESS: complete after engine recovery accepted
  DESTROYING --> ROLLBACK_FAILED: recovery failed
  ROLLBACK_FAILED --> DESTROYING: existing explicit recovery path
  READY --> DESTROYING: uncertain execution with durable trusted handle ONLY
```

对应 `execution/domain/ExperimentExecution.java`。FAILED / CREATE_UNCERTAIN 是执行状态，
不是 ExperimentStatus。`CREATE_UNCERTAIN` 不自动重试 create；图中的恢复分支只允许已有可信 journal。

```mermaid
stateDiagram-v2
  [*] --> PREPARING: durable prepare and occupancy
  PREPARING --> RUNNING: markRunning
  PREPARING --> FAILED: known create failure
  PREPARING --> CREATE_UNCERTAIN: ambiguous create outcome
  CREATE_UNCERTAIN --> DESTROYING: explicit recovery with matching durable journal
  RUNNING --> DESTROYING: active or scheduled destroy
  DESTROYING --> SUCCESS: accepted recovery then final transaction
  DESTROYING --> ROLLBACK_FAILED: error / evidence not confirmed
  ROLLBACK_FAILED --> DESTROYING: existing explicit recovery path
```

M6 没修改这些迁移，也没开放 uncertain 的强制成功/清占用按钮。Fake SUCCESS 是模拟完成；
真实 Blade 的 SUCCESS 条件包含 M1 Core 生产物理恢复证据，不能只凭 native exit/status。

## 一次 Fake 实验时序

```mermaid
sequenceDiagram
  actor User as ADMIN browser
  participant Sec as Spring Security
  participant API as REST Controller
  participant App as Audited/Application Service
  participant DB as Repository / H2
  participant Fake as FakeChaosEngine
  participant Audit as Audit / Report services
  User->>Sec: csrf + login (Session cookie)
  Sec-->>User: Session / fresh CSRF
  User->>Sec: create / validation / dry-run + CSRF
  Sec->>API: ADMIN + fixed FAKE_DEMO route allowed
  API->>App: validated DTO
  App->>DB: CREATED / VALIDATED / READY commits
  App->>Audit: persisted public audit facts
  User->>Sec: start + CSRF + Idempotency-Key
  Sec->>API: actual Fake mode / authority checked
  API->>App: same existing start service
  App->>DB: PREPARING + occupancy committed
  App->>Fake: create simulated operation once
  Fake-->>App: fake execution identity
  App->>DB: RUNNING committed
  App->>Audit: successful START audit
  User->>API: bounded read polling
  API->>DB: persisted status
  alt Active stop
    User->>Sec: destroy + CSRF
    Sec->>API: ADMIN / mode guard
    API->>App: destroy
  else Existing recovery scheduler
    App->>App: duration reached / scheduled recovery
  end
  App->>DB: DESTROYING committed
  App->>Fake: destroy simulated identity
  Fake-->>App: simulated recovered
  App->>DB: SUCCESS / occupancy release transaction
  App->>Audit: recovery audit
  User->>Sec: generate report + CSRF + Idempotency-Key
  Sec->>API: authorized Fake route
  API->>Audit: existing report generation
  Audit->>DB: persisted SIMULATED / missing metrics / insufficient conclusions
  User->>API: GET report analysis
  API->>DB: correlate saved report / execution / audit
  API-->>User: source and quality-aware DTO (no live target probe)
```

## 已完成 M1 Core 的历史证据

```mermaid
flowchart LR
  CPU[Direct cgroup fault evidence<br/>baseline 0 to during 10.91 percent] --> Native[Same UID Native Destroyed]
  Native --> Residual[Residual PRESENT then CLEAR]
  Residual --> Health[Health HEALTHY]
  Health --> Validator[Fresh same-subject Validator / Gate VERIFIED]
  Validator --> Tx[Backend SUCCESS transaction / occupancy released]
  Tx --> After[Archived later read-only CPU sample 0 percent<br/>not a post-SUCCESS veto]
  Cause[Recovery cause UNKNOWN<br/>ACTIVE request issued, causal winner not proven] -. independent dimension .-> Tx
```

这是归档中的历史过程，非当前监控。`docs/evidence/m1-core-real-20261010.json` 不变，
M2 读取器逐字段提供 JSON pointer。Native 时间戳不推导主动 destroy 的因果归属。
真实物理恢复、报告 HTTP 指标充分性、恢复因果是三个不同维度。
