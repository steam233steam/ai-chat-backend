# AI Chat 项目开发状态

## 项目概览
- **项目名称**: aichat
- **技术栈**: Spring Boot 3.2.5 + Spring AI (1.0.0-M5) + Redis + Ollama + MySQL + MyBatis
- **Java版本**: 17
- **工作目录**: D:\maven-project\aichat
- **开始开发日期**: 2026-09-12
- **当前阶段**: 生产级异常处理 + 参数校验收尾完成 ✅（阶段一）

## 架构分层

```
com.aichat/
├── AichatApplication.java          # 启动类
├── Config/
│   ├── AsyncConfig.java            # ⭐ @EnableAsync + 标题生成线程池
│   ├── ModelProperties.java        # ⭐ 多模型白名单/默认模型配置绑定
│   ├── ModelRouter.java            # ⭐ 按模型名路由 Ollama/DeepSeek
│   ├── RedisConfig.java            # Redis 配置
│   ├── ChatClientConfig.java       # 系统提示词配置（多模型下已迁至 ModelRouter）
│   ├── WebMvcConfig.java           # MVC 配置（拦截器 + CORS）
│   ├── LoginInterceptor.java       # 登录拦截器（CORS 预检放行）
│   └── RefreshTokenInterceptor.java # Token 刷新拦截器
├── Controller/
│   ├── ChatController.java         # 聊天接口（/chat 前缀 + 参数校验）
│   └── UserController.java         # 用户接口（/user 前缀）
├── Dto/
│   ├── Result.java                 # 统一返回结果（支持自定义 code）
│   ├── SMessage.java              # Redis 消息实体（含时间戳，去 Lombok）
│   ├── User.java                   # 用户实体
│   ├── UserDto.java               # 用户 DTO（含 isNewUser 字段）
│   └── LoginFormDTO.java          # 登录表单 DTO
├── Entity/                         # ⭐ 2026-10-22 新增
│   ├── ChatSession.java            # 会话实体（tb_chat_session）
│   └── ChatMessage.java            # 消息实体（tb_chat_message）
├── Exception/
│   ├── BusinessException.java      # 业务异常（含错误码）
│   └── GlobalExceptionHandler.java # 全局异常处理器
├── Mapper/
│   ├── UserMapper.java             # 用户 Mapper
│   ├── ChatSessionMapper.java      # ⭐ 会话 Mapper（upsert + 按用户查询）
│   └── ChatMessageMapper.java      # ⭐ 消息 Mapper（按 id 稳定排序）
├── Repository/
│   ├── ChatSessionRepository.java  # 会话仓储接口
│   ├── UserRepository.java         # 用户仓储接口
│   └── impl/
│       ├── ChatSessionRepositoryimpl.java  # ⭐ MySQL 主存储 + Redis 缓存
│       └── UserRepositoryimpl.java
├── Service/
│   ├── ChatService.java            # 聊天服务接口
│   ├── ChatTitleService.java       # ⭐ 会话标题生成接口
│   ├── UserService.java            # 用户服务接口
│   ├── ChatPersistenceService.java # ⭐ 聊天持久化服务接口
│   └── impl/
│       ├── ChatServiceimpl.java
│       ├── ChatTitleServiceimpl.java       # ⭐ 异步生成标题 + 不覆盖用户标题
│       ├── UserServiceimpl.java
│       └── ChatPersistenceServiceimpl.java # ⭐ MySQL 事务写入
└── Utils/
    ├── UserHolder.java             # 用户上下文（ThreadLocal）
    ├── PasswordUtils.java          # 密码工具（BCrypt）
    ├── RegexUtils.java             # 正则工具（含密码校验）
    ├── RegexPatterns.java          # 正则表达式常量
    └── RedisConstants.java         # Redis Key 常量
```

## 当前状态（2026-10-25 10:08 更新）

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

#### 聊天功能
- [x] 同步对话（POST /chat/ask）+ 参数长度校验
- [x] 流式对话（GET /chat/ask-stream）+ SSE
- [x] 获取会话历史（GET /chat/history）+ 时间戳
- [x] 获取会话列表（GET /chat/sessions）
- [x] 更新会话标题（PUT /chat/title）+ 长度限制
- [x] 清空会话（DELETE /chat/clear-session）

