# 001 - Docker 客户端网关与仓库入口

状态：**规范契约；分层实现已落地，具体验收与平台边界见 [验证记录](../verification.md)**。

引用：[架构](../../ARCHITECTURE.md)、[002 存储与共享模型](002-local-image-storage.md)、
[003 代理](003-public-registry-api-proxy.md)、[004 清理](004-scheduled-cleanup.md)、
[005 身份与授权](005-user-login.md)、[Registry API SPEC](../spec/docker-registry-api.md)。

## Context

向 Docker 客户端提供 `/v2/**` 协议入口，向管理员提供仓库注册和列表，向浏览器提供 Spring 渲染的仓库页面。
协议接入、业务授权、存储通信、数据库和并发协调必须分开，不能集中在一个控制器中。

### 目标类及所属包

| 完整类名 | 唯一职责 | 允许的主要依赖 |
|---|---|---|
| `com.grallum.application.controller.DockerGatewayController` | 绑定 Docker HTTP 请求，生成类型化输入，写协议响应 | DockerGatewayService |
| `com.grallum.application.controller.DockerRepositoryController` | 仓库管理 REST 请求和 DTO 校验 | DockerRepositoryService |
| `com.grallum.application.controller.DockerRepositoryPageController` | 仓库列表和详情页面，返回 Thymeleaf 视图 | DockerRepositoryService、DockerImageService |
| `com.grallum.application.service.DockerGatewayService` | 协议用例的授权、模式分发、协调及审计 | UserAuthorizationService、DockerRepositoryService、DockerStorageService、DockerProxyService、DockerRepositoryLockService、AuditService |
| `com.grallum.application.service.DockerRepositoryService` | 仓库注册、可见列表和详情；隐藏持久化对象 | UserAuthorizationService、DockerRepository、DockerProperties、WebProperties |
| `com.grallum.application.service.DockerRepositoryLockService` | 单实例仓库互斥和停机拒绝新工作 | JDK 并发工具，无数据库或 HTTP |
| `com.grallum.application.service.AuditService` | 保存限定字段的业务审计 | AuditEventRepository |
| `com.grallum.application.repository.AuditEventRepository` | audit_event 的参数化 SQL | JdbcClient |
| `com.grallum.application.exception.HttpErrorWriter` | 把业务失败映射为 SPEC 错误 | 无业务或存储依赖 |
| `com.grallum.application.exception.ManagementExceptionHandler` | 管理端点的稳定错误响应 | 无业务或存储依赖 |

DockerRepository、DockerProperties、DockerImageService 和仓库模型由 002 定义；授权、身份和 WebProperties 由 005 定义。
控制器不得依赖 repository 包、JdbcClient、JDK HttpClient 或锁服务，也不得调用其他控制器。

## Contract

### HTTP 方法与业务分发

