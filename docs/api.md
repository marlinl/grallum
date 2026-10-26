# API 与权限

本文说明分层实现的管理入口；协议以 [Docker Registry SPEC](spec/docker-registry-api.md) 为准，
类、方法和调用链见 [DESIGN.md](../DESIGN.md)。交互文档位于 `/swagger-ui.html`，JSON 位于 `/v3/api-docs`。

## 身份与 CSRF

| 客户端 | 认证 | 写操作 |
|---|---|---|
| 浏览器 | GET/POST `/login`，HttpOnly JWT 与刷新 Cookie；无 HttpSession | Thymeleaf 表单自动带 CSRF，JavaScript 可关闭 |
| 管理 API | POST `/api/auth/token`，显式 JSON `username/password`，得到管理 Bearer | 显式 Bearer 不依赖 CSRF；Cookie 请求仍必须提供表单 CSRF |
| Docker | `/v2/` 的 Bearer challenge → GET `/auth/registry/token`，仅交换端点使用 Basic | Registry audience、签名 scope 与当前仓库 ACL 同时检查 |
| 刷新 | POST `/auth/refresh`，刷新 Cookie 和 CSRF | 单次消费、秘密轮换；重放提交族撤销后拒绝 |
| 注销 | POST `/auth/logout`，刷新 Cookie 和 CSRF | 同族旧访问令牌失效，清除本机 Cookie |

管理与 Registry 令牌不能互换。资源端点不接受 Basic。账号状态、凭据版本、当前角色与授权在请求时检查。
Swagger 的 Authorize 使用 `/api/auth/token` 得到的管理 Bearer；不能用 Registry token。
旧 Session 不迁移，需要重新登录。

## 管理端点

| 方法 | 路径 | 权限 / 契约 |
|---|---|---|
| GET / POST | `/api/repositories` | 当前可见仓库 / 管理员注册；创建返回 201 |
| GET | `/api/repositories/{repositoryId}/tags` | READ；只读本地标签，代理不会因列举而拉取上游 |
| GET / PUT | `/api/repositories/{repositoryId}/permissions` | MANAGE；`{username,permission}` |
| DELETE | `/api/repositories/{repositoryId}/permissions/{username}` | MANAGE；即时撤销 |
| GET / PUT | `/api/repositories/{repositoryId}/policy` | READ / MANAGE；保存时必须提交 expectedVersion |
| POST | `/api/repositories/{repositoryId}/cleanup?dryRun=true` | MANAGE；默认预览 |
| POST | `/api/repositories/{repositoryId}/cleanup?dryRun=false` | MANAGE；必须先启用策略，重新读取完整快照 |
| GET | `/api/repositories/{repositoryId}/cleanup/{runId}` | READ；只能查看属于此仓库的任务 |
| GET / POST | `/api/users` | 管理员；创建返回 201；不返回密码哈希 |
| PUT | `/api/users/{username}` | 管理员；`{enabled,admin,password?}`，不能自我停用或降级 |

列表使用 `?n=100&last=...`，n 为 1–1000，响应为 `{items,next}`；next 为 null 时没有下一页。
仓库游标为 `alias/name`，标签游标为 tag。用户名和仓库名不是可自由替换的 URL 路径。

```json
{"keepLatest":10,"olderThanDays":30,"protectedPattern":"latest,stable,release-*","enabled":false,"expectedVersion":0}
```

初次策略版本为 0；保存返回新 version。旧版本写入返回 409。
清理响应包含 `runId,dryRun,status,candidateDigests,deletedDigests,failures`；调用者必须检查 status。
PARTIAL/FAILED 不能因为 HTTP 请求成功就当作所有候选已删除；INTENT 未确认项需要对账。

| 权限 | 允许的仓库操作 |
|---|---|
| READ | 本地标签、策略、任务结果和 pull |
| WRITE | READ 及私有仓库 push、上传状态、取消上传 |
| MANAGE | WRITE 及删除、授权变更、策略变更和清理 |
| 管理员 | 注册仓库和用户管理；仍受 Registry token scope 与代理只读限制 |

## 页面入口

| 页面 | 作用 |
|---|---|
| `/`、`/repositories` | 可见仓库与注册表单 |
| `/repositories/{repositoryId}` | 仓库详情和业务入口 |
| `/repositories/{repositoryId}/tags` | 本地标签与观察时间 |
| `/repositories/{repositoryId}/permissions` | 授权、撤销表单 |
| `/repositories/{repositoryId}/cleanup` | 版本化策略、预览及独立确认执行表单 |
| `/users` | 用户创建、状态与密码变更 |

## 失败语义

| 状态 | 含义 |
|---|---|
| 400 | 输入或凭据交换格式错误 |
| 401 | 未认证、过期、撤销、受众错误或账号不可用 |
| 403 | 当前权限不足、身份冲突或 CSRF 失败；冲突凭据也可按无效身份返回 401 |
| 404 | 授权后的资源不存在；隐藏仓库不会向无权用户泄露存在性 |
| 405 | 不支持的协议操作，或代理写入 |
| 409 | 资源重复、策略版本冲突、禁用清理或非法自我管理 |
| 429 | 凭据交换限流或上游限流 |
| 502 / 503 / 504 | 后端失败 / 当前服务或状态存储不可用 / 超时 |

管理 API 返回安全的 `code/message`；Docker 返回 SPEC 错误 envelope，HEAD 不带正文。
不返回后端凭据、内部 URL、SQL、完整异常或原始不可信错误正文。
