# 分层实现验证记录

验收日期：2026-10-26。本记录对应本次分层源码、JWT 和 Thymeleaf 实现；旧 Session/Basic 版本的验收结果不作为本次证明。

| 验证 | 结果 | 可复现入口 / 覆盖 |
|---|---|---|
| Java lint | 通过 | `./scripts/lint.sh`；主代码和测试的 2 空格、禁硬 Tab、120 字符、类型和方法 Javadoc |
| 构建和单元测试 | 通过，13 项，0 失败 | `mvn -f application/pom.xml clean verify`；RSA、受众/签发者/时间拒绝、秘密脱敏、保留规则、流与锁的关闭/超时、包与依赖边界 |
| PostgreSQL 空库迁移 | 通过 | 独立 PostgreSQL 18.1；Flyway V1、V2 均成功；UUID 账号/授权、刷新和任务持久化实际使用 |
| 既有 V1 数据迁移 | 通过 | 独立 schema 中先建 V1 账号、授权和策略，再执行 V2；账号哈希、MANAGE 授权、策略值保留并关联稳定 UUID |
| JVM 私有与代理链 | 通过 | `scripts/smoke.py --age-fixtures --jdbc-url ...`；分块上传、Location、manifest 发布、下载内容校验、ACL 与即时撤销 |
| JVM 清理 | 通过 | 真实预览无删除、实际删除、保护标签、共享摘要与多架构依赖保留；任务结果可重新查询 |
| 公共 Docker Hub | 通过 | 真实 index、平台 manifest、按摘要校验的 layer 下载；代理写入拒绝 |
| 本地缓存清理 | 通过 | proxy-admin 注入独立缓存样本，独立策略、预览、删除与保护标签；不修改公共上游 |
| JWT 与状态事务 | JVM / Native 通过 | `scripts/auth-smoke.py`；CSRF、Basic/受众拒绝、冲突身份、并发刷新一次消费、重放撤销提交、退出、当前角色与密码变更失效 |
| 服务端页面 | JVM / Native 通过 | `node scripts/browser-smoke.cjs`；Chrome 关闭 JavaScript，登录、注册、用户创建、策略、预览、刷新、退出 |
| Native 编译和运行 | 通过 | GraalVM Community 25.0.2，macOS ARM64；真实后端 smoke、鉴权和浏览器流程均执行 |
| 定时与恢复 | 通过 | `scripts/recovery-smoke.py`；Native 运行时关闭时不执行，开启后 cron 成功；重启确认已缺失 INTENT，保留仍存在内容及 PLANNED 候选 |
| Docker CLI | 通过 | `scripts/smoke.py --docker`；真实 login、私有 pull/push/re-pull、公共代理 pull；临时凭据目录自动清理 |
| Compose 与密钥挂载 | 通过 | JVM/Native Compose 均通过 `config --quiet`；实际容器 UID 可读取只读 0600 测试密钥，未输出密钥 |
| 文档一致性 | 通过 | 五份产品设计的四个英文章节顺序、文档链接、完整类型名均检查；根文档保持概览 |

## 本次环境与隔离

| 项目 | 实际使用 |
|---|---|
| 应用 | 独立 JVM / macOS Native 测试进程，端口 18080 |
| 数据库 | 独立 PostgreSQL 18.1 进程，端口 17432，数据位于 `.local/refactor-test/pgdata` |
| 镜像后端 | Distribution 官方 v3.0.0 tag 源码构建的本机二进制，15001 / 15002 / 15003；独立私有与代理目录 |
| 公共出站 | 独立 proxy 使用本机 127.0.0.1:1087 出站代理连接 Docker Hub |
| 浏览器 | 真实 Chrome；1440×1000 与 390×844 截图保存在被忽略的 `.local` |
| 秘密 | 独立 RSA 密钥和随机测试密码，仅保存在被忽略的 `.local/refactor-test`，不提交 Git |
| 现有环境 | 经用户授权正常重启 OrbStack；原五个服务容器恢复，原 8080 登录入口响应 200；未用新源码升级原数据库 |

## 复现入口

先按 README 启动一个专用测试实例并设置 `ADMIN_PASSWORD`。

```sh
./scripts/lint.sh
mvn -f application/pom.xml clean verify
python3 scripts/smoke.py --url http://localhost:8080 --age-fixtures --docker
python3 scripts/auth-smoke.py --url http://localhost:8080
APP_URL=http://localhost:8080 node scripts/browser-smoke.cjs
```

独立 PostgreSQL 可用 `--jdbc-url jdbc:postgresql://127.0.0.1:17432/postgres` 替代 smoke 的 Compose psql。
`FixtureSql.java` 只用于验收样本 SQL，不属于应用数据访问层；数据库连接凭据从环境读取。
浏览器依赖使用 `.local/browser/node_modules/playwright`，可用 `PLAYWRIGHT_MODULE` 覆盖。