#### 数据持久化 ⭐ 2026-10-22
- [x] 新增 `tb_chat_session` 表（会话元数据）
- [x] 新增 `tb_chat_message` 表（聊天消息）
- [x] `ChatSessionMapper` - upsert + 按用户查询 + 标题查询 + 删除
- [x] `ChatMessageMapper` - 插入 + 按会话查询（id 排序）+ 删除
- [x] `ChatPersistenceService` - MySQL 事务写入（消息 + 会话元数据）
- [x] `ChatSessionRepositoryimpl` - 改为"先写 MySQL，再写 Redis"
- [x] 缓存未命中自动回源 MySQL 并回填
- [x] Redis 写入失败时主动失效缓存，降级到 MySQL
- [x] 旧库 password NOT NULL 迁移脚本

#### 安全与工具
- [x] Token 拦截器（RefreshTokenInterceptor）
- [x] 登录拦截器（LoginInterceptor）+ CORS 预检放行
- [x] BCrypt 密码加密
- [x] 手机号/验证码/密码正则校验
- [x] ThreadLocal 用户上下文
- [x] 新用户标记功能
- [x] 全局异常处理器（BusinessException）
- [x] 验证码发送防刷冷却（60 秒）
- [x] 会话数据用户隔离
- [x] CORS 配置（本地开发端口）

#### 生产级异常处理 + 参数校验 ⭐ 2026-10-25
- [x] 拦截器 401 统一走 `BusinessException`（不再手写 JSON）
- [x] `GlobalExceptionHandler` 补全 handler：
  - `MethodArgumentNotValidException`（@Valid 触发）
  - `NoResourceFoundException` / `NoHandlerFoundException`（404）
  - `MethodArgumentTypeMismatchException`（参数类型不匹配）
  - `MaxUploadSizeExceededException`（文件上传超限，预留）
- [x] 引入 `spring-boot-starter-validation` 依赖
- [x] `LoginFormDTO` 加 `@NotBlank` + `@Pattern`（手机号格式）
- [x] `UserController` 登录类接口加 `@Valid` 注解
- [x] 命名规范：`SendSession`/`ClearSession` → `sendSession`/`clearSession`

### 📝 核心改进（2026-10-22 持久化改造）

**问题**：聊天数据只存在 Redis（7 天 TTL），过期后历史丢失。

**方案**：MySQL 为持久化主存储，Redis 降级为 7 天热缓存。

1. **写入路径**：`ChatService` → `ChatSessionRepository.save()` → `ChatPersistenceService.saveMessage()`（MySQL 事务）→ Redis 缓存
   - 每条消息都 upsert 会话元数据，刷新 `update_time`，保证会话列表按最近活跃排序
   - 消息和会话元数据在同一事务写入，避免半成功
2. **读取路径**：Redis 命中 → 直接返回；未命中 → 查 MySQL → 回填 Redis
   - 消息缓存失败时删除不完整缓存
   - 会话列表缓存过期时从 MySQL 整体重建，避免列表不完整
3. **清除路径**：先删 MySQL（消息 + 会话），再清 Redis；返回值区分"数据库有数据"和"只有 Redis 缓存"
4. **稳定性**：消息表用自增 `id` 保证同一毫秒内消息的稳定排序，不依赖时间精度
5. **迁移**：`tb_user.password` 改为 NULL 允许（新用户密码为 NULL 的设计需要）

### API 接口总览（对外行为不变）

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
| POST | /chat/ask | sessionid, question | 发送聊天消息（同步） |
| GET | /chat/ask-stream | sessionid, question | 流式发送聊天消息（SSE） |
| GET | /chat/history | sessionid | 获取会话历史（含时间戳） |
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
7. 消息按自增 id 稳定排序（不依赖时间精度）
8. 全局异常处理：BusinessException 携带错误码，统一 JSON 响应
9. 双拦截器：Token 刷新 + 登录校验，CORS 预检放行

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

