# AI Chat 项目开发状态

## 项目概览
- **项目名称**: aichat
- **技术栈**: Spring Boot 3.2.5 + Spring AI (1.0.0-M5) + Redis + Ollama + DeepSeek (OpenAI 兼容) + MySQL + MyBatis
- **Java版本**: 17
- **工作目录**: D:\maven-project\aichat
- **开始开发日期**: 2026-09-12
- **当前阶段**: 模型输出净化 + 历史真实角色 + 字符预算裁剪 + 线程安全加固 + Springdoc/Swagger 在线接口文档完成 ✅

## 架构分层

```
com.aichat/
├── AichatApplication.java          # 启动类
├── Config/
│   ├── RedisConfig.java            # Redis 配置
│   ├── ChatClientConfig.java       # 多模型说明（ChatClient 由 ModelRouter 构建）
│   ├── ModelRouter.java            # 多模型路由器（Ollama / OpenAI-DeepSeek 按名分发）
│   ├── ModelProperties.java        # 模型白名单 + 默认模型配置（app.models.*）
│   ├── AsyncConfig.java            # 异步线程池配置（标题生成专用）
│   ├── WebMvcConfig.java           # MVC 配置（拦截器 + CORS）
│   ├── LoginInterceptor.java       # 登录拦截器（CORS 预检放行）
│   └── RefreshTokenInterceptor.java # Token 刷新拦截器（入口清理 ThreadLocal）
├── Controller/
│   ├── ChatController.java         # 聊天接口（/chat 前缀 + 参数校验 + 分页 + @Operation）
│   └── UserController.java         # 用户接口（/user 前缀 + @Valid 校验 + @Operation）
├── Dto/
│   ├── Result.java                 # 统一返回结果（支持自定义 code）
│   ├── SMessage.java              # 消息实体（含 id 游标 + model + 时间戳，去 Lombok）
│   ├── User.java                   # 用户实体
│   ├── UserDto.java               # 用户 DTO（含 isNewUser 字段）
│   └── LoginFormDTO.java          # 登录表单 DTO（@NotBlank + @Pattern 校验）
├── Entity/
│   ├── ChatSession.java            # 会话实体（tb_chat_session，含 model 列）
│   └── ChatMessage.java            # 消息实体（tb_chat_message，含 model 列）
├── Exception/
│   ├── BusinessException.java      # 业务异常（含错误码）
│   └── GlobalExceptionHandler.java # 全局异常处理器（含 @Valid/404/409/413）
├── Mapper/
│   ├── UserMapper.java             # 用户 Mapper
│   ├── ChatSessionMapper.java      # 会话 Mapper（upsert + 锁模型 + 按用户查询）
│   └── ChatMessageMapper.java      # 消息 Mapper（游标分页查询 + model 列）
├── Repository/
│   ├── ChatSessionRepository.java  # 会话仓储接口（含分页 + 锁模型 + 批量取模型）
│   ├── UserRepository.java         # 用户仓储接口
│   └── impl/
│       ├── ChatSessionRepositoryimpl.java  # MySQL 主存储 + Redis 缓存（id 游标 + 锁模型）
│       └── UserRepositoryimpl.java
├── Service/
│   ├── ChatService.java            # 聊天服务接口（含模型名 + 分页）
│   ├── UserService.java            # 用户服务接口
│   ├── ChatPersistenceService.java # 聊天持久化服务接口（MySQL 事务 + 锁模型）
│   ├── ChatTitleService.java       # AI 标题生成服务接口
│   └── impl/
│       ├── ChatServiceImpl.java    # 含历史角色 + 字符预算 + 输出净化 + 流式过滤
│       ├── UserServiceimpl.java
│       ├── ChatPersistenceServiceimpl.java
│       └── ChatTitleServiceimpl.java # 异步标题生成（@Async 独立线程池 + 标题净化）
└── Utils/
    ├── UserHolder.java             # 用户上下文（ThreadLocal）
    ├── PasswordUtils.java          # 密码工具（BCrypt）
    ├── RegexUtils.java             # 正则工具（含密码校验）
    ├── RegexPatterns.java          # 正则表达式常量
    ├── RedisConstants.java         # Redis Key 常量（含会话锁模型 Hash）
    ├── ChatHistoryBuilder.java      # ⭐ 把持久化历史转换成真实 Spring AI 消息角色（条数 + 字符预算裁剪）
    ├── ModelOutputSanitizer.java   # ⭐ 模型输出净化（剥 <ds_safety> 标签 + 标题净化）
    └── StreamingSafetyFilter.java  # ⭐ 有状态流式过滤器（跨 chunk 剥安全标签）
```

