# 实现导览

架构约束以 [ARCHITECTURE.md](../ARCHITECTURE.md) 为准，产品方法契约以 [DESIGN.md](../DESIGN.md) 为准。
本页只提供源码导航，不为违反设计的代码建立例外。

| 入口 / 能力 | 调用链 |
|---|---|
| Docker 协议 | DockerGatewayController → DockerGatewayService → UserAuthorizationService → DockerStorageService / DockerProxyService |
| 仓库管理 | DockerRepositoryController / DockerRepositoryPageController → DockerRepositoryService → DockerRepository |
| 本地标签 | DockerImageController → DockerImageService → DockerStorageService → DockerTagObservationRepository |
| 清理 | DockerCleanupController / DockerCleanupPageController / DockerCleanupTask → DockerCleanupService → DockerStorageService + DockerCleanupProgressService |
| 登录与刷新 | UserAuthController → UserAuthService → UserTokenStateService + JwtTokenCodec |
| 请求身份 | JwtAuthenticationFilter → UserAuthService.authenticate → JwtTokenCodec + UserTokenStateService |
| 授权 / 用户 | UserPermissionController / UserController / UserPageController → UserAuthorizationService / UserManagementService → 对应 Repository |

根包仅保留 Application。数据库实体、外部 DTO、内部模型分别位于 entity、dto、model；配置位于 config。
控制器和任务不执行 SQL；Service 不接收 Servlet 对象。DockerRepositoryLockService 在单实例内协调流式操作和清理。
DockerResponse 拥有后端流及附属 lease；超时只关闭流，持有线程负责释放仓库锁。

PostgreSQL 保存账号、权限、刷新族与哈希、策略、观察、清理任务和审计；Distribution 保存镜像字节。
清理意图在 DELETE 前提交，确认结果在独立短事务中保存；数据库事务不能回滚外部删除。
代理元数据和删除使用共享缓存卷的 proxy-admin，绝不把清理请求发往公共上游。

模板位于 `application/src/main/resources/templates/`，静态样式位于 `static/assets/`。
浏览器使用 host-only HttpOnly Cookie 与 CSRF；管理机器请求和 Docker 使用不同 audience 的 Bearer。
JVM、Native 和实际部署的验收边界见 [验证记录](verification.md)。