恢复测试先在调度关闭的专用实例执行 `recovery-smoke.py --jdbc-url ... prepare`，
使用短周期 cron 与 recovery-delay 配置运行 `check-disabled`；再重启测试实例开启调度并执行 `check-enabled`。
此脚本仅创建唯一命名的样本仓库和任务，但其 `--jdbc-url` 必须指向对应专用测试库。

## 验收边界

| 范围 | 结论 |
|---|---|
| 新版本部署 | 已验证隔离运行；原 8080 部署尚未升级到新 schema/实现 |
| Native 平台 | 本次实际运行 macOS ARM64；新分层版本的 Linux Native 容器与 x86_64 未运行验收 |
| 容器构建 | Compose 结构与密钥可读性已检查；本次未重新构建整套应用容器镜像 |
| 并发规模 | 验证刷新并发和仓库锁生命周期；未做压力、超大镜像或多实例验证；多实例仍不受支持 |
| 故障恢复 | 验证持久化中断状态的重启对账；未执行断电、文件系统损坏或全部网络故障组合 |
| 磁盘回收 | 已验证 manifest 逻辑删除；未运行离线 GC 或破坏性恢复 |

升级前须备份，并停止旧网关后应用 V2 迁移；旧 Session 重新登录，旧管理 Basic 客户端改用显式 JWT 交换。

## 2026-10-30 约束对齐审查

本节记录文档约束调整与静态检查。上方 2026-10-26 的构建和运行结果仅对应当时源码，
不证明当前新增的约束已在实现中满足。本次没有修改 Java、构建配置或 SQL，也没有重建或升级数据库。

### 已完成的文档变更

- 根 ARCHITECTURE/DESIGN 明确设计先于实现、全可见性声明、成员契约、方法表、调用链和证据边界。
- 保留 JWT、entity 数据实体和 model 业务值；选择 AppConfig/WebConfig、@Value 与 Repository 事务所有权。
- 开发迭代直接维护既有 SQL，禁止以修 checksum 掩盖差异；数据库重建仍需明确授权。
- 五份组件设计迁入 docs/designs，使用三位编号、下划线主题和 `_design.md` 后缀；关联引用同步更新。
- Registry 的业务角色、模式、alias、分页限额和部署约束归入 001；技术 SPEC 不再反向依赖组件设计。
- 去除组件设计的实现状态横幅，保持历史验收记录独立。

### 静态审查发现的契约与源码差距

| 范围 | 差距与后续验收要求 |
| --- | --- |
| 配置类 | 当前存在 ApplicationConfig、SecurityConfig、OpenApiConfig、NativeConfig；须按契约收敛归属 |
| 配置来源 | Application 仍扫描 ConfigurationProperties，五个 Properties 类型仍使用该注解 |
| 事务 | 多个 Service 仍有 Transactional；源码未迁入 Repository 事务边界 |
| JSON | DockerStorageService、DockerGatewayController 仍调用 readTree；尚未完成类型化协议绑定 |
| 声明覆盖 | 001—005 尚缺完整构造器、私有方法、字段生命周期和有效注解契约；方法表仍有旧格式 |
| 聚合事务 | 注册审计、刷新轮换、用户失效、策略审计、删除确认等尚需明确 Repository 聚合入口签名 |
| 配置契约 | @Value 的逐项表达式、集合配置、不可变 holder 构造器及全部 Bean 签名尚需明确 |
| 源码格式 | 中文 Javadoc、Objects 空值判断、显式 import、依赖命名及类型体布局未逐项验收 |
| 数据库 | 本次未验证修改建表脚本后的重建、回滚或重初始化；未创建新的 SQL 版本 |
| 组件排版 | 迁移前已有的长表格仍有超过 120 字符的行，不能据根文档检查宣称全库排版通过 |

聚合事务不能通过把多个细粒度 Repository 方法分别标为事务来替代。
必须先补齐并确认原子入口与调用链，保留用户更新/凭据失效、刷新重放撤销及删除确认的原子提交语义。
上述缺项属于受影响实现的前置契约，不能以本次治理规则更新为由直接发明签名、字段或额外组件。

### 本次检查边界

检查 Markdown 本地文件链接和内部锚点、旧目录/文件名残留、设计编号与四章节顺序，
以及根治理文档和独立 SPEC 的 120 字符限制；执行 git diff --check。
以关键词搜索核对新配置/事务/迁移选择与下层设计是否矛盾，并静态查看相关源码声明。
未运行 Maven、JVM、Native、数据库或真实 Docker 验收，不更新历史运行结论。