## 当前状态（2026-11-01 13:08 更新）

### ✅ 已完成功能
#### 用户认证系统
- [x] 发送验证码（POST /user/code）+ 60秒防刷冷却 + 日志脱敏
- [x] 验证码登录（POST /user/login）+ 验证码一次性
- [x] 密码登录（POST /user/login/password）
- [x] 设置密码（PUT /user/set-password）+ 手机号归属校验
- [x] 修改资料（PUT /user/profile）+ 昵称长度限制
- [x] 获取当前用户（GET /user/me）+ isNewUser 标记
- [x] 检查是否新用户（GET /user/is-new-user）
- [x] 登出（POST /user/logout）
- [x] @Valid 参数校验（NotBlank + Pattern）

#### 聊天功能
- [x] 同步对话（POST /chat/ask）+ 参数长度校验 + 输出净化
- [x] 流式对话（GET /chat/ask-stream）+ SSE + 流式安全过滤
- [x] 获取会话历史（GET /chat/history）+ 时间戳 + 游标分页 + model 字段
- [x] 获取会话列表（GET /chat/sessions）+ model 字段
- [x] 更新会话标题（PUT /chat/title）+ 长度限制
- [x] 清空会话（DELETE /chat/clear-session）

#### 多模型支持（2026-10-25）
- [x] `ModelRouter` - 按模型名路由 Ollama（本地）/ OpenAI 兼容协议（DeepSeek）
- [x] `ModelProperties` - 模型白名单 + 默认模型 + Ollama 模型列表（配置化）
- [x] `GET /chat/models` - 查询可用模型白名单（公开接口）
- [x] `POST /chat/ask` 和 `GET /chat/ask-stream` 支持 `modelName` 参数
- [x] 模型名不在白名单时报 400（不静默兜底，避免用户以为切换成功）
- [x] 系统提示词集中在 `ModelRouter` 构建 ChatClient 时注入

#### 历史消息分页（2026-10-25）
- [x] `SMessage` 新增 `id` 字段（MySQL 自增 id 作为分页游标）
- [x] `GET /chat/history` 支持 `beforeId` + `limit` 游标分页
- [x] `limit` 不传时保持旧行为（返回全量历史），现有前端无需改动
- [x] `beforeId` 必须配 `limit` 使用，`limit` 范围 1-100
- [x] `ChatMessageMapper` 新增 `selectLatestBySession` 和 `selectBySessionBeforeId`
- [x] 分页查询时不回写缓存（避免把"一页"当成全量历史）
- [x] 旧版消息缓存缺少 `id` 时自动回源 MySQL 重建

#### 上下文裁剪（2026-10-25 → 2026-11-01）
- [x] 按条数裁剪：`app.chat.context-max-messages`（默认 20，<=0 不裁剪）
- [x] 按字符预算裁剪：`app.chat.context-max-chars`（默认 24000，<=0 不限制）⭐ 2026-11-01
- [x] 历史接口仍返回全量/分页数据，裁剪只影响 AI 上下文

#### 异步 AI 标题生成（2026-10-25 → 2026-11-01）
- [x] `ChatTitleService` - 会话标题生成接口
- [x] `ChatTitleServiceimpl` - 使用默认模型（本地 Ollama，便宜快）异步生成标题
- [x] `AsyncConfig` - 独立线程池（core=2, max=4, queue=100, CallerRunsPolicy）
- [x] 首次对话时先写兜底标题落库，再提交异步 AI 标题任务
- [x] 如果用户已手动修改标题（标题不再等于兜底值），跳过 AI 标题覆盖
- [x] 标题生成失败时静默保留兜底标题，绝不影响主流程
- [x] 标题用 `ModelOutputSanitizer.sanitizeTitle` 净化（去安全标签/HTML/引号/换行）⭐ 2026-11-01

