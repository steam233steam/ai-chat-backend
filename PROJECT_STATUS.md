# AI Chat 项目开发状态

## 项目概览
- **项目名称**: aichat
- **技术栈**: Spring Boot 3.2.5 + Spring AI (1.0.0-M5) + Redis + Ollama + DeepSeek (OpenAI 兼容) + MySQL + MyBatis + Springdoc
- **Java版本**: 17
- **工作目录**: D:\maven-project\aichat
- **开始开发日期**: 2026-09-12
- **当前阶段**: MindButler P1.1（长期记忆 + 口语化任务 + 待确认动作 + 查询意图 + 记忆会话隔离）✅

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
│   ├── RefreshTokenInterceptor.java # Token 刷新拦截器（入口清理 ThreadLocal）
│   ├── MindButlerProperties.java   # ⭐ MindButler P1 配置（app.mindbutler.*，启动校验时区）
│   ├── IntentExecutorConfig.java   # ⭐ 意图提取线程池配置
│   ├── MindButlerStartupValidator  # ⭐ 启动校验（模型白名单 / 时区）
│   └── ClockConfig.java            # ⭐ 时钟配置（可测试）
├── Controller/
│   ├── ChatController.java         # 聊天接口（/chat 前缀 + 参数校验 + 分页 + @Operation）
│   ├── UserController.java         # 用户接口（/user 前缀 + @Valid 校验 + @Operation）
│   ├── TaskController.java         # ⭐ 用户任务/计划接口（/user/tasks，MindButler P1）
│   ├── MemoryController.java       # ⭐ 用户记忆接口（/user/memories，MindButler P1）
│   └── PendingActionController.java # ⭐ 待确认动作接口（/user/pending-actions，MindButler P1）
├── Dto/
│   ├── Result.java                 # 统一返回结果（支持自定义 code）
│   ├── SMessage.java               # 消息实体（含 id 游标 + model + 时间戳，去 Lombok）
│   ├── User.java                   # 用户实体
│   ├── UserDto.java                # 用户 DTO（含 isNewUser 字段）
│   ├── LoginFormDTO.java           # 登录表单 DTO（@NotBlank + @Pattern 校验）
│   ├── IntentOutcome.java          # ⭐ 意图处理结果（不可变，工厂方法构造）
│   ├── IntentExtractionResult.java # ⭐ 提取器/解析器与 Service 的边界对象
│   ├── MemoryCandidate.java        # ⭐ 记忆候选
│   ├── TaskCandidate.java          # ⭐ 任务候选
│   ├── PendingActionVO.java        # ⭐ 待确认动作 VO
│   ├── ConfirmResult.java          # ⭐ 确认结果
│   ├── MemoryVO.java               # ⭐ 记忆 VO
│   ├── TaskVO.java                 # ⭐ 任务 VO
│   ├── CreateMemoryRequest.java    # ⭐ 创建记忆请求
│   ├── UpdateMemoryRequest.java    # ⭐ 更新记忆请求
│   ├── CreateTaskRequest.java      # ⭐ 创建任务请求
│   ├── UpdateTaskRequest.java      # ⭐ 更新任务请求
│   └── CompleteTaskRequest.java    # ⭐ 完成任务请求
├── Entity/
│   ├── ChatSession.java            # 会话实体（tb_chat_session，含 model 列）
│   ├── ChatMessage.java            # 消息实体（tb_chat_message，含 model 列）
│   ├── UserMemory.java             # ⭐ 用户长期记忆（tb_user_memory）
│   ├── UserTask.java               # ⭐ 用户任务/计划（tb_user_task）
│   └── PendingAction.java          # ⭐ 待确认动作（tb_pending_action）
├── Exception/
│   ├── BusinessException.java      # 业务异常（含错误码）
│   └── GlobalExceptionHandler.java # 全局异常处理器（含 @Valid/404/409/413）
├── Mapper/
│   ├── UserMapper.java             # 用户 Mapper
│   ├── ChatSessionMapper.java      # 会话 Mapper（upsert + 锁模型 + 按用户查询）
│   ├── ChatMessageMapper.java      # 消息 Mapper（游标分页查询 + model 列）
│   ├── UserMemoryMapper.java       # ⭐ 记忆 Mapper
│   ├── UserTaskMapper.java         # ⭐ 任务 Mapper
│   └── PendingActionMapper.java    # ⭐ 待确认动作 Mapper
├── Repository/
│   ├── ChatSessionRepository.java  # 会话仓储接口（含分页 + 锁模型 + 批量取模型）
│   ├── UserRepository.java         # 用户仓储接口
│   └── impl/
│       ├── ChatSessionRepositoryimpl.java  # MySQL 主存储 + Redis 缓存（id 游标 + 锁模型）
│       └── UserRepositoryimpl.java
├── Service/
│   ├── ChatService.java            # 聊天服务接口（含模型名 + 分页 + 意图拦截）
│   ├── UserService.java            # 用户服务接口
│   ├── ChatPersistenceService.java # 聊天持久化服务接口（MySQL 事务 + 锁模型）
│   ├── ChatTitleService.java       # AI 标题生成服务接口
│   ├── IntentService.java          # ⭐ 意图服务（口语 → 待确认动作，编排入口）
│   ├── IntentExtractor.java        # ⭐ 意图提取器接口（LLM + timeout）
│   ├── MemoryService.java          # ⭐ 用户长期记忆服务
│   ├── TaskService.java            # ⭐ 用户任务/计划服务
│   ├── PendingActionService.java   # ⭐ 待确认动作服务（v3 状态机）
│   ├── support/                    # ⭐ Service 的协作组件（非业务接口）
│   │   ├── IntentDailyLimiter.java   # 每用户每日意图提取限流
│   │   └── IntentCandidateSanitizer  # 候选校验/截断/敏感词
│   └── impl/
│       ├── ChatServiceImpl.java    # 含历史角色 + 字符预算 + 输出净化 + 流式过滤 + 意图拦截
│       ├── UserServiceimpl.java
│       ├── ChatPersistenceServiceimpl.java
│       ├── ChatTitleServiceimpl.java # 异步标题生成（@Async 独立线程池 + 标题净化）
│       ├── IntentServiceimpl.java    # ⭐ 意图编排（粗筛 → 限流 → 提取 → 校验 → pending → 文案）
│       ├── IntentExtractorimpl.java  # ⭐ LLM 提取 + timeout + 结构化解析
│       ├── MemoryServiceimpl.java    # ⭐ 记忆 CRUD + 注入 + 导出 + 乐观锁
│       ├── TaskServiceimpl.java      # ⭐ 任务 CRUD + 完成/重复滚动 + 到期查询
│       └── PendingActionServiceimpl  # ⭐ pending 状态机 + 事务 + 原子确认
└── Utils/
    ├── UserHolder.java             # 用户上下文（ThreadLocal）
    ├── PasswordUtils.java          # 密码工具（BCrypt）
    ├── RegexUtils.java             # 正则工具（含密码校验）
    ├── RegexPatterns.java          # 正则表达式常量
    ├── RedisConstants.java         # Redis Key 常量（含会话锁模型 Hash）
    ├── ChatHistoryBuilder.java     # 把持久化历史转换成真实 Spring AI 消息角色（条数 + 字符预算裁剪）
    ├── ModelOutputSanitizer.java   # 模型输出净化（剥 <ds_safety> 标签 + 标题净化）
    ├── StreamingSafetyFilter.java  # 有状态流式过滤器（跨 chunk 剥安全标签）
    ├── IntentDetector.java         # ⭐ 意图粗筛（关键词触发，避免每条都走 LLM）
    ├── IntentJsonParser.java       # ⭐ LLM JSON → DTO 解析
    ├── IntentPayloadCodec.java     # ⭐ pending payload 编解码（唯一入口）
    ├── IntentReplyFormatter.java   # ⭐ 确认/结果文案生成
    ├── IntentPrompts.java          # ⭐ 意图提取 Prompt
    ├── PromptBuilder.java          # ⭐ 注入画像 + 历史组装
    ├── MemoryKey.java              # ⭐ 记忆 key 枚举（style/goal/schedule/relation）
    └── SensitiveContentFilter.java # ⭐ 敏感词过滤
