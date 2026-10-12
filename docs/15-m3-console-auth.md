# M3：会话登录与只读 RBAC

基线：PR #4 已合并，main `47740ca2eb6039b9813a602a08f9c780b0b0317b`。
本轮没有操作 VM、修改 R1/R2/R3、数据库 migration、用户表或真实故障执行能力。

## 登录方式

Spring Security 7（版本由 Spring Boot 管理）标准用户名/密码 form login，服务端 HttpSession，
浏览器只携带 `CHAOSLAB_SESSION` Cookie。没有 JWT、OAuth/SSO、注册或用户 CRUD。
账号来自受信部署配置，仅保存 BCrypt hash，不接受 HTTP 提交的角色或授权规则。
默认没有账号/密码，没有 Boot 自动生成密码；配置为空时所有登录失败，数据不会匿名公开。

固定角色服务适合当前单工作区教学部署，重启使内存 Session 失效；增加用户/轮换密码需要
管理员更新受信配置并重启，不是控制台操作。账号配置不能提交 Git。
hash 也应视为敏感配置，避免写进命令行参数、截图或日志。

| 角色/模式 | 可读范围 | 不允许 |
| --- | --- | --- |
| 未登录 SESSION | 当前身份（无账号信息）、CSRF Token、最小 health | 任何控制台业务数据/历史证据 |
| VIEWER | 固定白名单 Console、脱敏审计、已有报告、目标/场景、SHA 归档 | ADMIN 权限摘要、全部业务写操作 |
| ADMIN | VIEWER 的只读范围 + `/api/v1/console/access` 权限摘要 | create/destroy、授权生成、root、用户修改、报告生成 |
| LOCAL_HISTORY | loopback 的固定 M1 历史、身份说明、最小 health | 不具有 VIEWER/ADMIN 角色；不能登录或读取业务 DB API |

VIEWER 的授权范围是当前专用工作区/数据源里的这些公开只读投影，不是多租户或逐用户行权限。
敏感审计参数仍由原字段白名单脱敏，原报告 ownership/conclusion 语义不变。
ADMIN 是未来管理权限基础，不等于 Docker/root/真实执行权限。
服务端缺省拒绝其他路径，包括原非 Console 写 API；不能用取消 console profile 绕过 M3。
原业务单元/集成测试使用测试 classpath 专用 fixture，不代表生产 HTTP 仍开放这些写接口。

## Cookie / CSRF / 生命周期

- HttpOnly、SameSite=Lax；默认 Secure=true。普通部署必须同源 HTTPS / 受信反向代理。
- 仅 `console` 的明确 loopback HTTP 开发配置默认 Secure=false；如果不安全 Cookie 被
  配置到非 loopback 地址，启动拒绝。不要为公网部署设置 `CHAOSLAB_SESSION_SECURE=false`。
- 30 分钟空闲超时，cookie-only Session tracking，不接受 URL Session ID；重启/登出失效。
- 登录更换 Session ID 防止 fixation。HTTP Basic/remember-me/request cache 均关闭。
- 默认 HttpSession CSRF repository 与 XOR masking 保留。`GET /api/v1/auth/csrf` 返回
  masked token（Cache-Control:no-store），每次 login/logout 前取新值，再发 X-CSRF-TOKEN。
- `POST /api/v1/auth/login` 与 `POST /api/v1/auth/logout` 均需要 CSRF。不存在 GET logout。
  登录/退出会清除旧 Token；不关闭 CSRF、不允许跨域预检，不使用前端隐藏来授权。
- 登录响应安全 JSON；密码错误/用户不存在均 LOGIN_FAILED，不区分账号是否存在。
- React 只在输入组件和单次 POST 中使用密码，完成后清空；没有 Token/password localStorage。
  登出/401 清除业务 Query 缓存并取消在途请求；保留 identity Query 对象，避免旧 observer
  重建造成已失效身份短暂重新出现。登录返回目标只允许已知站内路由，拒绝外部跳转。
- 服务端权威返回模式和角色；`vite --mode history` 不是匿名访问或授权开关。

