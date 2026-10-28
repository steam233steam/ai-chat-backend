# AI Chat 项目开发状态

## 项目概览
- **项目名称**: aichat
- **技术栈**: Spring Boot 3.2.5 + Spring AI (1.0.0-M5) + Redis + Ollama + DeepSeek (OpenAI 兼容) + MySQL + MyBatis
- **Java版本**: 17
- **工作目录**: D:\maven-project\aichat
- **开始开发日期**: 2026-09-12
- **当前阶段**: 多模型路由 + 历史分页 + 上下文裁剪 + 异步 AI 标题生成 + 消息级模型追踪完成 ✅

## 架构分层

```
com.aichat/
├── AichatApplication.java          # 启动类
├── Config/
│   ├── RedisConfig.java            # Redis 配置
│   ├── ChatClientConfig.java       # 多模型说明（ChatClient 由 ModelRouter 构建）
│   ├── ModelRouter.java            # ⭐ 多模型路由器（Ollama / OpenAI-DeepSeek 按名分发）
│   ├── ModelProperties.java        # ⭐ 模型白名单 + 默认模型配置（app.models.*）
│   ├── AsyncConfig.java            # ⭐ 异步线程池配置（标题生成专用）
│   ├── WebMvcConfig.java           # MVC 配置（拦截器 + CORS）
│   ├── LoginInterceptor.java       # 登录拦截器（CORS 预检放行）
│   └── RefreshTokenInterceptor.java # Token 刷新拦截器
├── Controller/
│   ├── ChatController.java         # 聊天接口（/chat 前缀 + 参数校验 + 分页）
│   └── UserController.java         # 用户接口（/user 前缀 + @Valid 校验）
├── Dto/
│   ├── Result.java                 # 统一返回结果（支持自定义 code）
│   ├── SMessage.java              # 消息实体（含 id 游标 + 时间戳 + 模型名，去 Lombok）
│   ├── User.java                   # 用户实体
│   ├── UserDto.java               # 用户 DTO（含 isNewUser 字段）
│   └── LoginFormDTO.java          # 登录表单 DTO（@NotBlank + @Pattern 校验）
├── Entity/
│   ├── ChatSession.java            # 会话实体（tb_chat_session）
│   └── ChatMessage.java            # 消息实体（tb_chat_message，含 model）
├── Exception/
│   ├── BusinessException.java      # 业务异常（含错误码）
│   └── GlobalExceptionHandler.java # 全局异常处理器（含 @Valid/404/413）
├── Mapper/
│   ├── UserMapper.java             # 用户 Mapper
│   ├── ChatSessionMapper.java      # 会话 Mapper（upsert + 按用户查询）
│   └── ChatMessageMapper.java      # 消息 Mapper（游标分页查询）
├── Repository/
│   ├── ChatSessionRepository.java  # 会话仓储接口（含分页重载）
│   ├── UserRepository.java         # 用户仓储接口
│   └── impl/
│       ├── ChatSessionRepositoryimpl.java  # MySQL 主存储 + Redis 缓存（id 游标）
│       └── UserRepositoryimpl.java
├── Service/
│   ├── ChatService.java            # 聊天服务接口（含模型名 + 分页）
│   ├── UserService.java            # 用户服务接口
│   ├── ChatPersistenceService.java # 聊天持久化服务接口（MySQL 事务）
│   ├── ChatTitleService.java       # ⭐ AI 标题生成服务接口
│   └── impl/
│       ├── ChatServiceimpl.java    # 含上下文裁剪 + 模型路由 + 消息级模型记录
│       ├── UserServiceimpl.java
│       ├── ChatPersistenceServiceimpl.java
│       └── ChatTitleServiceimpl.java # ⭐ 异步标题生成（@Async 独立线程池）
└── Utils/
    ├── UserHolder.java             # 用户上下文（ThreadLocal）
    ├── PasswordUtils.java          # 密码工具（BCrypt）
    ├── RegexUtils.java             # 正则工具（含密码校验）
    ├── RegexPatterns.java          # 正则表达式常量
    └── RedisConstants.java         # Redis Key 常量
```

## 当前状态（2026-10-28 13:34 更新）

### ✅ 已完成功能
#### 用户认证系统
- [x] 发送验证码（POST /user/code）+ 60秒防刷冷却
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
- [x] 获取会话历史（GET /chat/history）+ 时间戳 + 游标分页
- [x] 获取会话列表（GET /chat/sessions）
- [x] 更新会话标题（PUT /chat/title）+ 长度限制
- [x] 清空会话（DELETE /chat/clear-session）

#### 多模型支持 ⭐ 2026-10-25
- [x] `ModelRouter` - 按模型名路由 Ollama（本地）/ OpenAI 兼容协议（DeepSeek）
- [x] `ModelProperties` - 模型白名单 + 默认模型 + Ollama 模型列表（配置化）
- [x] `GET /chat/models` - 查询可用模型白名单（公开接口）
- [x] `POST /chat/ask` 和 `GET /chat/ask-stream` 支持 `modelName` 参数
- [x] 模型名不在白名单时报 400（不静默兜底，避免用户以为切换成功）
- [x] 系统提示词集中在 `ModelRouter` 构建 ChatClient 时注入

