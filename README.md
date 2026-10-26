# Grallum Registry

Java 25 / Spring Boot 4 的私有 Docker Registry 管理服务，支持 GraalVM Native Image。Maven 坐标：`com.grallum.application:application`。

| 功能 | 实现 |
|---|---|
| 私有镜像 | Distribution v3 保存镜像；Spring Boot 网关提供 Docker Registry v2 入口 |
| 公共 Registry 代理 | Distribution pull-through cache，默认 Docker Hub，支持多架构镜像；只读 |
| 管理页面 | Thymeleaf 登录、仓库列表、标签、用户、授权、清理预览/执行；无 JavaScript 可用 |
| 权限 | RS256 JWT、刷新轮换与撤销；全局管理员；每个仓库 READ / WRITE / MANAGE，默认拒绝 |
| 清理策略 | 私有/代理仓库独立配置保留数量、最短年龄、保护标签和定时开关 |
| 数据库 | PostgreSQL 18；Flyway 自动版本迁移；无 H2 替代 |
| API 文档 | `/swagger-ui.html` 与 `/v3/api-docs`，需要登录 |
| 配置 | `application/src/main/resources/application.yml`；支持同名外部 YAML 和环境变量覆盖 |

## IntelliJ IDEA 导入

| 项目 | 设置 |
|---|---|
| 打开入口 | 打开仓库根目录的 `pom.xml`，选择作为项目打开；根 POM 会导入 `application` 模块 |
| 已打开目录 | 在 Maven 工具窗口关联根 `pom.xml`，然后执行 Reload All Maven Projects；若未刷新，关闭并重新打开此项目 |
| Project SDK / Maven JDK | Java 25 |
| 应用入口 | `application/src/main/java/com/grallum/application/Application.java` |
| 配置来源 | Maven 自动生成模块、源码目录和依赖；`.idea/` 和 `*.iml` 为本机配置，不提交 Git |

根 POM 只聚合模块，`application/pom.xml` 仍直接继承 Spring Boot parent，也可独立导入、构建和制作 Docker 镜像。

## 本地启动

前提：Java 25、Maven 3.9+、可运行的 Docker Compose。命令从仓库根目录执行。

```sh
docker compose up -d postgres private proxy proxy-admin
./scripts/dev-keys.sh
export PUBLIC_BASE_URL=http://localhost:8080 COOKIE_SECURE=false
export JWT_PRIVATE_KEY_PATH="$PWD/.local/secrets/jwt-private.pem"
export JWT_PUBLIC_KEY_PATH="$PWD/.local/secrets/jwt-public.pem"
# 首次启动必须设置至少 12 位管理员密码；不要把真实密码提交到 Git。
read -s 'ADMIN_PASSWORD?首次管理员密码: '
export ADMIN_PASSWORD
mvn -f application/pom.xml spring-boot:run
```

`read -s 'NAME?提示'` 为 macOS 默认 zsh 语法；其他 shell 可直接在本地 YAML 中设置 `app.bootstrap.password`。

| 入口 | 地址 / 账号 |
|---|---|
| 管理页面 | http://localhost:8080/ |
| Swagger | http://localhost:8080/swagger-ui.html |
| 管理员 | `admin` / 首次启动时设置的密码 |
| Docker 登录 | `docker login localhost:8080` |
| PostgreSQL | `localhost:5432/grallum`；本地开发账号 `grallum` |

已存在用户时不会重置管理员密码。可在页面“用户管理”重置。首次 bootstrap 密码未设置时应用会拒绝完成启动。

网络需要代理时，只给公共缓存容器设置可从容器访问的代理地址，例如 Docker Desktop / OrbStack：

```sh
REGISTRY_HTTPS_PROXY=http://host.docker.internal:1087 docker compose up -d proxy
```

端口按实际本机代理调整。该配置不改变应用数据库或私有 Registry 连接。

## 使用

1. 登录页面，注册 `private` 下的 `team/app`，或 `dockerhub` 下的 `library/hello-world`。
2. 在用户管理创建用户，再在仓库详情授予 READ / WRITE / MANAGE。
3. 使用完整的 `registry 别名/仓库路径` 访问：

