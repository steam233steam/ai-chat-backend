# AI Chat 项目开发状态

## 项目概览
- **项目名称**: aichat
- **技术栈**: Spring Boot 3.2.5 + Spring AI (1.0.0-M5) + Redis + Ollama + DeepSeek (OpenAI 兼容) + MySQL + MyBatis
- **Java版本**: 17
- **工作目录**: D:\maven-project\aichat
- **开始开发日期**: 2026-09-12
- **当前阶段**: 会话锁模型 + 带发言人标注的历史 + 一体化集成测试（13 步全链路）+ 安全加固完成 ✅

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
│   └── RefreshTokenInterceptor.java # Token 刷新拦截器
├── Controller/
│   ├── ChatController.java         # 聊天接口（/chat 前缀 + 参数校验 + 分页）
│   └── UserController.java         # 用户接口（/user 前缀 + @Valid 校验）
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
│       ├── ChatServiceImpl.java    # 含上下文裁剪 + 模型路由 + 锁模型 + 带标注历史
│       ├── UserServiceimpl.java
│       ├── ChatPersistenceServiceimpl.java
│       └── ChatTitleServiceimpl.java # 异步标题生成（@Async 独立线程池）
└── Utils/
    ├── UserHolder.java             # 用户上下文（ThreadLocal）
    ├── PasswordUtils.java          # 密码工具（BCrypt）
    ├── RegexUtils.java             # 正则工具（含密码校验）
    ├── RegexPatterns.java          # 正则表达式常量
    ├── RedisConstants.java         # Redis Key 常量（含会话锁模型 Hash）
    └── ChatHistoryFormatter.java   # 带发言人标注的历史格式化（多模型身份区分）
