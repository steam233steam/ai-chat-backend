[![Backend CI](https://github.com/steam233steam/ai-chat-backend/actions/workflows/ci.yml/badge.svg)](https://github.com/steam233steam/ai-chat-backend/actions/workflows/ci.yml)

# MindButler：会记事、能派活的个人 AI 助手（后端）

一个 Spring Boot 3 + Spring AI 的对话后端。**它不是聊天套壳**：除了多轮对话，它会把你说过的偏好记下来、
把"下周三交作业"变成任务、需要外部信息时自己去查，并且在写库之前让你确认一次。

> 前端仓库：[ai-chat-frontend](https://github.com/steam233steam/ai-chat-frontend)  
> 文档索引：[docs/README.md](docs/README.md) · 排期表：[docs/路线图.md](docs/路线图.md) · 读代码入口：[docs/啃代码路线.md](docs/啃代码路线.md)

## 它和"套壳 ChatGPT"的区别

| 能力 | 普通套壳 | 这个项目 |
|---|---|---|
| 记住用户 | 靠上下文窗口，关掉就没了 | 结构化记忆（4 个维度 + confidence + 有效期），按 `confidence × 0.5^(闲置天数/半衰期)` 决定注入顺序 |
| 处理"下周三" | 说一句"我记住了"，其实什么都没有 | 意图层解析成任务（固定 schema + confidence），确认后落库、到点可查 |
| 改/删记忆 | 做不到，或模型直接删 | 解析出待改条目 + before/after 预览，确认才写；解析不出来就追问 |
| 查外部信息 | 没有 | MCP 工具：时间、天气、必应搜索、网页正文、沙箱内读写笔记 |
| 用户信任 | —— | 敏感信息（密码/证件/卡号）不进库；所有写操作先给预览 |

## 一次对话的路径

```text
POST /chat/ask-stream  (Flux<String> → SSE)
   │
   ├─ RefreshTokenInterceptor    Authorization → Redis → UserHolder(ThreadLocal)
   ├─ LoginInterceptor           未登录 401
   ├─ ChatDailyLimiter           按用户按天限流（Redis，异常降级进程内）
   │
   ├─ IntentService              有"记住/提醒/改成/删掉"意图？
   │    ├─ 敏感信息过滤 → 直接拒绝
   │    ├─ IntentExtractor（固定 schema + confidence + 超时兜底）
   │    └─ 写入 pending（memory / task / memory_change），只回一行标记
   │         └─ 前端弹窗确认 → POST /user/pending-actions/{id}/confirm → 落库
   │
   ├─ PromptBuilder              人设 + 注入记忆（按衰减排序，最多 5 条）+ 最近 N 条历史
   ├─ ModelRouter                Ollama 本地 / DeepSeek 云端（会话首条消息锁模型）
   ├─ MCP 工具                   按模型白名单挂载，模型自己决定调不调
   └─ ChatPersistenceService     事务内双写：MySQL 持久化 + Redis 7 天热缓存
```

## 三个取舍（面试会问）

| 取舍 | 选了什么 | 为什么 | 代价 |
|---|---|---|---|
| 认证 | **不用 Spring Security**，自己写两个拦截器 + Redis Token | 演示项目要能讲清"登录态到底是什么"；Security 的过滤器链对初学者是黑盒 | 越权、CSRF、密码哈希这些得自己补齐（BCrypt、userId 校验、401/403 分支都手写过） |
| 记忆衰减 | **查询时现算**，不落库、不定时任务 | 定时任务要处理"没跑""跑重了""时区"；现算没有一致性问题，改半衰期只改配置 | 每次注入多几次浮点运算；没法按权重建索引 |
| MCP | **自实现客户端**，不用官方 starter | `spring-ai` 1.0.0-M5 的 BOM 里没有 MCP 模块，升 1.0.0 要连带升 Boot 3.4，不划算 | 协议（stdio + 换行 JSON-RPC）、超时、错误码、进程管理都得自己写 |

## 两个数字

- **309** 个自动化测试全绿（含 8 个真实连 MySQL/Redis 的集成测试），CI 每次提交跑
- **12** 条人工验收用例（[`docs/P1-上线验收清单.md`](docs/P1-上线验收清单.md)）已逐条执行并留了记录，含弹窗、倒计时、跨用户隔离

## 技术栈

Spring Boot 3.2.5 · Spring AI 1.0.0-M5 (Ollama / OpenAI 兼容) · DeepSeek · MySQL · MyBatis · Redis · Hutool · Java 17

## 功能特性

- **双方式登录**：手机验证码登录 + 密码登录（BCrypt 加密）
- **新用户引导**：首次登录自动标记，需完善密码/昵称
- **多轮对话**：Redis 缓存会话上下文，MySQL 持久化，支持流式 SSE 输出
- **多模型切换**：`ModelRouter` 按模型名路由 Ollama / DeepSeek，白名单配置化
- **会话锁模型**：会话首条消息锁定模型，换模型需新建会话（409），避免跨模型上下文污染
- **长对话友好**：历史消息游标分页（`beforeId` + `limit`），只把最近 N 条历史送入模型
- **多用户隔离**：会话数据按 userId 隔离，含越权校验
- **数据可恢复**：Redis 7 天 TTL 到期后，历史消息、会话列表和标题可从 MySQL 回源
- **能用工具**：MCP 工具按模型白名单自动挂载，模型自己决定何时调用 —— 自带时间/天气/必应搜索/网页正文，另接官方 `server-filesystem`（根目录锁在 `notes/`）读写笔记，见 [`docs/mcp.md`](docs/mcp.md)
- **工程化**：三层架构 + 全局异常处理 + 拦截器链 + 参数校验
- **一体化集成测试**：`FullFlowIntegrationTest` 13 步全链路端到端验收测试（真实连 MySQL/Redis/Ollama，不 Mock）

## MindButler P1（长期记忆 + 口语化任务）⭐

P1 把"省用户惦记时间"做进聊天：固定 4 维度结构化画像（`style/fact/schedule/relation`）+ 口语化任务，
候选先走 pending 状态机（10 分钟 TTL），用户"确认/拒绝"后才落库——不连向量库、可解释可审计。

| 能力 | 接口 | 说明 |
|------|------|------|
| 长期记忆 | `/user/memories` | 手动 CRUD + 注入查询；4 维度 mem_key + `replaces_id` 替换 + dynamic TTL；注入顺序按 `confidence × 0.5^(闲置天数/半衰期)` 现算（[memory-decay.md](docs/memory-decay.md)） |
| 记忆变更 | `/user/pending-actions` | 聊天里说"把目标改成明年考公""把作息的记忆删掉"→ 规则解析出待改条目 + before/after 预览 → 确认才写；宁可追问不猜（[memory-change.md](docs/memory-change.md)） |
| 口语化任务 | `/user/tasks` | CRUD + 完成/重复滚动 + 到期查询（`once/daily/weekly`，weekly 带 `BYDAY=XX`） |
| 待确认动作 | `/user/pending-actions` | pending→confirmed/rejected/expired 状态机；确认按 payload 实际内容分 memory/task/mixed 落库 |
| 意图拦截 | `/chat/ask` 内置 | 说"明天 9 点交作业 / 记住我喜欢简洁"→ LLM 提取候选 → 写 pending → 二次确认；普通聊天不受影响 |
| 到期提醒 | `/user/tasks/due` + 前端轮询 | 页面可见时每 30 秒检查一次，只弹 10 分钟内到期任务，按 `taskId + dueAt` 去重；页面关闭/后台不保证提醒 |

**迁移**：现有库按顺序跑 `docs/migration/2026-11-02-mindbutler-p1.sql` → `2026-11-03-mindbutler-p1-memory-session.sql` → `2026-11-05-mindbutler-p15-task-session.sql`；全新库直接 `docs/schema.sql`（已含 3 表及 P1.5 列）。
**设计文档**：`docs/MindButler-P1设计.md`。

## 快速启动

**依赖服务**：MySQL (aichat 库) · Redis · Ollama

```bash
# 复制并填写本地密码
cp src/main/resources/application-local.properties.example src/main/resources/application-local.properties

# 使用 DeepSeek 时配置 key（不提交到 Git）
export DEEPSEEK_API_KEY=sk-xxx

# 启动
mvn spring-boot:run
# 服务端口 8080，CORS 已放行本地开发端口
```

完整启动步骤见 `docs/本地启动.md`，建库脚本见 `docs/schema.sql`。

## API 一览

### 用户接口 `/user`
| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/user/code` | 发送验证码（60s 冷却） |
| POST | `/user/login` | 验证码登录 |
| POST | `/user/login/password` | 密码登录 |
| POST | `/user/register` | 手机号 + 密码注册（无需短信；按 IP 每日限流 + 同号 60s 冷却） |
| PUT | `/user/set-password` | 设置密码 |
| PUT | `/user/profile` | 修改昵称 |
| GET | `/user/me` | 获取当前用户 |
| GET | `/user/is-new-user` | 检查是否新用户 |
| POST | `/user/logout` | 登出 |

### 聊天接口 `/chat`
| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/chat/models` | 可用模型白名单 |
| POST | `/chat/ask` | 同步对话（含 MindButler 意图拦截：命中记忆/任务意图直接返回确认文案，不调聊天模型） |
| POST | `/chat/ask-stream` | 流式对话（SSE），JSON body：`{sessionid, question, modelName}` —— **前端用这个** |
| GET | `/chat/ask-stream` | 同上，query 传参；已标 `@Deprecated`，仅兼容旧集成测试 |
| GET | `/chat/history` | 会话历史（可选 `beforeId` + `limit` 分页） |
| GET | `/chat/sessions` | 会话列表 |
| PUT | `/chat/title` | 更新标题 |
| DELETE | `/chat/clear-session` | 清空会话 |

### MindButler P1 接口
| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/user/memories` | 创建记忆（4 维度 mem_key + mem_type + 敏感词过滤） |
| GET | `/user/memories` | 记忆列表（active + superseded，含 `expired` 瞬态字段） |
| GET | `/user/memories/export` | 全量导出（含 superseded，供隐私/迁移） |
| GET | `/user/memories/{id}` | 查单条记忆 |
| PUT | `/user/memories/{id}` | 更新记忆（乐观锁 version） |
| DELETE | `/user/memories/{id}` | 软删记忆（幂等） |
| GET | `/user/tasks` | 任务列表（游标分页 beforeId + limit） |
| GET | `/user/tasks/due` | 到期待办查询 |
| GET | `/user/tasks/{id}` | 查单条任务 |
| POST | `/user/tasks` | 创建任务（once/daily/weekly，weekly 必带 BYDAY=XX） |
| PUT | `/user/tasks/{id}` | 更新任务（乐观锁 version） |
| POST | `/user/tasks/{id}/complete` | 完成任务（once→done；daily/weekly→滚动到下一次） |
| DELETE | `/user/tasks/{id}` | 软删任务（幂等） |
| GET | `/user/pending-actions/current?sessionId=` | 查当前待确认动作（懒过期） |
| POST | `/user/pending-actions/{id}/confirm` | 确认（原子 + 幂等；按 payload 内容分 memory/task/mixed 落库） |
| POST | `/user/pending-actions/{id}/reject` | 拒绝（幂等） |

### MCP 工具接口 `/mcp`（需登录）
| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/mcp/tools` | 列出当前已连接的工具（名 / 所属服务 / 描述） |
| POST | `/mcp/tools/{name}` | 直接调用某个工具，请求体是它的参数 JSON；用于不经过模型复现问题 |

工具实现见 `mcp-servers/info-server.mjs`，配置与扩展方式见 [`docs/mcp.md`](docs/mcp.md)。

接口清单以 **`/swagger-ui/index.html`**（springdoc 自动生成）为准；上面这张表只列常用入口。

## 架构设计

```
Controller  →  Service  →  Repository / Mapper  →  Redis / MySQL
     │
     └── 拦截器链：RefreshTokenInterceptor → LoginInterceptor
```

- **Controller**：接收请求、参数校验、路由
- **Service**：业务逻辑（用户认证、对话流程、越权校验）
- **Repository**：会话存储门面，负责 Redis 缓存与 MySQL 回源（Service 不感知存储细节）
- **Persistence Service**：聊天数据的 MySQL 事务边界（接口 + impl）
- **Mapper**：MyBatis 数据库访问（用户、会话、消息）
- **拦截器链**：自动解析 Token → 填充 UserHolder → 校验登录态

## 聊天数据持久化

MySQL 是聊天数据的持久化主存储，Redis 只做 7 天热缓存：

```
写：MySQL（消息 + 会话元数据，事务）→ Redis（List / ZSet / Hash）
读：Redis 命中直接返回；未命中查 MySQL 并回填 Redis
```

- 表：`tb_chat_session`、`tb_chat_message`
- 消息按自增 `id` 升序，避免同一秒内顺序不稳定；`id` 同时作为分页游标
- 历史接口支持 `beforeId` + `limit` 游标分页，不传 `limit` 时保持全量返回
- 只把最近 N 条历史送给模型（默认 20，`app.chat.context-max-messages` 可调），控制 Prompt 长度
- 历史以真实 `UserMessage` / `AssistantMessage` 传入，system prompt 只保留可信人设；按条数 + 字符预算裁剪（`app.chat.context-max-messages` / `app.chat.context-max-chars`）
- 会话锁定的模型存 `tb_chat_session.model`，同一会话传不同模型直接 409
- 每条消息记录实际使用的模型（`model` 列），历史接口会原样返回；旧消息该字段为 `null`
- 缓存写失败只记录日志并删除可能不完整的缓存，读请求会回源 MySQL
- 旧版本消息缓存缺少 `id` 时自动回源 MySQL 并重建
- 清空会话时先删 MySQL，再删 Redis

详细设计见 `docs/聊天持久化设计.md`。

## 安全设计

- **密码加密**：BCrypt 自动加盐，明文不落库
- **Token 管理**：Redis Hash 存储，7 天 TTL，每次请求自动续期
- **会话隔离**：所有会话 Key 带 userId 前缀，操作前校验归属
- **参数校验**：手机号/密码正则、长度限制、手机号归属校验
- **防重放**：验证码一次性，校验后立即删除；60s 发送冷却
- **全局异常**：BusinessException + GlobalExceptionHandler，统一 JSON 响应

## Redis Key 设计

| Key | 类型 | TTL | 说明 |
|-----|------|-----|------|
| `aichat:login:code:{phone}` | String | 5 分钟 | 登录验证码 |
| `aichat:login:token:{token}` | Hash | 7 天 | 用户信息 (id/name/phone) |
| `aichat:chat:sessions:{userId}` | ZSet | 7 天 | 用户会话列表 |
| `aichat:chat:session:{userId}:{sessionId}` | List | 7 天 | 会话消息 |
| `aichat:chat:title:{userId}:{sessionId}` | Hash | 7 天 | 会话标题 |
| `aichat:user:new:{uid}` | String | 365 天 | 新用户标记 |

> 聊天相关 key 都只是缓存：缓存未命中会回源 MySQL，过期不会丢数据。

## 项目结构

```
com.aichat/
├── Config/          # 拦截器、CORS、ChatClient、Redis 配置
├── Controller/      # 用户 + 聊天接口
├── Dto/             # 接口 DTO、Redis 消息对象、统一返回
├── Entity/          # 数据库实体（ChatMessage、ChatSession）
├── Exception/       # 业务异常 + 全局异常处理器
├── Mapper/          # MyBatis：用户、会话、消息
├── Repository/      # 会话缓存门面（Redis + MySQL 回源）、用户仓储
├── Service/         # 业务逻辑 + 聊天持久化事务
└── utils/           # ThreadLocal、加密、正则、Redis 常量
```

## CI

GitHub Actions 在每次 push 时执行单元测试与打包（`.github/workflows/ci.yml`）。

`AichatApplicationTests` 是 `@SpringBootTest`，启动需要真实的 Redis / MySQL / Ollama，因此未纳入 CI；CI 只跑不依赖外部服务的单元测试。

`FullFlowIntegrationTest` / `MindButlerFlowIntegrationTest` / `MindButlerEvalRunnerTest` / `DeepSeekShortInputRegressionTest` 连接真实 MySQL(13306)/Redis(16379)，已在 CI 排除清单；本地没有启动依赖服务时，它们会通过 `DbTestSupport` 自动 skip，而不是让 `mvn test` 报连接错误。

### 一体化集成测试

`FullFlowIntegrationTest` 是不 Mock、真实连 MySQL/Redis/Ollama 的端到端验收测试（17 步全链路）。本地运行：

```bash
# 前置：虚拟机已起（MySQL 13306、Redis 16379），Ollama 已加载 qwen2.5-coder:7b
mvn test -Dtest=FullFlowIntegrationTest
```

它用 `HttpComponentsClientHttpRequestFactory` + 自定义 `ResponseErrorHandler`（不抛 4xx）来正确断言 400/401/409 的响应体，并用 `ParameterizedTypeReference` 解决泛型 `Result<T>` 反序列化。覆盖场景：登录 → 新用户引导 → 设密码 → 改昵称 → 模型列表 → 建会话发消息（Ollama）→ 切模型 409 → 查历史 → 查会话 → **历史分页**（`beforeId`+`limit`）→ **DeepSeek 路由**（OpenAI 兼容协议，配了真实 key 才跑、占位符时自动跳过）→ 清会话重建 + 重新锁模型 → **流式 SSE**（`/chat/ask-stream`）→ 登出 401。

## 配置项

| 配置 | 默认值 | 说明 |
|------|--------|------|
| `app.models.available` | `qwen2.5-coder:7b,deepseek-chat` | 模型白名单 |
| `app.models.default-model` | `qwen2.5-coder:7b` | 默认模型 |
| `app.models.ollama-models` | `qwen2.5-coder:7b` | 走 Ollama 的模型，其余走 OpenAI 兼容协议 |
| `app.chat.context-max-messages` | `20` | 送给模型的历史消息条数，`<=0` 表示不裁剪 |
| `app.mindbutler.enabled` | `true` | MindButler P1 总开关；关闭后只保留手动 CRUD，不做意图提取/注入 |
| `app.mindbutler.intent.extract-model` | `deepseek-chat` | 意图提取器模型（需在模型白名单内，启动时校验） |
| `app.mindbutler.intent.timeout-ms` | `5000` | 提取超时；超时返回固定兜底 |
| `app.mindbutler.intent.daily-limit` | `50` | 每用户每日提取次数；0=不限 |
| `app.mindbutler.intent.max-memories` | `5` | 单条消息最多记忆候选 |
| `app.mindbutler.intent.max-tasks` | `5` | 单条消息最多任务候选 |

## 文档
## 文档

**先看 [`docs/README.md`](docs/README.md)（文档索引，16 份 + 迁移脚本 + 评测集）**，按目的挑：

| 目的 | 文档 |
|---|---|
| 跑起来 | [`docs/本地启动.md`](docs/本地启动.md) |
| 读代码 | [`docs/啃代码路线.md`](docs/啃代码路线.md)（五条主线 + 30 个自测题） |
| 面试 / 简历 | [`docs/简历与演示.md`](docs/简历与演示.md) |
| 排期与取舍 | [`docs/路线图.md`](docs/路线图.md) · [`docs/后续开发计划.md`](docs/后续开发计划.md) |
| 整体设计 | [`docs/MindButler-P1设计.md`](docs/MindButler-P1设计.md) · [`docs/聊天持久化设计.md`](docs/聊天持久化设计.md) |
| 记忆 | [`docs/memory-decay.md`](docs/memory-decay.md) · [`docs/memory-change.md`](docs/memory-change.md) |
| MCP 工具 | [`docs/mcp.md`](docs/mcp.md) · [`docs/mcp-design.md`](docs/mcp-design.md) |
| 任务能力边界 | [`docs/Task-Capability.md`](docs/Task-Capability.md) |
| 验收记录 | [`docs/P1-上线验收清单.md`](docs/P1-上线验收清单.md) |
| 变更日志 / 踩坑 | [`docs/开发日志.md`](docs/开发日志.md) · [`docs/踩坑记录.md`](docs/踩坑记录.md) |
| 表结构 | [`docs/schema.sql`](docs/schema.sql) + [`docs/migration/`](docs/migration/) |