```sh
docker login localhost:8080
docker tag your-image:tag localhost:8080/private/team/app:tag
docker push localhost:8080/private/team/app:tag
docker pull localhost:8080/private/team/app:tag
docker pull localhost:8080/dockerhub/library/hello-world:latest
```

公共代理的 `library/` 不能省略。网关只允许访问预先注册且已授权的仓库。跨仓库 blob mount 自动退化为普通上传，以免绕过源仓库读权限。

## 构建与验证

Java 使用 2 空格缩进、禁止硬 Tab、每行最多 120 字符；类型及方法必须有 Javadoc。
本地执行 `./scripts/lint.sh`，或 `mvn validate`。Checkstyle 绑定 Maven `validate`，构建时自动检查主代码和测试代码。
自动格式化使用 `mvn -f application/pom.xml spotless:apply`，随后重新运行 lint。

```sh
mvn -f application/pom.xml clean verify
java -jar application/target/application-0.1.0-SNAPSHOT.jar

# JAVA_HOME 指向 GraalVM 25；macOS 需要 Xcode Command Line Tools。
mvn -f application/pom.xml -Pnative native:compile
./application/target/application

# 对已启动服务做真实依赖端到端验证；会创建 smoke 仓库和用户。
python3 scripts/smoke.py --age-fixtures --docker
python3 scripts/auth-smoke.py
node scripts/browser-smoke.cjs
```

`--age-fixtures` 通过本项目 Compose PostgreSQL 仅回溯本轮 smoke 仓库的观察时间，以验证真实删除。不要对生产数据库运行验收脚本。自定义服务地址用 `--url`；自定义测试库用 `--database`。脚本从 `ADMIN_PASSWORD` 读取密码，或使用本地验收生成的 `.local/admin-password`（已忽略，权限 0600）。

本机 Compose 应先运行 `./scripts/dev-keys.sh`，并设置 `export APPLICATION_UID=$(id -u)`，使只读挂载的 0600 密钥可被容器用户读取。生产应由秘密管理设施给运行 UID 提供只读密钥，不能降低私钥文件权限。

整套 JVM 容器部署：`docker compose --profile app up -d --build`（首次需要 `ADMIN_PASSWORD`）。整套 Native 部署：`docker compose -f compose.yml -f compose.native.yml --profile app up -d --build`。也可单独构建：`docker build -f application/Dockerfile.native -t grallum-native application`。启动和构建应使用不同文件副本，避免重建覆盖运行中的 JAR。

## 部署边界

| 项目 | 要求 |
|---|---|
| 拓扑 | 当前支持单个 Spring Boot 网关实例；仓库锁在进程内，暂不支持多副本并发清理 |
| 外网入口 | 生产设置 PUBLIC_BASE_URL 为 HTTPS 公共 origin、COOKIE_SECURE=true；Basic 仅用于 Docker token 交换，不用于资源认证 |
| 后端隔离 | Registry / PostgreSQL 只映射回环端口；生产应移除映射，仅保留内部网络；禁止客户端绕过网关直写后端 |
| 清理范围 | 私有仓库与代理缓存均按独立策略删除本地摘要；代理删除不影响上游 |
| 年龄含义 | 首次观察到标签当前摘要的时间，不是镜像构建时间；既有镜像首次同步后重新开始保留期 |
| 多架构 | 自动清理保留索引及其递归引用；不尝试自动回收多架构索引 |
| 磁盘回收 | 清理删除 manifest 引用；空间回收需停止写入后运行 Distribution 离线 GC |
| 上游限制 | Docker Hub 匿名限流/网络故障原样影响拉取；代理页面仅列出本地缓存的标签 |
| 备份 | 一起备份 PostgreSQL 和私有/代理两个镜像数据卷；密码及代理凭据不得提交 Git |

架构与 Java 规范：[ARCHITECTURE.md](ARCHITECTURE.md)。产品设计总览：[DESIGN.md](DESIGN.md)，关联 `docs/design/001～999-*.md` 编号设计。协议：[Docker Registry API spec](docs/spec/docker-registry-api.md)。具体已执行验收与尚未验证的平台见验证记录；源码更新不会自动升级现有运行实例。

当前实现文档：[架构](docs/architecture.md) · [配置与运维](docs/operations.md) · [API 与权限](docs/api.md) · [验证记录](docs/verification.md)