```

## 当前状态（2026-11-03 17:24 更新）

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
- [x] 同步对话（POST /chat/ask）+ 参数长度校验 + 输出净化 + 意图拦截
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
- [x] 分页查询时不回写缓存（避免把"一页"当成全量历史）
- [x] 旧版消息缓存缺少 `id` 时自动回源 MySQL 重建

#### 上下文裁剪（2026-11-01）
- [x] 按条数裁剪：`app.chat.context-max-messages`（默认 20，<=0 不裁剪）
- [x] 按字符预算裁剪：`app.chat.context-max-chars`（默认 24000，<=0 不限制）
- [x] 历史接口仍返回全量/分页数据，裁剪只影响 AI 上下文

#### 异步 AI 标题生成（2026-10-25）
- [x] `ChatTitleService` - 会话标题生成接口
- [x] 使用默认模型（本地 Ollama，便宜快）异步生成标题
- [x] 独立线程池（core=2, max=4, queue=100, CallerRunsPolicy）
- [x] 用户已手动改名时跳过 AI 覆盖；失败静默保留兜底标题
- [x] 标题走 `sanitizeTitle` 净化

#### 会话锁模型（2026-10-28）
- [x] 会话首条消息锁定模型，切换报 409
- [x] `tb_chat_session.model` + `COALESCE(model, #{model})` upsert
- [x] `GET /chat/sessions` / `GET /chat/history` 返回 model 字段

#### 模型输出净化（2026-11-01）
- [x] `ModelOutputSanitizer` - 剥 `<ds_safety>` 标签 + 标题净化
- [x] `StreamingSafetyFilter` - 跨 chunk 剥标签（标签可能被拆开）
- [x] 同步/流式/标题三路净化，不存脏数据

#### 历史角色真实化（2026-11-01）
- [x] 历史以真实 User/Assistant 角色送入，不拼 system（防提示注入）
- [x] 当前问题作为最后一条 UserMessage

#### 线程安全加固（2026-11-01）
- [x] `RefreshTokenInterceptor` 入口 + 出口双重清理 ThreadLocal
- [x] 防 SSE/异步请求串用户

#### 在线接口文档（2026-11-01）
- [x] `springdoc-openapi-starter-webmvc-ui`（2.5.0）
- [x] 全部接口加 `@Tag` + `@Operation` 注解
- [x] Swagger UI：`http://localhost:8080/swagger-ui.html`
- [x] OpenAPI JSON：`http://localhost:8080/v3/api-docs`
- [x] 生产可用 `SPRINGDOC_ENABLED=false` 关掉

#### MindButler P1：长期记忆 + 口语化任务 ⭐（2026-11-03）
- [x] 新增 `tb_user_memory` 表（结构化画像，固定 schema + status 生命周期 + expires_at 过期）
- [x] 新增 `tb_user_task` 表（任务/计划，due_at + recurrence + 乐观锁 + 软删）
- [x] 新增 `tb_pending_action` 表（AI 提案，确认后才写入 memory/task；pending_slot 生成列保证同 user+session 只有一个 pending）
- [x] `MemoryController` - 记忆 CRUD + 注入查询 + 导出（`/user/memories`）
- [x] `TaskController` - 任务 CRUD + 完成 + 到期查询（`/user/tasks`）
- [x] `PendingActionController` - 待确认动作 当前查询/确认/拒绝（`/user/pending-actions`）
- [x] `IntentService` - 口语 → 待确认动作编排（粗筛 → 限流 → 提取 → 校验 → pending → 文案）
- [x] `IntentExtractor` - LLM 提取（deepseek-chat 结构化能力强）+ timeout
- [x] `IntentDetector` - 关键词粗筛，避免每条消息都走 LLM
- [x] `IntentDailyLimiter` - 每用户每日提取限流（默认 50 次）
- [x] `IntentCandidateSanitizer` - 候选校验/截断/敏感词过滤
- [x] `IntentPayloadCodec` - pending payload 编解码（唯一入口）
- [x] `IntentReplyFormatter` - 确认/结果文案
- [x] `SensitiveContentFilter` - 敏感词过滤
- [x] `PromptBuilder` - 注入画像 + 历史组装
- [x] `MindButlerProperties` - 配置绑定 + 启动校验（时区只允许 Asia/Shanghai）
- [x] `IntentExecutorConfig` - 意图提取线程池
- [x] `MindButlerStartupValidator` - 启动校验（模型白名单）
- [x] `ClockConfig` - 时钟可测试化
- [x] 迁移脚本 `docs/migration/2026-11-02-mindbutler-p1.sql`
- [x] 设计文档 `docs/MindButler-P1设计.md` / `docs/MindButler-P1设计-v2.md`
- [x] 上线验收清单 `docs/P1-上线验收清单.md`
- [x] 代码结构说明 `docs/P1-代码结构.md`
- [x] 评测集 `docs/eval/mindbutler-intent-eval.json`
- [x] 聊天拦截：`ChatServiceimpl` 在 sendSession 前调 `IntentService.handle`，命中意图时返回文案 + pending，不继续正常聊天
- [x] 聊天文案弹窗标记（2026-11-03）：`MbMarkers` 在命中"提取出新候选并写 pending"时，在 `/chat/ask` 文案尾部追加 `[[MB:pending={id}]]` 标记
- [x] 前端一次 `/chat/ask` 即可拿到 pendingId + 文案，据此弹窗（不必额外查 current）；展示时剥离标记
- [x] 新增 `GET /user/pending-actions/{id}` 拉弹窗详情（存库文案已剥离标记，懒过期语义同 current）
- [x] 流式 `/chat/ask-stream` 与同步接口契约一致：`data` 为「确认文案 + [[MB:pending=N]]」，单测覆盖
- [x] 前端任务确认弹窗（TaskConfirmDialog）：SSE 完成后自动弹出，刷新/切回会话用 current 恢复，确认后刷新任务

#### MindButler P1.1：查询意图 + 记忆会话隔离（2026-11-03 20:xx）
- [x] 新增 `QUERY` 意图：`IntentDetector` 疑问词 + 主题词组合命中 → 直接读已有任务/记忆数据回答，**不走 LLM**（确定性回答）
- [x] `IntentQueryReplyBuilder` - 查询类文案格式化（任务列表 / 记忆列表，纯函数不查库）
- [x] `IntentServiceimpl.query()` - 问任务列 pending+done+到期，问记忆列 active，无数据时明确说"还没有"
- [x] 记忆会话隔离：`tb_user_memory` 新增 `session_id` 列（NULL=全局画像，有值=仅该会话生效）
- [x] `MemoryService.listForInjection(stable, dynamic, sessionScope)` - 会话隔离版注入查询（全局 + 当前会话）
- [x] `PromptBuilder.build(basePersona, sessionScope)` - 注入时只带"全局画像 + 当前会话画像"，会话 A 的人设不带进会话 B
- [x] `MemoryCandidate.scope`（global/session）- LLM 判定作用域，prompt 里说明长期习惯=global、本会话人设=session
- [x] 迁移脚本 `docs/migration/2026-11-03-mindbutler-p1-memory-session.sql`（存量 session_id 全 NULL，向后兼容）
- [x] 单测 205 个全通过（BUILD SUCCESS）

### 📝 核心改进（2026-11-03 P1.1）

**1. QUERY 意图（确定性查询，不走 LLM）**
- 疑问词（哪些/哪个/什么/多少…）AND 主题词（任务/待办/日程/计划/提醒 / 记忆/记住的/记得）组合命中
- 命中后直接读 `TaskService` / `MemoryService` 已有数据拼自然文案，**不调 LLM**（省钱 + 100% 确定性 + 不污染聊天）
- 没有数据时明确回答"你目前还没有任何任务 / 我还没记住关于你的信息"，不再误走"没识别出"兜底

**2. 记忆会话隔离（全局 vs 会话级人设）**
- `tb_user_memory.session_id`：NULL=全局画像（跨会话生效，默认），有值=仅该会话生效（如"这个会话里你是猫娘"）
- LLM 提取时多输出 `scope` 字段（global/session），`IntentJsonParser` 归一（非 session 一律 global）
- 注入链路：`PromptBuilder.build(base, sessionScope)` → `listForInjection(..., sessionScope)` → 只取"全局(NULL) + 该会话"，别的会话人设不串进来
- 去重范围随 sessionScope：会话级记忆只在同会话去重，全局记忆跨会话去重
- 存量数据 session_id 全 NULL → 行为不变，向后兼容

**3. 查询意图与提取意图的分工**
- "我有哪些任务/你记住我什么" → QUERY（读数据，确定性）
- "记住/明天/每周…" → MEMORY/TASK（LLM 提取 + pending 确认）
- 避免"问一句"被误判成"建一条任务"

### 📝 核心改进（2026-11-03 MindButler P1）

**1. 长期记忆（结构化画像，不用向量库）**
- `tb_user_memory`：固定 schema（memKey 是有限枚举 style/goal/schedule/relation）+ status 生命周期（active/superseded/deleted）+ expires_at 过期（dynamic 记忆 90 天，stable 不过期）
- confidence 仅用于排序/审计，不决定生效（生效只由用户确认决定）
- 注入：`listForInjection` 按 stable/dynamic 分开配额（各 5 条）+ 总字符预算（1200），`PromptBuilder` 拼进 system
- 安全：`SensitiveContentFilter` 过滤敏感词；stable 强制 expiresAt=null；手动创建 confidence=1.00

**2. 口语化任务（"记得周五交周报" → 任务）**
- `tb_user_task`：due_at（可空）+ recurrence（once/daily/weekly，monthly 预留 P1.5）+ 乐观锁 + 软删（status 与 deleted_at 分离）
- 完成：once → done；daily/weekly → 保持 pending + due_at 滚动到下一次
- 到期查询：`GET /user/tasks/due`（status=pending、有 due_at 且已到期，按 due_at 升序；P1 只读不回写 last_notified_at）
- 时区：P1 固定 Asia/Shanghai（启动校验，配置错在启动时失败）

**3. 待确认动作（v3 状态机）**
- LLM 提取的记忆/任务候选先落 `tb_pending_action`（status=pending），用户确认后才写进 tb_user_memory / tb_user_task
- `pending_slot` 生成列（`CONCAT(user_id, ':', session_id)` 当 status=pending）+ 唯一键，保证同一 user+session 只有一个 pending
- 确认原子 + 幂等：重复确认返回 alreadyConfirmed=true（不重复建）；已拒绝/过期 409
- actionType（memory/task/mixed）由 payload 内容推导，只用于展示/索引，不参与确认分支（确认时按实际 memories/tasks 分三路落库）

**4. 意图编排（聊天拦截）**
- `ChatServiceimpl.sendSession` 先调 `IntentService.handle(sessionId, message, sourceMessageId)`
- 编排顺序：`IntentDetector` 粗筛（NONE → 走正常聊天；CONFIRM → confirm；REJECT → reject；MEMORY/TASK → 提取）→ `IntentDailyLimiter` 限流 → `IntentExtractor` LLM 提取 → `IntentCandidateSanitizer` 校验 → `IntentPayloadCodec` 写 pending → `IntentReplyFormatter` 文案
- 提取失败/空候选 → 兜底文案（不让聊天 500，不污染上下文）
- `IntentOutcome` 是服务返回结果（不可变，工厂方法构造），放 Dto 不放 Service

**5. 包结构修正（复盘）**
- `IntentOutcome` / `IntentExtractionResult` 放 `Dto`（不是 Service）
- `IntentDailyLimiter` / `IntentCandidateSanitizer` 放 `Service.support`（协作组件，非业务接口）
- `IntentPayloadCodec` 是 pending payload 唯一编解码入口（IntentService 和 PendingActionService 不各写 ObjectMapper）
- 启动校验从 `IntentExtractorimpl` 的 @PostConstruct 拆到 `Config.MindButlerStartupValidator`

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
| POST | /chat/ask | sessionid, question, modelName? | 发送聊天消息（同步，会话锁模型，输出净化，意图拦截） |
| GET | /chat/ask-stream | sessionid, question, modelName? | 流式发送聊天消息（SSE，会话锁模型，流式安全过滤） |
| GET | /chat/history | sessionid, beforeId?, limit? | 获取会话历史（含时间戳 + model + 分页） |
| GET | /chat/sessions | - | 获取会话列表（含 model） |
| PUT | /chat/title | sessionid, title | 更新会话标题 |
| DELETE | /chat/clear-session | sessionid | 清空会话 |

#### MindButler P1 接口（/user 前缀）

**用户任务 `/user/tasks`**
| 方法 | 路径 | 参数 | 说明 |
|------|------|------|------|
| GET | /user/tasks/due | limit? | 到期待办（pending + 有 due_at + 已到期，due_at 升序） |
| GET | /user/tasks | status?, beforeId?, limit? | 任务列表（游标分页） |
| GET | /user/tasks/{id} | - | 查单条任务（含 overdue 计算；非本人/已删 404） |
| POST | /user/tasks | body | 手动创建任务（不走 LLM） |
| PUT | /user/tasks/{id} | body | 更新任务（乐观锁，只能改 pending，version 不匹配 409） |
| POST | /user/tasks/{id}/complete | body | 完成任务（once→done；daily/weekly 滚动到下次） |
| DELETE | /user/tasks/{id} | - | 删除任务（软删，幂等） |

**用户记忆 `/user/memories`**
| 方法 | 路径 | 参数 | 说明 |
|------|------|------|------|
| GET | /user/memories | limit? | 查询生效记忆（active 且未过期，limit 1-100） |
| GET | /user/memories/export | - | 导出全部记忆（含 superseded，隐私查看/迁移） |
| GET | /user/memories/{id} | - | 查单条记忆（含 status/expired 计算；非本人/已删 404） |
| POST | /user/memories | body | 手动创建记忆（source=manual，confidence=1.00） |
| PUT | /user/memories/{id} | body | 更新记忆（乐观锁，只能改 active 未过期，409） |
| DELETE | /user/memories/{id} | - | 删除记忆（软删，幂等） |

**待确认动作 `/user/pending-actions`**
| 方法 | 路径 | 参数 | 说明 |
|------|------|------|------|
| GET | /user/pending-actions/current | sessionId | 当前会话的待确认动作（没有/已过期 data=null） |
| POST | /user/pending-actions/{id}/confirm | - | 确认（原子，幂等；重复确认 alreadyConfirmed；已拒绝/过期 409） |
| POST | /user/pending-actions/{id}/reject | - | 拒绝（幂等；已拒绝静默成功；已确认/过期 409） |

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
12. 上下文裁剪：条数（默认 20）+ 字符预算（默认 24000）双重控制
13. 异步标题：独立线程池，不阻塞主流程，标题走净化
14. 会话锁模型：首条消息锁定，切换报 409
15. 历史角色真实化：User/Assistant 消息，不拼 system（防提示注入）
16. 模型输出净化：剥 `<ds_safety>` 标签，同步/流式/标题三路
17. 线程安全：拦截器入口 + 出口双重清理 ThreadLocal
18. 在线 Swagger：springdoc + @Operation 注解
19. MindButler P1：长期记忆（结构化画像）+ 口语化任务 + 待确认动作（v3 状态机）
20. 意图编排：粗筛 → 限流 → LLM 提取 → 校验 → pending → 文案，不污染聊天上下文

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
  model       VARCHAR(64) COMMENT '本轮使用的模型名',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '消息时间',
  KEY idx_user_session (user_id, session_id, id)
);
```

### tb_user_memory 表 ⭐ MindButler P1
```sql
CREATE TABLE tb_user_memory (
  id                BIGINT NOT NULL AUTO_INCREMENT,
  user_id           VARCHAR(32) NOT NULL COMMENT '用户 uid',
  session_id        VARCHAR(64) NULL COMMENT 'P1.1 会话隔离：NULL=全局画像；有值=仅该会话生效',
  mem_key           VARCHAR(32) NOT NULL COMMENT 'P1: style|goal|schedule|relation',
  content           VARCHAR(512) NOT NULL COMMENT '记忆内容（应用层限制 256 字符）',
  mem_type          ENUM('stable','dynamic') NOT NULL DEFAULT 'stable',
  status            ENUM('active','superseded','deleted') NOT NULL DEFAULT 'active',
  confidence        DECIMAL(3,2) NOT NULL DEFAULT 1.00 COMMENT '仅排序/审计，不决定生效',
  source            ENUM('manual','ai') NOT NULL DEFAULT 'manual',
  source_message_id BIGINT NULL COMMENT '来源聊天消息 id',
  replaces_id       BIGINT NULL COMMENT '替换的旧记忆 id（旧条置 superseded）',
  expires_at        DATETIME NULL COMMENT 'dynamic 过期时间；stable 为 NULL',
  version           INT NOT NULL DEFAULT 0 COMMENT '乐观锁',
  create_time       DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time       DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_memory_session (user_id, session_id, status)
);
```

### tb_user_task 表 ⭐ MindButler P1
```sql
CREATE TABLE tb_user_task (
  id                BIGINT NOT NULL AUTO_INCREMENT,
  user_id           VARCHAR(32) NOT NULL,
  title             VARCHAR(128) NOT NULL,
  due_at            DATETIME NULL COMMENT '无截止时间可为 NULL',
  timezone          VARCHAR(40) NOT NULL DEFAULT 'Asia/Shanghai',
  recurrence        ENUM('once','daily','weekly','monthly') NOT NULL DEFAULT 'once',
  recurrence_rule   VARCHAR(128) NULL COMMENT 'weekly: BYDAY=MO；monthly P1.5: BYMONTHDAY=31',
  status            ENUM('pending','done','cancelled') NOT NULL DEFAULT 'pending',
  deleted_at        DATETIME NULL COMMENT '软删；非 NULL 表示已删除',
  last_notified_at  DATETIME NULL COMMENT 'P3 推送用；P1 GET /due 不回写',
  completed_at      DATETIME NULL,
  raw_input         VARCHAR(512) NULL COMMENT '触发句截断，最大 512 字符',
  source_message_id BIGINT NULL,
  version           INT NOT NULL DEFAULT 0,
  create_time       DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time       DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id)
);
```

### tb_pending_action 表 ⭐ MindButler P1
```sql
CREATE TABLE tb_pending_action (
  id                BIGINT NOT NULL AUTO_INCREMENT,
  user_id           VARCHAR(32) NOT NULL,
  session_id        VARCHAR(64) NOT NULL,
  action_type       ENUM('memory','task','mixed') NOT NULL,
  payload           JSON NOT NULL COMMENT '提案内容（memories + tasks），限制 16KB',
  status            ENUM('pending','confirmed','rejected','expired') NOT NULL DEFAULT 'pending',
  expires_at        DATETIME NOT NULL,
  source_message_id BIGINT NULL,
  pending_slot      VARCHAR(140) GENERATED ALWAYS AS (
                    CASE WHEN status = 'pending' THEN CONCAT(user_id, ':', session_id) ELSE NULL END
                  ) STORED COMMENT '保证同一 user+session 只有一个 pending；非 pending 为 NULL',
  create_time       DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time       DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id)
);
```

**完整建表脚本**：`docs/schema.sql`  
**迁移脚本**：
- `docs/migration/2026-10-22-fix-tb-user-password-null.sql`
- `docs/migration/2026-10-28-add-chat-message-model.sql`
- `docs/migration/2026-10-28-add-chat-session-model.sql`
- `docs/migration/2026-11-02-mindbutler-p1.sql` ⭐

## 依赖项
```xml
- spring-boot-starter-web
- spring-boot-starter-validation
- spring-boot-starter-data-redis
- spring-ai-ollama-spring-boot-starter
- spring-ai-openai-spring-boot-starter  (DeepSeek)
- springdoc-openapi-starter-webmvc-ui (2.5.0)
- mysql-connector-j (8.0.33)
- mybatis-spring-boot-starter (3.0.3)
- hutool-core (5.8.25)
- hutool-crypto (5.8.25)
- spring-boot-starter-test
- httpclient5 (test scope，集成测试 4xx 处理)
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
app.chat.context-max-chars=24000

