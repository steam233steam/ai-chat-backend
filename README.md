[![Backend CI](https://github.com/steam233steam/ai-chat-backend/actions/workflows/ci.yml/badge.svg)](https://github.com/steam233steam/ai-chat-backend/actions/workflows/ci.yml)

# AI Chat 后端

基于 Spring Boot 3 + Spring AI 的智能对话后端，支持 Ollama 本地模型与 DeepSeek 云端模型切换，提供同步/流式两种响应模式。

> 前端仓库：[ai-chat-frontend](https://github.com/steam233steam/ai-chat-frontend)

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
- **工程化**：三层架构 + 全局异常处理 + 拦截器链 + 参数校验
- **一体化集成测试**：`FullFlowIntegrationTest` 13 步全链路端到端验收测试（真实连 MySQL/Redis/Ollama，不 Mock）

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
| PUT | `/user/set-password` | 设置密码 |
| PUT | `/user/profile` | 修改昵称 |
| GET | `/user/me` | 获取当前用户 |
| GET | `/user/is-new-user` | 检查是否新用户 |
| POST | `/user/logout` | 登出 |

### 聊天接口 `/chat`
| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/chat/models` | 可用模型白名单 |
| POST | `/chat/ask` | 同步对话 |
| GET | `/chat/ask-stream` | 流式对话 (SSE) |
| GET | `/chat/history` | 会话历史（可选 `beforeId` + `limit` 分页） |
| GET | `/chat/sessions` | 会话列表 |
| PUT | `/chat/title` | 更新标题 |
| DELETE | `/chat/clear-session` | 清空会话 |

完整接口文档见 `docs/API.md`。

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
- 历史以"发言者 + 模型名（我/其他模型）"标注后作为 system message 注入，并保留基础人设 prompt
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

## 文档

- `docs/API.md` — 接口文档
- `docs/schema.sql` — 数据库脚本
- `docs/聊天持久化设计.md` — 持久化、缓存一致性与面试讲点
- `docs/DEVELOPMENT_PLAN.md` — 后续开发与求职准备计划
- `docs/本地启动.md` — 本地运行指南
- `docs/学习笔记.md` — 开发笔记
- `PROJECT_STATUS.md` — 开发状态记录
