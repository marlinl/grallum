# 配置与运维

## application.yml

应用配置统一位于 `application/src/main/resources/application.yml`。可用 `--spring.config.additional-location=file:/path/application.yml` 指定本地外置文件，不需要 `.env` 或 Java 属性文件。

| YAML 配置 | 默认值 | 环境变量 | 含义 |
|---|---|---|---|
| server.port | 8080 | PORT | 管理页面与 Registry 共用入口 |
| spring.datasource.url | jdbc:postgresql://localhost:5432/grallum | DATABASE_URL | PostgreSQL JDBC URL |
| spring.datasource.username | grallum | DATABASE_USER | 数据库用户 |
| spring.datasource.password | grallum-local | DATABASE_PASSWORD | 仅用于本地开发的默认密码 |
| spring.flyway.enabled | true | SPRING_FLYWAY_ENABLED | 默认必须开启 |
| spring.threads.virtual.enabled | true | SPRING_THREADS_VIRTUAL_ENABLED | Java 25 虚拟线程 |
| spring.servlet.multipart.enabled | false | — | 保持镜像上传原始流，不执行表单解析 |
| app.bootstrap.username | admin | ADMIN_USERNAME | 首次管理员账号 |
| app.bootstrap.password | 空 | ADMIN_PASSWORD | 首次必须设置，至少 12 位；数据库已有用户后不再覆盖 |
| app.cleanup.cron | `0 0 3 * * *` | CLEANUP_CRON | 显式 zone 中的每日 03:00 |
| app.cleanup.zone | UTC | CLEANUP_ZONE | 必须为有效 ZoneId |
| app.cleanup.scheduling-enabled | true | CLEANUP_SCHEDULING_ENABLED | 启用调度与恢复入口 |
| app.public-base-url | https://localhost | PUBLIC_BASE_URL | 可信公共 origin，用于 token realm 和 Location；不信任客户端 Host |
| app.security.cookies.secure | true | COOKIE_SECURE | false 仅允许显式回环 HTTP 开发配置 |
| app.security.jwt.issuer | grallum | JWT_ISSUER | 精确签发者 |
| app.security.jwt.active-key-id | local-1 | JWT_ACTIVE_KEY_ID | 必须存在于 verification-keys 中 |
| app.security.jwt.private-key-location | ./secrets/jwt-private.pem | JWT_PRIVATE_KEY_PATH | PKCS8 RSA 私钥；缺失启动失败 |
| app.security.jwt.verification-keys.local-1 | ./secrets/jwt-public.pem | JWT_PUBLIC_KEY_PATH | X509 RSA 公钥；至少 2048 位 |
| app.security.jwt.management-ttl / registry-ttl | 10m / 5m | 外部 YAML | 最大 1h / 15m |
| app.security.jwt.clock-skew | 30s | 外部 YAML | 最大 30s |
| app.security.refresh.absolute-ttl | 7d | 外部 YAML | 绝对族期限，最大 30d |
| app.registry.metadata-max-bytes | 8388608 | 外部 YAML | 元数据读取上限，超限失败关闭 |
| app.registry.connect-timeout / metadata-timeout / transfer-timeout | 10s / 60s / 30m | 外部 YAML | 连接、元数据与流读取期限 |
| app.registries.private.url | http://localhost:5001 | PRIVATE_REGISTRY_URL | 私有 Distribution 内部地址 |
| app.registries.private.mode | PRIVATE | — | 可上传、可执行清理 |
| app.registries.dockerhub.url | http://localhost:5002 | PROXY_REGISTRY_URL | 缓存 Distribution 内部地址 |
| app.registries.dockerhub.mode | PROXY | — | 客户端只读；缓存通过管理通道执行逐仓库策略 |
| app.registries.dockerhub.management-url | http://localhost:5003 | PROXY_MANAGEMENT_URL | 共享缓存卷的内部管理 Registry，仅供服务端访问 |
| springdoc.swagger-ui.csrf.enabled | true | — | 管理交互建议使用显式 management Bearer |

增加其他公共 Registry：部署独立 Distribution 缓存服务，设置其 `proxy.remoteurl`，同时部署共享其缓存卷的无 proxy 配置管理 Registry，在 `app.registries` 增加别名、URL、management-url 和 `mode: PROXY`，缓存端 TTL 设为 0。每个缓存服务只对应一个上游。别名使用小写字母、数字和连字符。

## 基础设施配置

| 文件 / 字段 | 默认值 | 作用 |
|---|---|---|
| compose.yml / postgres.image | postgres:18.1-alpine | 开发数据库，命名卷持久化 |
| compose.yml / private、proxy、proxy-admin.image | registry:3.0.0 | 固定 Distribution 版本 |
| compose.yml / application profiles | app | 默认仅启动依赖；`--profile app` 启动全容器栈 |
| deploy/registry-private.yml / storage.delete.enabled | true | 支持私有仓库按摘要删除 |
| deploy/registry-*.yml / storage.redirect.disable | true | blob 流经过网关，避免暴露未鉴权后端位置 |
| deploy/registry-*.yml / http.relativeurls | true | 上传位置重写兼容本地和容器部署 |
| deploy/registry-proxy.yml / proxy.remoteurl | https://registry-1.docker.io | 公共上游 |
| deploy/registry-proxy.yml / proxy.ttl | 0s | 禁用全局 TTL，由逐仓库策略统一决定保留；拉取可重新填充 |
| proxy-admin / ports | 127.0.0.1:5003 | 只供受信任本机运维，生产移除映射，严禁公开 |
| REGISTRY_HTTPS_PROXY / REGISTRY_HTTP_PROXY | 空 | 公共缓存容器的出站网络代理 |
| Registry / OTEL_TRACES_EXPORTER | none | 默认关闭未配置的遥测导出，避免无意义的连接错误 |