#### 会话锁模型（2026-10-28）
- [x] `ChatMessage` / `ChatSession` 实体新增 `model` 字段
- [x] `tb_chat_message.model` - 记录每条消息实际使用的模型
- [x] `tb_chat_session.model` - 会话锁定的模型（首条消息写入后固定）
- [x] `ChatSessionMapper.upsert` 使用 `COALESCE(model, #{model})` 实现锁模型
- [x] `ChatServiceimpl.enforceModelLock` - 新会话锁模型，已有会话切换模型报 409
- [x] `ChatSessionRepository.lockModel` / `getModel` / `getModels`
- [x] `RedisConstants.SESSION_MODEL_KEY_PREFIX` - 会话锁模型 Hash 缓存
- [x] `GET /chat/sessions` 返回 `model` 字段（批量取模型，避免逐会话查库）
- [x] `GET /chat/history` 返回 `model` 字段
- [x] 迁移脚本 `docs/migration/2026-10-28-add-chat-message-model.sql`
- [x] 迁移脚本 `docs/migration/2026-10-28-add-chat-session-model.sql`

#### 带发言人标注的历史（2026-10-28）
- [x] `ChatHistoryFormatter` - 把带 model 标签的会话历史格式化成喂给大模型的文本块
- [x] 每条历史标注"发言人: 模型名（我/其他模型）"
- [x] 让当前模型能明确区分"哪些是我说的、哪些是别的模型说的"
- [x] 共享上下文（跨模型不断裂）+ 不认领错误（不把别的模型的话当成自己的）
- [x] `ChatServiceimpl.buildLabeledHistory` - 渲染带标注的历史文本块
- [x] `ChatServiceimpl.withHistory` - 把带标注的历史挂到 prompt，保留基础人设
- [x] 旧版本消息缺少 model 时标记为"未知模型"

#### 历史角色真实化（2026-11-01）⭐
- [x] 历史从"带标注文本块拼进 system"改为"真实 UserMessage / AssistantMessage 角色"
- [x] 安全边界：历史只作为 User/Assistant 传入，绝不拼进 system prompt，避免用户历史被提升为系统指令
- [x] 当前问题作为最后一条 UserMessage 追加
- [x] `withHistory` 用 `chatClient.prompt().system(ModelRouter.SYSTEM_PROMPT).messages(historyMessages + [question])`
- [x] 兜底文案不再污染下一轮上下文（失败时不写入历史）

#### 模型输出净化（2026-11-01）⭐
- [x] `ModelOutputSanitizer.sanitize` - 剥掉模型偶尔泄漏的内部 `<ds_safety>` 安全审核标签
- [x] 同步回复落库前净化，避免脏数据进库/进前端
- [x] 流式回复用 `StreamingSafetyFilter` 跨 chunk 剥标签（标签可能被拆成 "<ds_sa" + "fety>"）
- [x] 清理后为空时用固定兜底文案（"（模型回复被过滤，请稍后重试或换个问法）"），不存空消息
- [x] 标题专用净化 `sanitizeTitle`（去安全标签/HTML/引号/换行/控制字符）

#### 线程安全加固（2026-11-01）⭐
- [x] `RefreshTokenInterceptor.preHandle` 入口先 `UserHolder.removeUser()`
- [x] SSE/异步请求的 `afterCompletion` 可能不在原 Tomcat 线程执行，若不在入口清理，后续无 Token 请求可能复用到上一个请求的用户（串用户）
- [x] 配合 `afterCompletion` 清理，双重保障防止 ThreadLocal 内存泄漏 + 用户串号

#### 数据持久化（2026-10-22）
- [x] `tb_chat_session` 表（会话元数据）
- [x] `tb_chat_message` 表（聊天消息）
- [x] `ChatPersistenceService` - MySQL 事务写入
- [x] `ChatSessionRepositoryimpl` - 先写 MySQL，再写 Redis；读未命中回源
- [x] 旧库 password NOT NULL 迁移脚本