# 验证码开发模式（默认关闭，本地在 application-local.properties 显式设 true）
app.sms.dev-mode=${APP_SMS_DEV_MODE:false}

# MindButler P1（长期记忆 + 口语化任务 + 意图提取）
app.mindbutler.enabled=true
app.mindbutler.intent.enabled=true
app.mindbutler.intent.extract-model=deepseek-chat
app.mindbutler.intent.timeout-ms=5000
app.mindbutler.intent.daily-limit=50
app.mindbutler.intent.max-memories=5
app.mindbutler.intent.max-tasks=5
app.mindbutler.memory.dynamic-ttl-days=90
app.mindbutler.memory.max-per-user=100
app.mindbutler.memory.inject-max-stable=5
app.mindbutler.memory.inject-max-dynamic=5
app.mindbutler.memory.inject-max-chars=1200
app.mindbutler.task.zone=Asia/Shanghai
app.mindbutler.task.max-per-user=500
app.mindbutler.task.due-limit=50
app.mindbutler.pending.ttl-minutes=10
app.mindbutler.pending.payload-max-bytes=16384

# Swagger / OpenAPI
springdoc.enabled=true
springdoc.api-docs.path=/v3/api-docs
springdoc.swagger-ui.path=/swagger-ui.html
```

### 环境变量
| 变量 | 默认值 | 说明 |
|------|--------|------|
| `OLLAMA_BASE_URL` | `http://localhost:11434` | Ollama 服务地址 |
| `DEEPSEEK_API_KEY` | - | DeepSeek API Key（不提交 Git） |
| `REDIS_HOST` / `REDIS_PORT` | `localhost:16379` | Redis 地址/端口 |
| `MYSQL_URL` | `jdbc:mysql://localhost:13306/aichat` | MySQL 连接串 |
| `SPRINGDOC_ENABLED` | `true` | Swagger 开关（生产可关） |
| `APP_MINDBUTLER_ENABLED` | `true` | MindButler 总开关 |
| `APP_MINDBUTLER_INTENT_ENABLED` | `true` | 意图提取开关（关闭后聊天不拦截） |
| `APP_MINDBUTLER_INTENT_EXTRACT_MODEL` | `deepseek-chat` | 意图提取器模型 |