协议方法、权限和状态码严格引用 [SPEC 方法表](../spec/docker-registry-api.md#supported-operations)，不得由旧实现覆盖。

| 请求 | Controller → Service | 约束 |
|---|---|---|
| `GET/HEAD /v2/` | DockerGatewayController → DockerGatewayService.ping | 已认证，HEAD 无正文 |
| `GET /v2/_catalog` | DockerGatewayController → DockerGatewayService.catalog | 按当前授权过滤后分页 |
| `/v2/<alias>/<name>/…` | DockerGatewayController → DockerGatewayService.exchange | 按操作类型判权，不只依据 HTTP 方法 |
| `GET /api/repositories` | DockerRepositoryController.list → DockerRepositoryService.list | 只列当前可见仓库 |
| `POST /api/repositories` | DockerRepositoryController.create → DockerRepositoryService.create | 系统管理员，校验配置 alias |
| `GET /repositories` | DockerRepositoryPageController.list → DockerRepositoryService.list | 返回 repositories/list 模板 |
| `GET /repositories/{id}` | DockerRepositoryPageController.detail → DockerRepositoryService.detail | 当前 READ，返回 repositories/detail 模板 |

同一列表/详情服务供 REST 和页面调用；浏览器写操作遵循 005 的 CSRF 规则。

### 精确 Java 方法声明

以下全部是目标签名，省略框架注解和实现体。各类型的完整包归属由本设计或引用设计给出，不能换成旧 Store 类型。

| 类 | 方法声明 | 参数、结果和副作用 |
|---|---|---|
| DockerGatewayController | `public void handle(HttpServletRequest request, HttpServletResponse response, AuthenticatedUser user) throws IOException;` | Servlet 适配层；user 来自可信安全上下文；负责最终关闭服务响应 |
| DockerRepositoryController | `public PageResponse<DockerRepositoryResponse> list(AuthenticatedUser user, PageQuery page);` | 只绑定参数并调用服务 |
| DockerRepositoryController | `public DockerRepositoryResponse create(AuthenticatedUser user, DockerRepositoryRequest request);` | 校验 DTO，返回服务结果，不执行 SQL |
| DockerRepositoryPageController | `public String list(AuthenticatedUser user, PageQuery page, Model model);` | 放入 repositories 视图数据 |
| DockerRepositoryPageController | `public String detail(AuthenticatedUser user, long repositoryId, Model model);` | 放入 repository 视图数据 |
| DockerGatewayService | `public DockerResponse ping(AuthenticatedUser user, boolean head);` | 校验当前身份，不访问镜像后端 |
| DockerGatewayService | `public PageResponse<String> catalog(AuthenticatedUser user, PageQuery page);` | 仓库名转换为 alias/name；复用可见列表服务 |
| DockerGatewayService | `public DockerResponse exchange(AuthenticatedUser user, DockerRequest request);` | 校验操作、当前授权、模式，持有仓库协调权，执行存储或代理业务 |
| DockerRepositoryService | `public PageResponse<DockerRepositoryResponse> list(AuthenticatedUser user, PageQuery page);` | 当前账号检查、可见查询和 DTO 映射 |
| DockerRepositoryService | `public DockerRepositoryResponse detail(AuthenticatedUser user, long repositoryId);` | 由授权服务得到允许访问的仓库，返回不含内部 URL 的 DTO |
| DockerRepositoryService | `public DockerRepositoryResponse create(AuthenticatedUser user, DockerRepositoryRequest request);` | 管理员检查；短事务创建仓库与审计，重复注册返回冲突 |
| DockerRepositoryLockService | `public DockerRepositoryLease acquire(long repositoryId, Duration timeout);` | 可中断、有界等待；超时和停机显式失败 |
| DockerRepositoryLockService | `public Optional<DockerRepositoryLease> tryAcquire(long repositoryId);` | 供调度使用；被占用返回空，不排无限队列 |
| DockerRepositoryLockService | `public void stopAccepting();` | 停机拒绝新工作，已有 lease 仍需释放 |
| AuditService | `public void record(AuditEvent event);` | 独立短事务或加入现有短事务；不记录正文、凭据和 SQL |
| AuditEventRepository | `public void insert(AuditEventEntity event);` | 仅参数化持久化，无授权或业务判断 |

`HttpServletRequest/Response`、`Model` 仅出现在 controller 方法；service 方法不得使用 Servlet 或 Spring Authentication。
构造器显式注入上表依赖，不暴露可让上层直接执行 SQL/HTTP 的 public 或包级字段。

### 类型定义与所有权

| 完整类型 | 字段或取值 | 不变量 |
|---|---|---|
| `com.grallum.application.dto.DockerRepositoryRequest` | `String registry, String name` | alias 必须来自配置；name 遵循 SPEC |
| `com.grallum.application.dto.DockerRepositoryResponse` | `long id, String registry, String name, DockerRegistryMode mode, String imagePrefix` | 外部镜像地址；无内部存储 URL |
| `com.grallum.application.model.DockerOperation` | TAGS_LIST、MANIFEST_READ/WRITE/DELETE、BLOB_READ/DELETE、UPLOAD_START/STATUS/APPEND/COMPLETE/CANCEL | STATUS/CANCEL 都需要 WRITE；操作与 HTTP 方法组合须符合 SPEC |
| `com.grallum.application.model.DockerHttpMethod` | GET、HEAD、POST、PATCH、PUT、DELETE | 不接受任意字符串扩展 |
| `com.grallum.application.model.DockerRequest` | `DockerRepositoryName repository, DockerOperation operation, DockerHttpMethod method, Optional<String> reference, Optional<String> uploadId, Map<String,List<String>> query, Map<String,List<String>> headers, InputStream body` | 名称规范化；正文只消费一次；保留重复头及不透明上传状态 |
| `com.grallum.application.model.DockerResponse` | `int status, Map<String,List<String>> headers, InputStream body`；实现 AutoCloseable，`public void close();` | close 幂等关闭后端资源和附属 lease；不可重复消费流 |
| `com.grallum.application.model.DockerRepositoryLease` | 仓库持有权，AutoCloseable，`public void close();` | 由锁服务创建；释放线程与持有模型须一致，取消不泄漏锁 |
| `com.grallum.application.model.AuditEvent` | `Instant occurredAt, String actor, String action, String target, String outcome` | 允许的动作及结果，排除秘密 |
| `com.grallum.application.entity.AuditEventEntity` | 审计表字段及持久化 id | repository 内部对象，不作为 HTTP 响应 |
| `com.grallum.application.exception.BusinessException` | `BusinessErrorCode code, String safeMessage` | 运行时业务失败，服务不抛 HTTP ResponseStatusException |
| `com.grallum.application.exception.BusinessErrorCode` | INVALID_INPUT、UNAUTHENTICATED、FORBIDDEN、NOT_FOUND、CONFLICT、UNSUPPORTED、DEPENDENCY_FAILURE、TIMEOUT、UNAVAILABLE、CANCELLED、RATE_LIMITED | 在不同入口映射为对应响应，不携带 SQL 或内部地址 |

共享仓库、分页类型见 002，身份类型见 005。元数据 DTO 可以序列化；流对象不得放入 JSON、日志或实体。

### 授权、资源与错误约定

- UserAuthorizationService 先检查当前账号、签名范围和授权，再允许后端操作；隐藏仓库存在性遵循 SPEC。
- PRIVATE 选择 DockerStorageService，PROXY 选择 DockerProxyService；代理模式写入在网络请求前拒绝。
- 输入流由 Servlet 容器拥有，服务只借用；返回 DockerResponse 的关闭责任交给 controller 的 finally/try-with-resources。
- exchange 在返回前失败时自行释放 lease；返回成功后由 DockerResponse.close 释放。传输完成前不能提前解锁。
- 默认单实例保守协调同仓库请求；PROXY GET 可能回填，必须与清理协调。跨仓库 mount 初始按 SPEC 回退普通上传。
- Location/Link 改写到可信公共地址，保留查询状态；不转发客户端 Authorization/Cookie 或后端内部地址。
- 后端成功但观察或审计失败不能伪装为存储回滚；记录待对账故障，不自动重放写操作。

应移除现有控制器中的 SQL、后端 HTTP、锁操作和权限策略。现有合并管理入口须拆为对应业务 Controller，
不是把旧类换个名称后继续保留全部职责。调用者继续使用 SPEC 路由，但错误格式、上传权限分类和分页须修正。

## Core Logic

### Docker 请求调用链

```mermaid
sequenceDiagram
    participant F as JwtAuthenticationFilter
    participant C as DockerGatewayController
    participant G as DockerGatewayService
    participant A as UserAuthorizationService
    participant L as DockerRepositoryLockService
    participant S as DockerStorageService / DockerProxyService
    participant R as DockerResponse
    participant E as DockerRepositoryLease
    F->>C: 可信 AuthenticatedUser + HTTP 请求
    C->>G: exchange(user, DockerRequest)
    G->>A: requireRepository(user, name, permission)
    A-->>G: DockerRepositoryRef
    G->>L: acquire(id, timeout)
    G->>S: exchange(repo, request) / pull(repo, request)
    S-->>G: DockerResponse
    G-->>C: 附带释放责任的 DockerResponse
    C->>C: 写入允许的响应头和正文
    C->>R: close()
    R->>E: close()，释放所持仓库协调权
```

身份由 005 建立；路径与方法绑定后形成 DockerRequest，服务仍校验操作与字段组合。
模式选择、授权、观察更新及审计都在服务侧，Controller 仅做 HTTP 转换。

### 仓库与页面调用链

```text
DockerRepositoryController.create
  -> DockerRepositoryService.create
     -> UserAuthorizationService.requireAdministrator
     -> DockerProperties 校验 alias
     -> DockerRepository.insert + AuditService.record（同一短事务）
     -> DockerRepositoryResponse

DockerRepositoryPageController.list / DockerRepositoryController.list
  -> DockerRepositoryService.list
     -> UserAuthorizationService.requireActive
     -> DockerRepository.findVisible
     -> PageResponse<DockerRepositoryResponse>
```

实体映射在服务内完成，页面和 JSON 使用明确 DTO，不返回 JdbcClient 结果 Map。
模板不访问数据库、实体的敏感字段或内部后端地址。

## Corners

| 边界 | 可观察行为与责任 |
|---|---|
| 空名称、非法方法组合或参数 | INVALID_INPUT，SPEC 400；无后端调用 |
| 未认证、无权限或错误受众 | 401/403；Registry 返回质询而非登录 HTML |
| 空目录 | 空页；授权过滤先于分页，不能泄露隐藏仓库 |
| 锁等待超时或停机 | UNAVAILABLE；未取得 lease 就不能发起后端请求 |
| 后端失败且响应未提交 | 映射 SPEC 的安全 502/503/504 错误 |
| 客户端取消或流中断 | 关闭 DockerResponse，释放 lease；已提交响应不追加 JSON |
| 重复注册 | repository 唯一约束转换为业务 CONFLICT，不暴露 SQL |
| 同仓库并发 | 与清理共用锁服务，锁只由业务入口持有，不由存储服务重复获取 |
| 停机 | 拒绝新 lease，有界等待在途操作，超时关闭资源；不承诺多实例协调 |

验收必须检查包与依赖：controller 禁止 repository/JDBC/HttpClient/锁依赖，service 禁止 Servlet 类型；
同时验证真实 Docker 登录、推拉、续传、多架构、HEAD/Range、分页和取消资源释放。
仅格式 lint 或类名修改不能证明设计已落实。