#### 安全与工具
- [x] Token 拦截器（RefreshTokenInterceptor，入口清理 ThreadLocal）
- [x] 登录拦截器（LoginInterceptor）+ CORS 预检放行
- [x] BCrypt 密码加密
- [x] 手机号/验证码/密码正则校验
- [x] ThreadLocal 用户上下文（入口 + 出口双重清理）
- [x] 新用户标记功能
- [x] 全局异常处理器（含 @Valid/404/409/413）
- [x] 验证码发送防刷冷却（60 秒）
- [x] 会话数据用户隔离
- [x] CORS 配置（本地开发端口）
- [x] 验证码日志脱敏（只打前 2 位，避免手机号 + 验证码成对泄露）
- [x] `app.sms.dev-mode` 默认 false（生产安全）
- [x] 模型输出净化（剥安全标签 + 流式过滤 + 标题净化）

#### 在线接口文档（2026-11-01）⭐
- [x] `pom.xml` 新增 `springdoc-openapi-starter-webmvc-ui`（2.5.0）
- [x] `ChatController` / `UserController` 全部接口加 `@Tag` + `@Operation` 注解
- [x] `application.properties` 新增 springdoc 开关（`springdoc.enabled` 默认 true，可用 `SPRINGDOC_ENABLED=false` 关掉）
- [x] Swagger UI：`http://localhost:8080/swagger-ui.html`
- [x] OpenAPI JSON：`http://localhost:8080/v3/api-docs`
- [x] 生产环境可关闭，不暴露文档

#### 测试基础设施（2026-11-01）⭐
- [x] `pom.xml` 新增 `httpclient5`（test scope）
- [x] 集成测试的 RestTemplate 正确处理 4xx 响应（JDK 默认工厂对 4xx 会抛 HttpRetryException）

#### 工程化
- [x] `README.md` - 项目说明 + API 总览 + 配置项
- [x] `.github/workflows/ci.yml` - CI（单元测试 + 打包）
- [x] `docs/踩坑记录.md` - 开发踩坑记录
- [x] `docs/INTERVIEW_ROADMAP.md` - 面试路线图
- [x] `docs/聊天记录持久化设计.md` - 持久化设计
- [x] `docs/本地启动.md` - 本地启动指南
- [x] `docs/schema.sql` - 数据库初始化脚本
- [x] `docs/DEVELOPMENT_PLAN.md` - 后续开发计划

### 📝 核心改进（2026-11-01 更新）

**1. 模型输出净化（输出侧防御层）**
- 部分模型（如 DeepSeek 的推理侧安全层）在特定输入下会把内部审核标签（形如 `<ds_safety>...</ds_safety>`）夹带进 content
- 正常 API 调用中这类标签不应出现在 `message.content` 里
- `ModelOutputSanitizer.sanitize`：完整标签对（忽略大小写、支持跨行和标签内空白）剥离；残缺标签开头（只吐了开标签没吐闭合）从该处到末尾全丢；清理后为空用固定兜底文案
- `StreamingSafetyFilter`：有状态流式过滤器，保留少量尾部缓冲，等标签完整或流结束后再决定，避免对每个 chunk 单独调用导致被拆开的标签漏过
- 同步/流式/标题三条路径都走净化，不存脏数据

**2. 历史角色真实化（安全边界）**
- 历史从"带标注文本块拼进 system"改为"真实 UserMessage / AssistantMessage 角色"
- 安全边界：历史只作为 User/Assistant 传入模型，绝不拼进 system prompt，否则用户历史会被提升为系统指令（提示注入风险）
- `ChatHistoryBuilder.build(history, maxMessages, maxChars)` 负责按条数 + 字符预算裁剪
- 当前问题作为最后一条 UserMessage，保持"当前问题只通过最后一条进入上下文"避免重复发送