## 文档
- `README.md` - 项目说明 + API 总览 + 配置项
- `docs/API.md` - 后端接口文档（供前端使用）
- `docs/DEVELOPMENT_PLAN.md` - 后续开发计划
- `docs/INTERVIEW_ROADMAP.md` - 面试路线图
- `docs/MindButler-P1设计.md` - MindButler P1 设计（v2）
- `docs/P1-代码结构.md` - P1 代码结构说明（包职责 + 意图链路类图）
- `docs/P1-上线验收清单.md` - P1 上线验收清单
- `docs/聊天记录持久化设计.md` - 持久化设计
- `docs/踩坑记录.md` - 开发踩坑记录
- `docs/schema.sql` - 数据库初始化脚本
- `docs/本地启动.md` - 本地启动指南
- `docs/migration/` - 数据库迁移脚本
- `docs/eval/mindbutler-intent-eval.json` - 意图提取评测集
- `docs/refs/mika/` - 参考资料
- `docs/学习笔记.md` - 项目学习笔记
- `docs/备注.md` - 开发备注
- `PROJECT_STATUS.md` - 项目状态记录

## 开发环境说明（本地启动.md 摘要）
- 后端：`http://localhost:8080`
- 前端：Vite `http://localhost:5173`（/api 代理到后端）
- Ollama：`127.0.0.1:11434`（qwen2.5-coder:7b）
- MySQL：`127.0.0.1:13306`（VirtualBox 虚拟机 studybox 的 NAT 转发）
- Redis：`127.0.0.1:16379`（同上）
- Swagger UI：`http://localhost:8080/swagger-ui.html`
- 敏感配置放 `application-local.properties`（.gitignore 中不提交）