### tb_chat_session 表 ⭐ 新增
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

### tb_chat_message 表 ⭐ 新增
```sql
CREATE TABLE tb_chat_message (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
  user_id     VARCHAR(32) NOT NULL COMMENT '用户 uid',
  session_id  VARCHAR(64) NOT NULL COMMENT '所属会话',
  role        VARCHAR(16) NOT NULL COMMENT 'user/assistant/system',
  content     TEXT NOT NULL COMMENT '消息内容',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '消息时间',
  KEY idx_user_session (user_id, session_id, id)
);
```

**完整建表脚本**：`docs/schema.sql`  
**迁移脚本**：`docs/migration/2026-10-22-fix-tb-user-password-null.sql`

## 依赖项
```xml
- spring-boot-starter-web
- spring-boot-starter-validation   # ⭐ 2026-10-25 参数校验
- spring-boot-starter-data-redis
- spring-ai-ollama-spring-boot-starter
- mysql-connector-j (8.0.33)
- mybatis-spring-boot-starter (3.0.3)
- hutool-core (5.8.25)
- hutool-crypto (5.8.25)
- spring-boot-starter-test
```

## 文档
- `docs/API.md` - 后端接口文档（供前端使用）
- `docs/DEVELOPMENT_PLAN.md` - 后续开发计划（暑期求职版，10 周路线）
- `docs/聊天记录持久化设计.md` - 持久化设计说明
- `docs/schema.sql` - 数据库初始化脚本
- `docs/本地启动.md` - 本地启动指南（含虚拟机端口转发说明）
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
- [x] 生产级异常处理 + @Valid 参数校验 ✅ 2026-10-25（阶段一收尾）
- [ ] 阶段二：管理员后台 / 消息搜索等业务广度模块
- [ ] 历史消息分页 + 长上下文裁剪
- [ ] 聊天/登录接口限流 + 安全加固
- [ ] Testcontainers 集成测试 + GitHub Actions CI
- [ ] Docker Compose 部署
- [ ] RAG 知识库（可选加分项）
- [ ] Micrometer 可观测性 + 压测
- [ ] springdoc-openapi 接口文档

## 多模型架构 ⭐ 2026-10-25
- [x] Ollama（本地） + DeepSeek（云端，OpenAI 兼容协议）双供应商
- [x] `ModelRouter` 按模型名路由到对应 `ChatModel`（规避多 starter 的 Bean 冲突）
- [x] 模型白名单 + 默认模型配置化（`app.models.*`）
- [x] `POST /chat/ask` / `GET /chat/ask-stream` 支持 `modelName` 参数
- [x] `GET /chat/models` 返回可用模型白名单（前端渲染下拉框）
- [x] 标题生成固定用默认（本地）模型，不跟随用户聊天模型

### 技术要点
1. **DeepSeek 走 OpenAI 兼容协议**：Spring AI 1.0.0-M5 没有 DeepSeek 官方 starter，且 M5 的 OpenAI starter 叫 `spring-ai-openai-spring-boot-starter`（artifact 名不带 `model-` 前缀）。利用 DeepSeek API 与 OpenAI 完全兼容的特性，配 `spring.ai.openai.base-url=https://api.deepseek.com` + `api-key` 即可。
2. **多 starter 的 Bean 冲突**：Ollama + OpenAI 两个 starter 各自动装配一个 `ChatModel` 和 `ChatClient.Builder`，直接注入会报 `NoUniqueBeanDefinitionException`。解法：不注入自动装配的单例 `ChatClient`，改为在 `ModelRouter` 里手动 `ChatClient.builder(chatModel)` 建两个带默认 system prompt 的 client，按模型名分发。
3. **key 安全**：`spring.ai.openai.api-key` 从环境变量 `${DEEPSEEK_API_KEY}` 读，不提交到 git。
4. **AI 标题用本地模型**：`ChatTitleServiceimpl` 调 `modelRouter.client(null)` 走默认本地模型，便宜快，不消耗云端额度。

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

---
*最后更新: 2026-10-25 17:00（多模型切换 + BYOK 设计思路）*