**3. 字符预算裁剪**
- 新增 `app.chat.context-max-chars`（默认 24000），历史总字符预算
- `ChatHistoryBuilder` 从最新往旧取，保证超预算时优先保留最近上下文
- 单条消息自身超预算时保留其尾部（至少让模型看到最近上下文）
- 每条消息有固定开销 `MESSAGE_OVERHEAD=16` 用于字符预

**4. 线程安全加固**
- `RefreshTokenInterceptor` 入口先 `UserHolder.removeUser()`
- SSE/异步请求的 `afterCompletion` 可能不在原 Tomcat 线程执行，若不在入口清理，后续无 Token 请求可能复用到上一个请求的用户（串用户）
- 配合 `afterCompletion` 清理，双重防止 ThreadLocal 内存泄漏 + 用户串号

**5. 失败兜底文案不污染上下文**
- 流式调用失败时（`failed` 标志），兜底文案不写入历史，避免污染下一轮上下文
- 只有正常结束或客户端主动断开时才保存已生成部分

### API 接口总览（对外行为）

#### 用户接口（/user 前缀）
| 方法 | 路径 | 参数 | 说明 |
|------|------|------|------|
| POST | /user/code | phone | 发送验证码（60秒冷却） |
| POST | /user/login | phone, code (Body) | 验证码登录 |
| POST | /user/login/password | phone, password (Body) | 密码登录 |
| PUT | /user/set-password | phone, code, password (Body) | 设置密码 |
| PUT | /user/profile | name (Body), token (Header) | 修改资料 |
| GET | /user/me | - | 获取当前用户（含 isNewUser） |
| GET | /user/is-new-user | - | 检查是否新用户 |
| POST | /user/logout | token (Header) | 登出 |

#### 聊天接口（/chat 前缀）
| 方法 | 路径 | 参数 | 说明 |
|------|------|------|------|
| GET | /chat/models | - | 可用模型白名单（公开接口） |
| POST | /chat/ask | sessionid, question, modelName? | 发送聊天消息（同步，会话锁模型，输出净化） |
| GET | /chat/ask-stream | sessionid, question, modelName? | 流式发送聊天消息（SSE，会话锁模型，流式安全过滤） |
| GET | /chat/history | sessionid, beforeId?, limit? | 获取会话历史（含时间戳 + model + 分页） |
| GET | /chat/sessions | - | 获取会话列表（含 model） |
| PUT | /chat/title | sessionid, title | 更新会话标题 |
| DELETE | /chat/clear-session | sessionid | 清空会话 |

### 技术要点
1. **MySQL 是聊天数据持久化主存储**，Redis 只做 7 天热缓存
2. 密码使用 BCrypt 自动加盐加密
3. Token 有效期 7 天，每次请求自动续期
4. 验证码有效期 5 分钟，同一手机号 60 秒冷却，日志脱敏
5. 新用户标记有效期 365 天，仅设置密码后自动取消
6. 会话数据按用户隔离，key 格式：`aichat:chat:sessions:{userId}`
7. 消息按自增 `id` 稳定排序，`id` 作为分页游标
8. 全局异常处理：BusinessException 携带错误码，统一 JSON 响应
9. 双拦截器：Token 刷新（入口清理 ThreadLocal）+ 登录校验，CORS 预检放行
10. 参数校验：长度限制 + 格式校验 + 归属校验 + @Valid
11. 多模型路由：`ModelRouter` 按白名单分发 Ollama / DeepSeek
12. 上下文裁剪：按条数（默认 20）+ 字符预算（默认 24000）双重控制
13. 异步标题：独立线程池，不阻塞主流程，标题走净化
14. 会话锁模型：首条消息锁定，切换报 409
15. 历史角色真实化：User/Assistant 消息，不拼 system（防提示注入）
16. 模型输出净化：剥 `<ds_safety>` 标签，同步/流式/标题三路
17. 线程安全：拦截器入口 + 出口双重清理 ThreadLocal

## 数据库表结构