## 下一步计划（见 DEVELOPMENT_PLAN.md / MindButler-P1设计.md）
- [x] 聊天数据持久化（MySQL 主存储 + Redis 缓存）✅ 2026-10-22
- [x] 多模型路由（Ollama + DeepSeek）✅ 2026-10-25
- [x] 历史消息分页 ✅ 2026-10-25
- [x] 上下文裁剪 ✅ 2026-10-25
- [x] 异步 AI 标题生成 ✅ 2026-10-25
- [x] 会话锁模型 + 带发言人标注的历史 ✅ 2026-10-28
- [x] 验证码日志脱敏 + dev-mode 默认关闭 ✅ 2026-10-27
- [x] 模型输出净化 + 历史角色真实化 + 字符预算 + 线程安全 ✅ 2026-11-01
- [x] Springdoc/Swagger 在线接口文档 + httpclient5 测试依赖 ✅ 2026-11-01
- [x] MindButler P1（长期记忆 + 口语化任务 + 待确认动作）✅ 2026-11-03
- [x] MindButler P1.1（查询意图直读数据 + 记忆会话隔离）✅ 2026-11-03
- [ ] 聊天/登录接口限流 + 安全加固
- [ ] Testcontainers 集成测试 + GitHub Actions CI（单元测试已有）
- [ ] Docker Compose 部署
- [ ] RAG 知识库（可选加分项）
- [ ] Micrometer 可观测性 + 压测
- [ ] MindButler P1.5（monthly 重复任务 + 推送 + last_notified_at 回写）

---
*最后更新: 2026-11-03 20:47*