```

## 当前状态（2026-10-28 18:36 更新）

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
- [x] 同步对话（POST /chat/ask）+ 参数长度校验
- [x] 流式对话（GET /chat/ask-stream）+ SSE
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

#### 上下文裁剪（2026-10-25）
- [x] `ChatServiceImpl.trimHistory()` - 只把最近 N 条历史送给模型
- [x] `app.chat.context-max-messages` 配置项（默认 20，<=0 表示不裁剪）
- [x] 历史接口仍返回全量/分页数据，裁剪只影响 AI 上下文

#### 异步 AI 标题生成（2026-10-25）
- [x] `ChatTitleService` - 会话标题生成接口
- [x] `ChatTitleServiceimpl` - 使用默认模型（本地 Ollama，便宜快）异步生成标题
- [x] `AsyncConfig` - 独立线程池（core=2, max=4, queue=100, CallerRunsPolicy）
- [x] 首次对话时先写兜底标题落库，再提交异步 AI 标题任务
- [x] 如果用户已手动修改标题（标题不再等于兜底值），跳过 AI 标题覆盖
- [x] 标题生成失败时静默保留兜底标题，绝不影响主流程

#### 会话锁模型（2026-10-28）⭐
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

#### 带发言人标注的历史（2026-10-28）⭐
- [x] `ChatHistoryFormatter` - 把带 model 标签的会话历史格式化成喂给大模型的文本块
- [x] 每条历史标注"发言人: 模型名（我）/（其他模型）"
- [x] 让当前模型能明确区分"哪些是我说的、哪些是别的模型说的"
- [x] 共享上下文（跨模型不断裂）+ 不认领错误（不把别的模型的话当成自己的）
- [x] `ChatServiceimpl.buildLabeledHistory` - 渲染带标注的历史文本块
- [x] `ChatServiceimpl.withHistory` - 把带标注的历史挂到 prompt，保留基础人设
- [x] 旧版本消息缺少 model 时标记为"未知模型"

#### 数据持久化（2026-10-22）
- [x] `tb_chat_session` 表（会话元数据）
- [x] `tb_chat_message` 表（聊天消息）
- [x] `ChatPersistenceService` - MySQL 事务写入
- [x] `ChatSessionRepositoryimpl` - 先写 MySQL，再写 Redis；读未命中回源
- [x] 旧库 password NOT NULL 迁移脚本

#### 安全与工具
- [x] Token 拦截器（RefreshTokenInterceptor）
- [x] 登录拦截器（LoginInterceptor）+ CORS 预检放行
- [x] BCrypt 密码加密
- [x] 手机号/验证码/密码正则校验
- [x] ThreadLocal 用户上下文
- [x] 新用户标记功能
- [x] 全局异常处理器（含 @Valid/404/409/413）
- [x] 验证码发送防刷冷却（60 秒）
- [x] 会话数据用户隔离
- [x] CORS 配置（本地开发端口）
- [x] 验证码日志脱敏（2026-10-27，只打前 2 位，避免手机号 + 验证码成对泄露）
- [x] `app.sms.dev-mode` 默认改为 false（生产安全）

#### 一体化集成测试（2026-10-30）⭐
- [x] `FullFlowIntegrationTest` - 17 步全链路端到端验收测试（`src/test/java/com/aichat/FullFlowIntegrationTest.java`）
- [x] `@SpringBootTest(RANDOM_PORT)` 不 Mock，连真实 MySQL/Redis/Ollama，跑完整用户旅程
- [x] 覆盖：发验证码(冷却)→验证码登录→me(isNewUser=true)→is-new-user→设密码→改昵称(超长400)→模型列表→新建会话发消息(本地Ollama)→切模型触发409→查历史(带model)→查会话列表(带锁模型)→历史分页(beforeId+limit)→DeepSeek路由(OpenAI兼容协议,占位符key自动跳过)→清会话+重建重锁→流式SSE(/chat/ask-stream)→登出(token失效401)
- [x] 流式场景：RestTemplate 读回 `text/event-stream` 全量 body，断言含 `data:`；验证 `doFinally` 断开也保存 + 会话锁模型
- [x] DeepSeek 场景：读 `environment.getProperty("spring.ai.openai.api-key")`，是占位符则 `Assumptions.assumeTrue` 跳过，配了真 key 才真跑 OpenAI 协议那条路由
- [x] `TestRestTemplate` 用 `HttpComponentsClientHttpRequestFactory` + 自定义 `ResponseErrorHandler`（不抛 4xx），正确读到 400/401/409 的 body
- [x] `ParameterizedTypeReference` 解决泛型 `Result<T>` 反序列化
- [x] 数据隔离：静态手机号 + `@BeforeEach` 用 `dataCleaned` 标志只清一次；`UserRepository.saveCode` 注入已知验证码绕过 60s 冷却
- [x] `pom.xml` 新增 `httpclient5`（test scope）依赖
- [x] 全部 13 个测试通过（`mvn test -Dtest=FullFlowIntegrationTest`）

#### 工程化
- [x] `README.md` - 项目说明 + API 总览 + 配置项
- [x] `.github/workflows/ci.yml` - CI（单元测试 + 打包）
- [x] `docs/踩坑记录.md` - 开发踩坑记录
- [x] `docs/INTERVIEW_ROADMAP.md` - 面试路线图
- [x] `docs/聊天记录持久化设计.md` - 持久化设计
- [x] `docs/本地启动.md` - 本地启动指南
- [x] `docs/schema.sql` - 数据库初始化脚本
- [x] `docs/DEVELOPMENT_PLAN.md` - 后续开发计划

### 📝 核心改进（2026-10-28 更新）

**1. 会话锁模型**
- 会话首条消息时锁定模型（`enforceModelLock`）
- 已有会话若传入模型与锁定模型不一致 → 409，提示"要换模型请新建会话"
- 避免同一会话反复切模型导致"分布外上下文"污染、弱模型身份漂移
- 锁模型用 `COALESCE(model, #{model})` 实现：只在首条消息（model 非空且原值为空）时写入，后续不覆盖
- `lockModel` 时以 MySQL 为准回填缓存（并发首条消息时 upsert 的 COALESCE 只保留先到的模型，直接写请求模型会让 Redis 与库不一致）

**2. 带发言人标注的历史**
- `ChatHistoryFormatter.format()` 渲染每条历史：`发言人: 模型名（我/其他模型）` + `用户/助手: 内容`
- 当前模型自己的历史打"（我）"标记，其他模型打"（其他模型）"
- 防呆设计：弱模型（如本地 7B）读不懂裸标签，容易把别人的话当成自己的
- 旧消息缺少 model 时标记为"未知模型"（不视为缓存失效条件，避免每次读都回源）
- `withHistory` 把基础人设 `ModelRouter.SYSTEM_PROMPT` 拼回去，避免 Spring AI `.system()` 覆盖 `defaultSystem`

**3. 缓存结构统一**
- `SESSION_TITLE_KEY_PREFIX` 和 `SESSION_MODEL_KEY_PREFIX` 都改为 Hash 结构：`{userId} → {sessionId → value}`
- `getModels` 批量取模型：一次 HGETALL 读缓存，未命中用一次 MySQL 查询补齐并回填
- 避免按会话逐个查库（标题有缓存捣薄，model 保持原逻辑）

**4. 安全加固（2026-10-27）**
- `UserServiceimpl.sendCode` 日志脱敏：只打验证码前 2 位 + 手机号，避免成对泄露
- `app.sms.dev-mode` 默认值从 `true` 改为 `false`（生产安全），本地在 `application-local.properties` 显式设 `true`

**5. 接口行为变化（前端需注意）**
- `POST /chat/ask` / `GET /chat/ask-stream`：已有会话切换模型时报 409
- `GET /chat/history` 响应新增 `model` 字段
- `GET /chat/sessions` 响应新增 `model` 字段
- `GET /chat/models` 保持公开接口（`/chat/models` 加入 `WebMvcConfig.PUBLIC_PATHS`）

**6. 一体化集成测试（2026-10-30）**
- 区别于 36 个"Mock 掉外部服务"的单元测试：`FullFlowIntegrationTest` 不 Mock，真实连 MySQL/Redis/Ollama，跑完整用户旅程
- 13 个 `@Order` 有序方法组成一条链：登录→新用户引导→设密码→改昵称→模型列表→建会话发消息→切模型 409→查历史→查会话→清会话→登出 401
- 关键技巧：`HttpComponentsClientHttpRequestFactory` + `ResponseErrorHandler`（不抛 4xx）测错误响应；`ParameterizedTypeReference` 解泛型 `Result<T>`；`saveCode` 注入已知验证码绕冷却
- 数据隔离：静态手机号 + `@BeforeEach` 用 `dataCleaned` 标志只清一次（token 是跨方法共享 state，不能每个方法前都清）

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
| POST | /chat/ask | sessionid, question, modelName? | 发送聊天消息（同步，会话锁模型） |
| GET | /chat/ask-stream | sessionid, question, modelName? | 流式发送聊天消息（SSE，会话锁模型） |
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
9. 双拦截器：Token 刷新 + 登录校验，CORS 预检放行
10. 参数校验：长度限制 + 格式校验 + 归属校验 + @Valid
11. 多模型路由：`ModelRouter` 按白名单分发 Ollama / DeepSeek
12. 上下文裁剪：默认只送最近 20 条历史给模型
13. 异步标题：独立线程池，不阻塞主流程
14. 会话锁模型：首条消息锁定，切换报 409
15. 带发言人标注的历史：跨模型共享上下文 + 身份区分
16. 验证码日志脱敏 + dev-mode 默认关闭

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
- httpclient5 (test scope，集成测试 RestTemplate 读 4xx 用)
```

## 配置项说明

### application.properties
```properties
# 多模型路由
app.models.available=qwen2.5-coder:7b,deepseek-chat
app.models.default-model=qwen2.5-coder:7b
app.models.ollama-models=qwen2.5-coder:7b

# 上下文裁剪
app.chat.context-max-messages=20

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
- [x] 一体化集成测试 `FullFlowIntegrationTest`（13 步全链路）✅ 2026-10-30
- [ ] 聊天/登录接口限流 + 安全加固
- [ ] Docker Compose 部署
- [ ] RAG 知识库（可选加分项）
- [ ] Micrometer 可观测性 + 压测
- [ ] springdoc-openapi 接口文档

---
*最后更新: 2026-10-30 18:29*