### tb_user 表
```sql
CREATE TABLE tb_user (
  uid VARCHAR(32) PRIMARY KEY COMMENT '用户唯一标识',
  name VARCHAR(64) COMMENT '用户昵称',
  phone VARCHAR(20) UNIQUE COMMENT '手机号',
  password VARCHAR(255) NULL COMMENT '密码（BCrypt 加密，首次为 NULL）',
  create_time DATETIME COMMENT '创建时间',
  update_time DATETIME COMMENT '更新时间'
);
```

### tb_chat_session 表
```sql
CREATE TABLE tb_chat_session (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
  user_id     VARCHAR(32) NOT NULL COMMENT '用户 uid',
  session_id  VARCHAR(64) NOT NULL COMMENT '会话标识',
  title       VARCHAR(100) DEFAULT NULL COMMENT '会话标题',
  model       VARCHAR(64) DEFAULT NULL COMMENT '会话锁定的模型（首条消息写入后固定，切换需新建会话）',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最近更新时间',
  UNIQUE KEY uk_user_session (user_id, session_id),
  KEY idx_user_update (user_id, update_time)
);
```

### tb_chat_message 表
```sql
CREATE TABLE tb_chat_message (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键（分页游标）',
  user_id     VARCHAR(32) NOT NULL COMMENT '用户 uid',
  session_id  VARCHAR(64) NOT NULL COMMENT '所属会话',
  role        VARCHAR(16) NOT NULL COMMENT 'user/assistant/system',
  content     TEXT NOT NULL COMMENT '消息内容',
  model       VARCHAR(64) COMMENT '本轮使用的模型名（多模型切换后每条消息记录实际走的是哪个模型）',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '消息时间',
  KEY idx_user_session (user_id, session_id, id)
);
```

**完整建表脚本**：`docs/schema.sql`  
**迁移脚本**：
- `docs/migration/2026-10-22-fix-tb-user-password-null.sql`
- `docs/migration/2026-10-28-add-chat-message-model.sql`
- `docs/migration/2026-10-28-add-chat-session-model.sql`

## 依赖项
```xml
- spring-boot-starter-web
- spring-boot-starter-validation
- spring-boot-starter-data-redis
- spring-ai-ollama-spring-boot-starter
- spring-ai-openai-spring-boot-starter  (DeepSeek)
- mysql-connector-j (8.0.33)
- mybatis-spring-boot-starter (3.0.3)
- hutool-core (5.8.25)
- hutool-crypto (5.8.25)
- spring-boot-starter-test
```

## 配置项说明

### application.properties
```properties
# 多模型路由
app.models.available=qwen2.5-coder:7b,deepseek-chat
app.models.default-model=qwen2.5-coder:7b
app.models.ollama-models=qwen2.5-coder:7b

# 上下文裁剪（条数 + 字符预算）
app.chat.context-max-messages=20
app.chat.context-max-chars=24000

# 验证码开发模式（默认关闭，本地在 application-local.properties 显式设 true）
app.sms.dev-mode=${APP_SMS_DEV_MODE:false}

# DeepSeek（OpenAI 兼容协议）
spring.ai.openai.base-url=${DEEPSEEK_BASE_URL:https://api.deepseek.com}
spring.ai.openai.api-key=${DEEPSEEK_API_KEY:sk-deepseek-placeholder}
spring.ai.openai.chat.options.model=deepseek-chat

# Ollama（本地模型）
spring.ai.ollama.base-url=${OLLAMA_BASE_URL:http://localhost:11434}
spring.ai.ollama.chat.options.model=${OLLAMA_MODEL:qwen2.5-coder:7b}
```

### 环境变量
| 变量 | 默认值 | 说明 |
|------|--------|------|
| `OLLAMA_BASE_URL` | `http://localhost:11434` | Ollama 服务地址 |
| `OLLAMA_MODEL` | `qwen2.5-coder:7b` | 默认 Ollama 模型 |
| `DEEPSEEK_BASE_URL` | `https://api.deepseek.com` | DeepSeek API 地址 |
| `DEEPSEEK_API_KEY` | - | DeepSeek API Key（不提交 Git） |
| `REDIS_HOST` | `localhost` | Redis 地址 |
| `REDIS_PORT` | `16379` | Redis 端口 |
| `REDIS_PASSWORD` | - | Redis 密码 |
| `MYSQL_URL` | `jdbc:mysql://localhost:13306/aichat` | MySQL 连接串 |
| `MYSQL_USERNAME` | `root` | MySQL 用户名 |
| `MYSQL_PASSWORD` | - | MySQL 密码 |
| `APP_CHAT_CONTEXT_MAX_MESSAGES` | `20` | 送给模型的历史消息条数，<=0 不裁剪 |
| `APP_CHAT_CONTEXT_MAX_CHARS` | `24000` | 送给模型的历史总字符预算，<=0 不限制 |