## 启停与构建

| 操作 | 命令 |
|---|---|
| 依赖启动 | `docker compose up -d postgres private proxy proxy-admin` |
| JVM 开发 | `mvn -f application/pom.xml spring-boot:run` |
| 单元测试 / JAR | `mvn -f application/pom.xml clean verify` |
| JAR 启动 | `java -jar application/target/application-0.1.0-SNAPSHOT.jar` |
| Native 编译 | `JAVA_HOME=/path/to/graalvm25 mvn -f application/pom.xml -Pnative native:compile` |
| Native 启动 | `./application/target/application` |
| 整栈 JVM 容器 | `docker compose --profile app up -d --build` |
| Native 容器构建 | `docker build -f application/Dockerfile.native -t grallum-native application` |
| 整栈 Native 容器 | `docker compose -f compose.yml -f compose.native.yml --profile app up -d --build` |
| 查看容器状态 | `docker compose ps -a` |
| 查看代理故障 | `docker compose logs --tail=100 proxy` |
| 停止依赖并保留数据 | `docker compose down` |

不使用 `docker compose down -v`，除非明确需要销毁本地所有镜像及数据库数据。GraalVM 的二进制与构建平台绑定；macOS 构建不能直接作为 Linux 容器二进制使用。

Native Dockerfile 使用 `25-ol9` 构建镜像，保持 glibc 与 Debian 12 运行镜像兼容，并携带动态链接所需的 zlib；不要直接换成默认 `25` 标签（其基础系统可能更新）。两个 Dockerfile 共享 BuildKit Maven 缓存，首次构建需要下载依赖。

## 离线垃圾回收

逻辑清理不会立即减少 blob 占用；安排维护窗口、停止应用的写入与清理任务后，再执行。先备份数据卷。

```sh
# 本机 java/native 进程也必须先停止；这里只停止 Compose 中的应用。
docker compose --profile app stop application
docker compose stop private
docker compose run --rm --no-deps private garbage-collect /etc/distribution/config.yml
docker compose start private
docker compose --profile app start application
```

默认 GC 不加 `--delete-untagged`，避免误删多架构索引引用的未打标签 manifest。该操作仅针对私有卷。回收代理卷时必须同时停止 `proxy` 和 `proxy-admin`，使用 `docker compose run --rm --no-deps proxy-admin garbage-collect /etc/distribution/config.yml`，完成后启动两者。保持应用停止直到两个后端恢复。

## 备份与恢复

| 数据 | 备份方式 | 恢复要求 |
|---|---|---|
| PostgreSQL | `docker compose exec -T postgres pg_dump -U grallum -Fc grallum > grallum.dump` | 使用 pg_restore 到兼容 PostgreSQL，保留 Flyway 表 |
| 私有镜像 | 停写后备份 `grallum_private` 命名卷 | 与数据库同一维护窗口的快照一起恢复 |
| 代理缓存 | 备份 `grallum_proxy`，或接受从公网重新拉取 | 上游不可用时不能依赖重拉 |
| 配置与凭据 | 独立保存实际 application.yml、Registry YAML、TLS 配置 | 不放入公开 Git、不输出密码日志 |

## 故障定位

| 现象 | 检查 |
|---|---|
| 首次启动失败 | ADMIN_PASSWORD 至少 12 位；PostgreSQL 健康；5432 无冲突 |
| proxy 容器重启 | 到 Docker Hub 的 DNS、HTTPS、代理地址；代理中的 localhost 指向容器自身 |
| push 404 | 页面是否注册准确的 registry/name；镜像地址必须包含 private 别名 |
| pull manifest 404 | tag 是否存在；自写 HTTP 客户端要发送 OCI/Docker manifest Accept |
| 403 | 仓库授权、用户启用状态；管理变更额外检查 CSRF Cookie 和 token |
| public push 405 | 公共代理设计为只读 |
| 清理无候选 | 观察年龄、保留数量、保护标签、共享 digest、多架构依赖保护 |
| 删除后空间未释放 | 按上述停写流程执行离线 GC |
| 重建时运行进程类加载异常 | 停止正在使用 target JAR 的进程，或先复制 JAR 到独立运行路径 |

## 密钥、迁移和恢复

| 项目 | 操作与边界 |
|---|---|
| 开发密钥 | `./scripts/dev-keys.sh` 只在 `.local/secrets` 首次生成，文件为 0600，不覆盖旧密钥 |
| Compose 读取 | 本机 `export APPLICATION_UID=$(id -u)`；挂载只读目录到 `/run/grallum-secrets` |
| 生产 | 配置 HTTPS 公共 origin、Secure Cookie 和运行 UID 可读的私钥，不提交秘密到 Git |
| 密钥轮换 | 外部 YAML 同时配置新 active-key-id、私钥和可信公钥 map；保留旧验签公钥至旧 token 最长有效期及偏差结束 |
| V2 迁移 | 将账号主键和授权关联迁至稳定 UUID，保留账号哈希和授权；增加刷新与清理任务表 |
| 旧客户端 | Session 重新登录；管理 Basic 改为 JSON 交换管理 JWT；Docker 跟随 Bearer challenge |
| 升级运行环境 | 先备份数据库和镜像卷；停止旧网关后迁移并启动新版本，不能让旧代码继续使用 V2 schema |
| 清理恢复 | 重启后只对账未确认 INTENT，已存在内容标为 UNCERTAIN；不自动继续剩余候选删除 |
| 验收隔离 | smoke 会创建唯一名称用户与镜像；`--age-fixtures` 仅限测试库，`--jdbc-url` 可指定独立 PostgreSQL |

普通镜像请求不建立长数据库事务。不得通过删除失败回滚记录来掩盖已经发生的外部删除。