#### 消息级模型追踪 ⭐ 2026-10-28
- [x] `tb_chat_message` 新增 `model` 列，记录每条消息实际使用的模型
- [x] `ChatMessage` / Redis `SMessage` / `ChatMessageMapper` SQL 全链路带 `model`
- [x] `ChatServiceimpl` 通过 `ModelRouter.resolve()` 解析默认模型后落库，未显式传 `modelName` 也记录实际模型
- [x] `/chat/history` 响应新增 `model` 字段；历史消息（加列之前）为 `null`
- [x] 旧缓存只在缺少 `id` 时回源重建；`model=null` 是历史数据合法状态，不会导致缓存永久未命中

#### 会话锁模型 + 跨模型历史 ⭐ 2026-10-28
- [x] `tb_chat_session` 新增 `model` 列，首条消息锁定模型（`ON DUPLICATE KEY UPDATE model = COALESCE(model, ...)`）
- [x] 同一会话换模型返回 **409**，提示"切换模型请新建会话"，避免跨模型上下文污染
- [x] 锁模型走 MySQL 真源 + Redis `aichat:chat:sessionmodel:{userId}` Hash 缓存，miss 回源回填
- [x] 标题缓存 key 收敛为 `aichat:chat:title:{userId}`（Hash，field = sessionId），避免每会话一个 key
- [x] 历史改为 `ChatHistoryFormatter` 渲染成带"发言者 + 模型名（我/其他模型）"标注的文本块
- [x] 历史以 system message 注入，并保留 `ModelRouter.SYSTEM_PROMPT` 基础人设（`.system()` 会覆盖 `defaultSystem`）
- [x] `/chat/sessions` 响应新增 `model` 字段；模型查询改为批量（一次 `HGETALL` + 最多一次 MySQL），消除逐会话查库
- [x] `GlobalExceptionHandler` 补齐 `409 -> HttpStatus.CONFLICT`（此前 409 会落到 400）

#### 历史消息分页 ⭐ 2026-10-25
- [x] `SMessage` 新增 `id` 字段（MySQL 自增 id 作为分页游标）
- [x] `GET /chat/history` 支持 `beforeId` + `limit` 游标分页
- [x] `limit` 不传时保持旧行为（返回全量历史），现有前端无需改动
- [x] `beforeId` 必须配 `limit` 使用，`limit` 范围 1-100
- [x] `ChatMessageMapper` 新增 `selectLatestBySession` 和 `selectBySessionBeforeId`
- [x] 分页查询时不回写缓存（避免把"一页"当成全量历史）
- [x] 旧版消息缓存缺少 `id` 时自动回源 MySQL 重建

#### 上下文裁剪 ⭐ 2026-10-25
- [x] `ChatServiceImpl.trimHistory()` - 只把最近 N 条历史送给模型
- [x] `app.chat.context-max-messages` 配置项（默认 20，<=0 表示不裁剪）
- [x] 历史接口仍返回全量/分页数据，裁剪只影响 AI 上下文

#### 异步 AI 标题生成 ⭐ 2026-10-25
- [x] `ChatTitleService` - 会话标题生成接口
- [x] `ChatTitleServiceimpl` - 使用默认模型（本地 Ollama，便宜快）异步生成标题
- [x] `AsyncConfig` - 独立线程池（core=2, max=4, queue=100, CallerRunsPolicy）
- [x] 首次对话时先写兜底标题落库，再提交异步 AI 标题任务
- [x] 如果用户已手动修改标题（标题不再等于兜底值），跳过 AI 标题覆盖
- [x] 标题生成失败时静默保留兜底标题，绝不影响主流程

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
- [x] 全局异常处理器（含 @Valid/404/413）
- [x] 验证码发送防刷冷却（60 秒）
- [x] 会话数据用户隔离
- [x] CORS 配置（本地开发端口）

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

**1. 多模型架构**
- 不再使用单一 `ChatClient` Bean，改为 `ModelRouter` 按模型名分发
- 两个 starter（Ollama + OpenAI）同时存在时，Spring AI 自动装配会产生多个 ChatModel Bean，直接注入会冲突
- `ModelRouter` 显式注入两个 ChatModel，手动构建 ChatClient，按白名单分发
- 模型白名单、默认模型、哪些走 Ollama 都从配置读（`app.models.*`），不硬编码

**2. 历史分页**
- 消息表用自增 `id` 保证稳定排序，`id` 作为分页游标
- `beforeId` + `limit` 游标分页，不传 `limit` 时保持旧行为
- 缓存是"全量"，分页时在内存中过滤；缓存未命中且需要分页时直接查 MySQL 不回写
- 旧版本缓存缺少 `id` 时视为未命中，回源 MySQL 重建

**3. 上下文裁剪**
- `contextMaxMessages` 配置项控制送给模型的历史条数（默认 20）
- 避免长对话撑爆 Prompt