## 文档
- `README.md` - 项目说明 + API 总览 + 配置项
- `docs/API.md` - 后端接口文档（供前端使用）
- `docs/DEVELOPMENT_PLAN.md` - 后续开发计划
- `docs/INTERVIEW_ROADMAP.md` - 面试路线图
- `docs/聊天记录持久化设计.md` - 持久化设计
- `docs/踩坑记录.md` - 开发踩坑记录
- `docs/schema.sql` - 数据库初始化脚本
- `docs/本地启动.md` - 本地启动指南
- `docs/migration/` - 数据库迁移脚本
- `docs/学习笔记.md` - 项目学习笔记
- `docs/备注.md` - 开发备注
- `PROJECT_STATUS.md` - 项目状态记录

## 开发环境说明（本地启动.md 摘要）
- 后端：`http://localhost:8080`
- 前端：Vite `http://localhost:5173`（/api 代理到后端）
- Ollama：`127.0.0.1:11434`（qwen2.5-coder:7b）
- MySQL：`127.0.0.1:13306`（VirtualBox 虚拟机 studybox 的 NAT 转发）
- Redis：`127.0.0.1:16379`（同上）
- 敏感配置放 `application-local.properties`（.gitignore 中不提交）

## 下一步计划（见 DEVELOPMENT_PLAN.md）
- [x] 聊天数据持久化（MySQL 主存储 + Redis 缓存）✅ 2026-10-22
- [x] 多模型路由（Ollama + DeepSeek）✅ 2026-10-25
- [x] 历史消息分页 ✅ 2026-10-25
- [x] 上下文裁剪 ✅ 2026-10-25
- [x] 异步 AI 标题生成 ✅ 2026-10-25
- [x] 会话锁模型 + 带发言人标注的历史 ✅ 2026-10-28
- [x] 验证码日志脱敏 + dev-mode 默认关闭 ✅ 2026-10-27
- [x] 模型输出净化 + 历史角色真实化 + 字符预算 + 线程安全 ✅ 2026-11-01
- [x] Springdoc/Swagger 在线接口文档 + httpclient5 测试依赖 ✅ 2026-11-01
- [x] **MindButler P1**：长期记忆（4 维度画像）+ 口语化任务 + pending 确认状态机 + 意图拦截 ✅ 2026-11-02（M1）→ 2026-11-03（M2/M3/M4，P1 主链路完成；收尾项见下）
- [x] MindButler P1 单测/集成 202 绿（含真 MySQL 链路 + 40 条口语评测桩链路）✅ 2026-11-03（2026-11-03 验收补丁后）
- [x] MindButler P1 收尾：意图提取超时/日限流（`intent.timeout-ms` / `intent.daily-limit`）接线、启动校验、固定兜底文案 ✅ 2026-11-03
- [ ] MindButler P1.5：自然语言更新/删除、monthly、真实 LLM 准确率评测
- [ ] 聊天/登录接口限流 + 安全加固
- [ ] Docker Compose 部署
- [ ] RAG 知识库（可选加分项；P1 刻意不引向量库，留 P1.5 数据量验证）
- [ ] Micrometer 可观测性 + 压测
- [ ] MindButler P2：MCP 只读公开数据源（如米游社资源/回复量）+ 风控感知（参考 Mika）
- [ ] MindButler P3：微信/QQ AI 客服提醒（最小权限：只读提醒、不能加计划）
- [x] springdoc-openapi 接口文档 ✅ 2026-11-01

---
*最后更新: 2026-11-03 16:10*