方案依据：Spring 官方 [Form Login](https://docs.spring.io/spring-security/reference/servlet/authentication/passwords/form.html)、
[CSRF（含 login/logout 与刷新）](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html)、
[Session 管理](https://docs.spring.io/spring-security/reference/servlet/authentication/session-management.html)。

## API 与安全边界

| API | 行为 |
| --- | --- |
| GET `/api/v1/auth/me` | SESSION authenticated/username/roles 或 LOCAL_HISTORY 非登录身份；不返回密码/hash/Token |
| GET `/api/v1/auth/csrf` | 标准 Session CSRF Token；本地历史模式不开放 |
| POST `/api/v1/auth/login` | `application/x-www-form-urlencoded` username/password；成功200，失败401 |
| POST `/api/v1/auth/logout` | 新鲜 X-CSRF-TOKEN，成功204，清 Cookie 与 Session |
| GET `/api/v1/console/access` | ADMIN-only，只读能力列表，realExecutionAllowed=false |

Spring Security 位于 ConsoleReadOnlyFilter 之前，标准登录/退出 Filter 在通过 CSRF 后消费
仅这两个 POST。Console 过滤器没有放开通用 POST；剩余所有写路径仍拒绝。
服务端 matcher 复用精确只读路径，不使用 `/api/** permitAll` 或 `/console/** role`。
Vite 的唯一 POST 例外也是精确 login/logout；不能通过代理转发其他写操作。
健康检查之外，其他 Actuator 和原生审计/恢复清单仍不在白名单。
匿名401、权限不足403；Spring StrictHttpFirewall 对部分非规范路径/TRACE 可先返回400。
400/401/403 都是拒绝，不意味着这些路径被放行。

## 普通控制台启动

依赖 Java21、Node>=22.12、**已迁移的专用本地 MySQL**。继续使用 SELECT-only DB 用户，
console profile 关闭 Flyway 和自动恢复；不要连接现场数据库。认证本身没有数据库依赖。

先构建（仓库根）：

```powershell
.\backend\mvnw.cmd -f backend/pom.xml package
```

在交互终端生成 BCrypt hash；密码不回显、不能放 argv，要求至少12字符且最多72 UTF8字节：

```powershell
java -Dloader.main=com.chaoslab.console.security.PasswordHashTool -cp backend/target/chaoslab-backend-1.0.0-rc1.jar org.springframework.boot.loader.launch.PropertiesLauncher
```

为 VIEWER 与 ADMIN 分别生成不同密码/hash。管理员在本机受信配置中设置：

```powershell
$env:CHAOSLAB_VIEWER_USERNAME='your-viewer-name'
$env:CHAOSLAB_VIEWER_PASSWORD_HASH=Read-Host 'Paste the locally generated VIEWER BCrypt hash'
$env:CHAOSLAB_ADMIN_USERNAME='your-admin-name'
$env:CHAOSLAB_ADMIN_PASSWORD_HASH=Read-Host 'Paste the locally generated ADMIN BCrypt hash'
# 按原 Console 文档设置专用本机 DB URL/USERNAME/PASSWORD，不要提交真实值。
.\backend\mvnw.cmd -f backend/pom.xml spring-boot:run '-Dspring-boot.run.profiles=console'
```

可以只配置一个角色。部分配置、非 BCrypt/hash cost 非10–14、用户名不符合
`[A-Za-z0-9._-]{1,64}`、两个账号忽略大小写相同，都会安全拒绝启动。
BCrypt cost12；框架可在登录时把较低 cost hash 在内存升级，重启仍按外部可信配置加载。
凭据不是 Flyway seed 或前端 env；不要把这些 PowerShell 临时变量写进仓库脚本。

第二个终端：

```bash
cd frontend
npm ci
npm run dev
```

打开 `http://127.0.0.1:5173/login`。同源 Vite Proxy 默认后端8080；自定义
`API_PROXY_TARGET` 同原说明。远程部署前必须 HTTPS、Secure Cookie、限制来源并配合
网关登录速率控制；跨源 Cookie/CORS、账户管理、细粒度权限和 SSO 不在本轮。
认证不是允许公开真实执行的新授权，后端仍应普通用户运行。

## M2 独立历史演示

沿用 [M2 两终端说明](14-m2-history-demo.md)：

```powershell
.\backend\mvnw.cmd -f backend/pom.xml spring-boot:run '-Dspring-boot.run.main-class=org.chaoslab.historydemo.HistoryDemoApplication'
```

前端 `npm run dev:history`，打开 `/history/m1-core`。
页面明确“本地历史演示 · 非登录身份”，服务端返回 authenticated=false、roles=[]。
仍不连接数据库、不需要 Docker/root/VM、不创建 Session、不登录。
固定归档只允许 loopback 读取；不要公网发布或把它代理成远程管理入口。
所有账号环境配置在该模式忽略，不能冒充 ADMIN。切换 URL 或前端 mode 不能扩大后端范围。

## 验证与测试隔离

- `ConsoleAuthenticationTests`、`ConsoleSessionHttpTests` 明确 auth-test profile，走完整生产
  SecurityFilterChain 和真实 BCrypt 登录。后者是真 Tomcat/HttpClient/Cookie，不是 MockMvc Cookie。
- 测试账号/密码每次随机生成，不硬编码/提交登录凭据。
- `ConsoleProductionBoundaryTests` 关闭 console servlet filter 后，仍通过真实 ADMIN 登录证明
  生产 SecurityFilterChain 拒绝原业务写接口和未列入白名单的读取；不能用取消 console profile 绕过。
- 覆盖匿名/Basic绕过、VIEWER/ADMIN隔离、未知角色、双角色有效CSRF仍不能写、精确路径/OPTIONS/CORS、
  登录/退出CSRF、Token轮换、Session ID更换、登出后的旧Cookie重放、密码/hash脱敏、配置错误拒绝。
- `LegacyBusinessTestSecurity` **只在 src/test**，legacy-api-test profile 仅为原业务/Fake集成测试
  隔离身份契约；不打入 JAR。生产选择同名 profile 也没有任何 permissive fixture。
  安全测试不得使用该 profile，CI 另外断言测试 helper 不存在于生产 JAR。
- 历史启动器只导入生产认证配置和历史组件，不扫描测试 classpath fixture。模式/来源测试保持 SHA 原义。
- 前端测试覆盖登录/安全错误、密码清空、不重试、角色/路由、退出和过期缓存清理、历史演示说明、
  精确 Proxy POST 例外。CI 消费真实 Spring identity/console/history DTO，不能用 mock 替代契约。
- 原后端全量、Demo/Python、Go/root FAKE CI 保留。R1/R2/R3 原始 JSON 和验收报告未修改。