**4. 异步标题生成**
- 标题生成是"可延迟、可失败"的旁路任务，放独立线程池避免占用 Tomcat 请求线程
- 队列满时回退到调用线程执行（CallerRunsPolicy），宁可慢也不静默丢弃
- 只在标题仍等于兜底值时才覆盖，避免 AI 结果晚到冲掉用户手动改的标题
- 失败时静默保留兜底标题，绝不影响主流程

**5. 用户接口增强**
- `UserController` 使用 `@Valid` 注解触发 Bean Validation
- `GlobalExceptionHandler` 新增处理 `MethodArgumentNotValidException`、`NoResourceFoundException`、`MaxUploadSizeExceededException`

**6. 消息级模型追踪（2026-10-28）**
- 多模型切换后，只记录"当前会话用了哪个模型"不够，需要按消息追溯
- 同步和流式接口都在落库前调用 `ModelRouter.resolve()`，把默认模型解析成实际模型名
- MySQL、Redis 缓存、历史接口三处字段保持一致；旧数据保留 `null`，不伪造模型
- 旧缓存兼容只检查 `id`，避免历史消息 `model=null` 导致每次读都回源

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
| POST | /chat/ask | sessionid, question, modelName? | 发送聊天消息（同步） |
| GET | /chat/ask-stream | sessionid, question, modelName? | 流式发送聊天消息（SSE） |
| GET | /chat/history | sessionid, beforeId?, limit? | 获取会话历史（含时间戳 + 分页） |
| GET | /chat/sessions | - | 获取会话列表 |
| PUT | /chat/title | sessionid, title | 更新会话标题 |
| DELETE | /chat/clear-session | sessionid | 清空会话 |

### 技术要点
1. **MySQL 是聊天数据持久化主存储**，Redis 只做 7 天热缓存
2. 密码使用 BCrypt 自动加盐加密
3. Token 有效期 7 天，每次请求自动续期
4. 验证码有效期 5 分钟，同一手机号 60 秒冷却
5. 新用户标记有效期 365 天，仅设置密码后自动取消
6. 会话数据按用户隔离，key 格式：`aichat:chat:sessions:{userId}`
7. 消息按自增 `id` 稳定排序，`id` 作为分页游标
8. 全局异常处理：BusinessException 携带错误码，统一 JSON 响应
9. 双拦截器：Token 刷新 + 登录校验，CORS 预检放行
10. 参数校验：长度限制 + 格式校验 + 归属校验 + @Valid
11. 多模型路由：`ModelRouter` 按白名单分发 Ollama / DeepSeek
12. 上下文裁剪：默认只送最近 20 条历史给模型
13. 异步标题：独立线程池，不阻塞主流程
14. 模型追踪：每条消息记录实际使用的模型，默认模型也会解析后落库

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
  model       VARCHAR(64) COMMENT '本轮使用的模型名',
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

# 上下文裁剪
app.chat.context-max-messages=20

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

## BYOK 设计思路（仅设计，未实现）

当前项目是**平台内置 key**（管理员在后端配置，用户无感），这是学习项目最合理的形态——一个人/一台服务器/一个 key，BYOK 没有真实用户和付费体系根本运转不起来。

若未来要做 BYOK，设计思路如下（面试可展开）：

1. **key 加密存储**：用户填 key，后端用 AES 加密存到 `tb_user` 或独立表（绝不存明文）
2. **有效性校验**：入库时发 1 个 token 的测试请求，确认 key 可用
3. **限额 + 计费**：每用户 token 配额，Redis 计数防刷
4. **故障归因**：区分"用户 key 失效" vs "服务自身故障"，给不同提示
5. **平台 key 兜底**：用户 key 失效/没填时，回落平台内置 key
6. **多租户隔离**：key 按 userId 维度存，A 用户的 key 绝不给 B 用

**取舍**：BYOK 是产品级功能，涉及加密/计费/限额/多租户，学习项目里"讲得清设计"比"做出来"更有面试价值。

## 文档
- `README.md` - 项目说明 + API 总览 + 配置项
- `docs/API.md` - 后端接口文档（供前端使用）
- `docs/DEVELOPMENT_PLAN.md` - 后续开发计划
- `docs/INTERVIEW_ROADMAP.md` - 面试路线图
- `docs/聊天记录持久化设计.md` - 持久化设计
- `docs/踩坑记录.md` - 开发踩坑记录
- `docs/schema.sql` - 数据库初始化脚本
- `docs/本地启动.md` - 本地启动指南
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
- [x] 消息级模型追踪（model 列 + 历史接口返回）✅ 2026-10-28
- [ ] BYOK（当前仅设计，见上）
- [ ] 聊天/登录接口限流 + 安全加固
- [ ] Testcontainers 集成测试 + GitHub Actions CI（单元测试已有）
- [ ] Docker Compose 部署
- [ ] RAG 知识库（可选加分项）
- [ ] Micrometer 可观测性 + 压测
- [ ] springdoc-openapi 接口文档

---
*最后更新: 2026-10-28 13:34（消息级模型追踪 + BYOK 设计说明）*
