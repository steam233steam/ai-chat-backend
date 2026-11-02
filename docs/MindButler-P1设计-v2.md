# MindButler P1 设计文档（v2 含业界调研）

> **历史版本**：本文件是 v2，已被 `MindButler-P1设计.md`（v3 施工版）取代；仅用于对照。

> **目标**：让 aichat 从"能聊天的 API"变成"记得住你的 AI 管家"。
> **P1 范围**：长期记忆 + 口语化任务（纯后端，不引入新组件，不引入向量库）。
> **P2/P3/P4 暂不实现**：MCP 数据接入 / 微信 QQ 客服 / 商业化，只在路线图里留钩子。
> **文档版本**：v2（含业界调研结论，2026-11-02）。v1 已作废。

---

## 0. 业界调研结论（本次新增，先读这节）

调研对象：GetProfile / Langmem (LangGraph) / Google Gemini Memory Bank / PersonaTree 论文 / PSG-Agent / 中文 DuoMem 双流框架 / 安排儿、小管家、life_butler_ai、Memoh、LifeOS 等同类项目。

### 0.1 所有靠谱方案共收敛到一套"结构化画像"范式

| 范式 | 代表 | 关键结论 |
|---|---|---|
| 固定 schema，不是自由文本 | GetProfile | "Instead of storing raw text blobs, turns user understanding into **typed fields with confidence scores and importance levels**" |
| 每条记忆带 confidence | GetProfile / PersonaTree | PersonaTree 甚至用 log-odds + 时间衰减（记忆会随时间变弱） |
| 画像分"稳定 vs 动态"两类 | PSG-Agent | Stable Attributes（人口学/性格）+ Dynamic Attributes（实时情绪） |
| 结构化 + 摘要双轨 | DuoMem（中文）/ Langmem | "后台：递归摘要构建宏观摘要流（跨会话连贯）+ 结构化提取构建用户画像流（细粒度个性化）" |
| 注入 = 一条 system message | GetProfile / Langmem | "adds a system message with user profile summary, traits, and relevant memories to each prompt" |

### 0.2 任务/提醒侧的通用做法

| 做法 | 来源 | P1 采纳 |
|---|---|---|
| 自然语言 → 三元组（任务 + 时间 + 依赖） | "ChatGPT日程规划黄金法则" | ✅ 解析 prompt 输出三元组 |
| 相对时间 → 绝对时间（喂 `{now}` 给 LLM） | Time-AI / RemindTask | ✅ 解析 prompt 必须喂当前时间 |
| 解析（LLM）和调度（定时扫描）分离 | Task Manager 架构 | ✅ 保持你现有架构 |
| MCP 做提醒 | nudge / reminder-mcp / mcp-todo | ⏸️ P2/P3 再做，P1 不碰 |

### 0.3 你 v1 设计的差距（本次调研暴露）

| 你 v1 写法 | 业界标配 | 该补 |
|---|---|---|
| `memory_key` 开放枚举 | 固定 schema | ✅ 改成 4-6 个固定维度 |
| 没有 confidence | 每条记忆带 confidence 0/1 | ✅ AI 提取=0（挂起），用户确认=1（生效+注入） |
| 没分稳定/动态 | PSG-Agent 的 stable/dynamic 分类 | ✅ 加 `mem_type` 字段（面试亮点） |
| 解析 prompt 缺三元组 | 任务-时间-依赖 | ✅ 输出三元组 |
| 会话摘要 | DuoMem 双流框架 | ⏸️ P1 不做（你"怕步子大"的判断被印证） |

### 0.4 业界共识的节奏（和你"克制 P1"的判断一致）

所有项目都是**先跑通结构化画像，再挂摘要流，再挂 MCP 工具**——和你"P1 只做记忆+任务，摘要/MCP/客服全挂 P2+"完全对齐。**你不用怕步子大，业界节奏也是先小后大。**

---

## 1. 核心定位

### 1.1 一句话
一个**记得住你**的 AI 生活管家。它读你授权的公开信息、用**一句话口语**帮你把事排好、在该提醒的时候主动找你。核心卖点是**省惦记**——你只管说话，它负责记住、排好、到点找上门。

### 1.2 为什么值得做（动机）
互联网时代最贵的不是信息，是**注意力 + 惦记成本**：
- 学生同时挂着游戏签到、考试日期、待办、作业
- 上班族同时挂着工单、排班、家人日程
- "我明明记得明天要做 X，但忙起来就忘了"是所有人的痛点

**存在意义**：把"当人肉日程表"外包给 AI，让用户少费心、早点休息。
**产品差异化**：不是"又一个聊天框"，是**有长期记忆、能听口语、主动提醒**的管家。

### 1.3 三个边界（定死，防翻车）

| 边界 | 内容 | 面试话术 |
|------|------|---------|
| 只读 + 提醒，不托管账号 | MCP 读公开/授权数据，**绝不存明文密码、绝不代登录** | "我优先走官方开放接口，没有就只读公开数据，账号安全完全不沾手" |
| 客服渠道最小权限 | 微信/QQ 只做"提醒 + 轻量查询"，**规划/任务增删改一律回主网站** | "按渠道分级授权，高危操作收回主站，全链路审计" |
| 记忆结构化，不碰向量库 | 长期记忆 = 固定 schema 画像 + 置信度，**不用 embedder/Qdrant** | "用户偏好是有界事实，不是海量非结构化文档，向量检索是杀鸡用牛刀" |

---

## 2. 数据模型（P1，v2 优化版）

### 2.1 `tb_user_memory` — 长期记忆（v2 升级：固定 schema + confidence + stable/dynamic）
```sql
CREATE TABLE IF NOT EXISTS tb_user_memory (
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    user_id       VARCHAR(64) NOT NULL,
    memory_key    ENUM('style','tech','goal','schedule','game','relation') NOT NULL,
    memory_val    VARCHAR(255) NOT NULL,
    mem_type      ENUM('stable','dynamic') NOT NULL DEFAULT 'stable',
    confidence    TINYINT NOT NULL DEFAULT 0,   -- 0=AI 提取挂起, 1=用户已确认生效
    source        ENUM('manual','ai') NOT NULL DEFAULT 'manual',
    create_time   DATETIME NOT NULL,
    update_time   DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_memory (user_id, memory_key, mem_type),
    KEY idx_user_memory_inject (user_id, confidence)
) ENGINE = InnoDB COMMENT = '用户长期记忆（固定 schema + 置信度）';
```

**v2 设计取舍（对照 0.3 表）**：
- `memory_key` 用 ENUM 锁死 6 个维度（P1 做 4 个核心：style/goal/schedule/relation，P2 再加 tech/game）
- `mem_type` 区分 stable/dynamic（**这是面试最能讲的点**，学 PSG-Agent）
- `confidence=1` 才注入 system prompt / 才驱动提醒；`confidence=0` 是"AI 提取挂起"，等用户确认
- `source` 区分 AI 提取 vs 用户手动改（手改的永远 `confidence=1`）
- 唯一键含 `mem_type`（同一个 key 下 stable 和 dynamic 各存一条）

### 2.2 `tb_user_task` — 口语化任务
```sql
CREATE TABLE IF NOT EXISTS tb_user_task (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    user_id       VARCHAR(64)  NOT NULL,
    title         VARCHAR(128) NOT NULL,
    due_at        DATETIME     NOT NULL,
    recurrence    ENUM('once','daily','weekly','monthly') NOT NULL DEFAULT 'once',
    status        TINYINT      NOT NULL DEFAULT 0,    -- 0=待办 1=已完成（软删）
    raw_input     VARCHAR(512) NULL,
    create_time   DATETIME     NOT NULL,
    update_time   DATETIME     NOT NULL,
    PRIMARY KEY (id),
    KEY idx_user_task_due (user_id, status, due_at),
    KEY idx_user_task_user (user_id, update_time)
) ENGINE = InnoDB COMMENT = '用户任务/计划（口语化解析后落库）';
```

**P1 刻意保持单表**（不学"安排儿"拆多张 待办/清单/餐食 表——那是"大而全"，你选"小而全"，P1 克制）。

---

## 3. 记忆提取（v2：固定 schema + 三元组 + 置信度）

### 3.1 触发时机
- 用户说"**记住** / **以后** / **我**在…"等触发词 → 调用 LLM 提取
- 用户通过 API 手动 PUT → 直接写库（`confidence=1`、`source=manual`）

### 3.2 提取 prompt（v2：输出 schema + 置信度）
```
你是"记忆提取器"。从用户原话中识别**长期事实**，以 JSON 数组输出。
每条：{"key":"<style|goal|schedule|relation>","value":"...","mem_type":"stable|dynamic"}

规则：
- key 必须从上面 4 个维度里选，匹配不上就**不要输出**这条（宁可漏记不可乱记）
- mem_type：稳定的（性格/称呼/作息）→ stable；近期会变的（目标/在做什么）→ dynamic
- 任务类的话（"明天交作业"）不是记忆，是任务，**不提为记忆**
- 没有可提取项就返回 []
- 只输出 JSON

用户原话：{user_message}
```

### 3.3 意图二次确认（v2：confidence 数据级）
LLM 提取后**不直接生效**：
1. 写库 `confidence=0`（挂起）
2. 回一句给用户："我从你这句话里识别到 2 条记忆要记：① 风格=简洁 ② 近期目标=备考。确认吗？"
3. 用户回"对" → `confidence` 升 1
4. 用户回"不对" → 删掉挂起那条（或让用户手动改）

**为什么数据级确认**：
- "只有 `confidence=1` 的才注入 / 才驱动提醒" → 二次确认不只是对话里问一句，而是**数据里真有"确认状态"字段**
- 面试讲法："AI 提取是低置信挂起，用户确认才升级为生效记忆"

### 3.4 提取失败兜底
- LLM 返回空数组 → 告诉用户"没识别到长期事实，可以手动改记忆"
- 解析失败 / 超时 → 静默跳过（记忆是增值不阻断主对话）

---

## 4. 记忆注入（v2：一条 system message，学 GetProfile/Langmem）

### 4.1 注入点
`ChatServiceimpl.withHistory()`（现有角色化历史改造那处）：
- 查 `SELECT * FROM tb_user_memory WHERE user_id=? AND confidence=1`
- 拼成**一条 system message**（学 GetProfile："adds a system message with user profile summary, traits, and relevant memories"）

### 4.2 注入形态（v2：分层）
```
【system】（原有 ModelRouter.SYSTEM_PROMPT）
你是一个乐于助人的 AI 助手。…

【用户画像】（新增，一条 system message，confidence=1 的）
风格: 简洁            ← stable
近期目标: 备考        ← dynamic
称呼: 老板            ← stable
作息: 早起            ← stable
```

### 4.3 安全边界
- 画像只读，不改（改走 API 或 LLM 提取 + 确认）
- 每条 `memory_val` 做 HTML 转义 + 长度截断（防注入）
- 只注入前 5 条（stable 优先，dynamic 在后）

### 4.4 缓存（P1 不做）
- 单用户 5-10 条，MySQL 毫秒级，**不走 Redis**
- P2 有"提醒措辞按记忆定制"高频读时再决定

---

## 5. 口语化任务（v2：三元组 + 喂 {now}）

### 5.1 体验
"下周三交国二" / "每天 8 点提醒我敲代码 30 分钟" / "把敲代码改成 9 点"
→ LLM 解析成三元组（任务 + 时间 + 依赖）
→ 回一句确认："我理解你要建一个每周一 9 点的「交国二」任务，确认吗？"
→ 用户回"对" → 落库

### 5.2 解析 prompt（v2：三元组 + 喂 {now}）
```
你是"任务解析器"。把口语化任务描述解析为 JSON 数组。
每条：{"action":"create|update|delete","id":null,
      "title":"...","due_at":"2026-11-05T09:00:00",
      "recurrence":"once|daily|weekly|monthly"}

规则：
- 相对时间（"下周三"、"明天"）基于**当前时间 {now}** 解析为绝对 ISO 时间
- 同一句话提到多个任务就输出多条
- recurrence 缺省 once
- 没有明确意图就返回 []
- 只输出 JSON

当前时间：{now}
用户原话：{user_message}
```

**喂 `{now}` 的原因**（学 Time-AI/RemindTask）：LLM 不擅长算"下周三"是几号，**必须把当前时间喂进去**，否则"明天"可能是错的一天。

### 5.3 二次确认（同记忆一样的 confidence 模式）
任务创建/修改/删除是**写操作**，必须防"AI 理解错就乱建任务"。

### 5.4 API（前端手动建任务兜底，不走 LLM）
`POST /user/tasks`：直接传 `{title, due_at, recurrence}`

---

## 6. 到点提醒（P1 只做"查"，"推"放 P3）
- `GET /user/tasks/due`：查所有"到期未处理"任务（`status=0 AND due_at <= NOW()`）
- 前端轮询 / 定时调用（P1），P3 接微信/QQ 客服做 push 后才真"主动找你"

**P1 不做**：不接微信/QQ、不接 MCP、不做"提醒措辞按记忆定制"（P3）。

---

## 7. 新增接口清单（全部带 @Operation）

### 记忆
```
GET    /user/memory                       查当前用户所有生效记忆（confidence=1）
PUT    /user/memory?memory_key=...         手动改某条（body 带 memory_val，confidence 直接=1）
DELETE /user/memory?memory_key=...&mem_type=...   删某条
POST   /user/memory/confirm?memory_key=...  把挂起记忆升级为生效（confidence 0→1）
```

### 任务
```
GET    /user/tasks                        查所有任务（可按 status 过滤）
POST   /user/tasks                        手动建任务（兜底，不走 LLM）
PUT    /user/tasks/{id}                   改任务（title/due_at/recurrence/status）
DELETE /user/tasks/{id}                   软删（status=1）
GET    /user/tasks/due                    查到期任务（P1 的"提醒"）
```

### 对话里 LLM 路径（不改 /chat/ask 主流程）
- 在 `ChatServiceimpl.sendSession` 里，**关键词检测**（"记住" / "下周三" / "提醒" 等）命中意图
- 命中才调一次"记忆提取器" / "任务解析器"（**复用现有 ModelRouter，零新依赖**）
- 结果以**确认文案**返回（"我理解你要记…"）
- 用户回"对" → 调 `MemoryService.confirm()` / `TaskService.upsert()` 落库
- **不命中就纯聊天，零额外成本**

---

## 8. 文件清单（P1 落地时新建/改动）

### 新增
```
docs/migration/2026-11-02-mindbutler-memory-task.sql
src/main/java/com/aichat/Entity/UserMemory.java
src/main/java/com/aichat/Entity/UserTask.java
src/main/java/com/aichat/Mapper/UserMemoryMapper.java
src/main/java/com/aichat/Mapper/UserTaskMapper.java
src/main/java/com/aichat/Service/MemoryService.java
src/main/java/com/aichat/Service/impl/MemoryServiceimpl.java
src/main/java/com/aichat/Service/TaskService.java
src/main/java/com/aichat/Service/impl/TaskServiceimpl.java
src/main/java/com/aichat/utils/MemoryPrompt.java
src/main/java/com/aichat/utils/TaskParsePrompt.java
src/test/java/com/aichat/Service/impl/MemoryServiceimplTest.java
src/test/java/com/aichat/Service/impl/TaskServiceimplTest.java
```

### 改动
```
ChatServiceimpl.java   // 对话前注入画像 + 意图检测
UserController.java      // 加 /user/memory、/user/tasks 接口
application.properties   // 加 app.intent.detection.enabled 开关
```

### 不动
- ModelRouter（画像注入复用现有 `withHistory`）
- Redis（P1 纯 MySQL）
- 前端（自己补，后端只做 API）

---

## 9. 测试计划

### 单测（纯 Mock）
- `MemoryServiceimplTest`：upsert / get / confirm / delete / list / stable vs dynamic（10+ case）
- `TaskServiceimplTest`：create / update / soft delete / due / recurrence（10+ case）
- `ChatServiceimplTest` 补 1 个：画像注入 system prompt 断言（stable 优先，confidence=1）

### 集成测试（真实 MySQL/Redis/Ollama/DeepSeek）
在 `FullFlowIntegrationTest` 加 3 个 Order：
- **Order 18**：说"记住我喜欢简洁" → 回确认 → 回"对" → `GET /user/memory` 查到 `style=简洁, confidence=1`
- **Order 19**：说"明天 9 点交作业" → 回确认 → 回"对" → `GET /user/tasks/due` 查到该任务
- **Order 20**：`GET /user/memory` 验证 confidence=0 的**不**返回（挂起的不生效）

**P1 通过标准**：单测 + 集成测试全绿，记忆/任务都能"口语化创建 + 确认 + 查得到"。

---

## 10. 路线图（P1 做完才走 P2，防"手痒"）

| Phase | 内容 | 做到什么算完成 |
|-------|------|--------------|
| **P1** | 记忆 + 口语化任务（纯后端 + 固定 schema + confidence + stable/dynamic） | 对话里说"记住…/下周三…" → 确认 → 落库 → 查得到 |
| P1.5 | 会话摘要（DuoMem 双流框架的"摘要流"，非结构化补充） | 每 20 轮 LLM 生成"这次聊了啥"存下来，跨会话喂背景 |
| P2 | MCP 接数据（米游社资源回复量等） | 到点自动拉数据 + 生成提醒 |
| P3 | 微信/QQ 客服（最小权限） | 忘登录/考试临近，收到定制措辞提醒 |
| P4 | 商业化 + 收益模型 | 讲清"谁付钱、为什么付" |

**P1 刻意不做的事**（写下来防"手痒"）：
- 不碰向量库
- 不接 MCP
- 不接客服
- 不做"提醒措辞按记忆定制"（P3）
- 不做"会话摘要"（P1.5）
- 画像维度只做 4 个核心（style/goal/schedule/relation），tech/game 留 P2

---

## 11. 风险与取舍

| 风险 | 取舍 |
|------|------|
| LLM 解析错任务/记忆 | **confidence 数据级确认**（副作用分离）+ 手动 API 兜底 |
| 意图检测漏识别 | 简单关键词表（P1），P2 升级成 LLM 意图分类 |
| 用户被"它什么都记"吓到 | 提取被动（触发词才跑）+ confidence=0 挂起 + 可查可删可改 |
| 画像拼进 system prompt 太长 | 前 5 条 + stable 优先 + 每条 value 截 64 字符 |
| "下周三"算错日期 | **喂 {now} 给 LLM**（Time-AI 经验） |
| dynamic 记忆过期 | P1 不处理（P2 加时间衰减，学 PersonaTree 的 log-odds） |

---

## 12. 面试讲法（v2 版，比 v1 多 2 个亮点）

> "我做了个**有长期记忆**的 AI 管家。用户**口语化**一句话就能把事排好——'下周三交国二'，AI 理解成**三元组（任务 + 时间 + 依赖）**，**先回一句确认**（'我理解你要建个下周三 9 点的交国二任务，对吗？'），用户回'对'才落库。
>
> 长期记忆用**固定 schema + 置信度 + 稳定/动态分类**（学 PSG-Agent）——不是'存个 KV'那种粗糙做法，而是：
> - **6 个固定维度**（style/goal/schedule/relation/tech/game，P1 做 4 个）
> - **每条带 confidence**（AI 提取=0 挂起，用户确认=1 生效，只有 =1 的才注入）
> - **分 stable 和 dynamic**（stable 长存，dynamic 会过期，P2 加时间衰减）
>
> **会话摘要、MCP 数据接入、客服推送**——这些**我刻意不在 P1 做**（学 DuoMem 双流框架的节奏：结构化先行，摘要后补，工具最后挂），**P1 先把'记得住'这一根魂立住**。
>
> 核心卖点是**省惦记**——用户不用费心想'我该做什么'，说说话就行，AI 负责记住、排好、到点找上门。"

---

## 附：P1 实现顺序（给"你"看的 TODO 参考，每步 commit 一次）

1. 迁移脚本（建 2 张表）
2. 2 个 Entity（`UserMemory` / `UserTask`）
3. 2 个 Mapper（MyBatis 注解，跟 `ChatSessionMapper` 风格一致）
4. `MemoryService` + `TaskService`（接口 + 实现，**纯 MySQL 不碰 Redis**）
5. 2 个 prompt 常量类（`MemoryPrompt` / `TaskParsePrompt`，喂 `{now}`）
6. `ChatServiceimpl.withHistory()` 注入画像（一条 system message） + 意图检测
7. `UserController` 加 9 个接口（`/user/memory` × 4、`/user/tasks` × 5）
8. 单测（Memory 10+ / Task 10+ / ChatService 补 1）
9. 集成测试（`FullFlowIntegrationTest` 加 3 个 Order：18/19/20）
10. Swagger `@Operation` 中文说明
11. 跑全量 + 提交

---

## 附：待你拍板（v2 新增的 2 个小决策）

**D1. 画像 6 个维度，P1 做几个？**
- 你 v1 设计里我提的 6 个（style/tech/goal/schedule/game/relation）
- 我建议 **P1 做 4 个核心**（style/goal/schedule/relation），tech/game 留 P2（"小而全"）
- **你定几个？**

**D2. `mem_type`（stable/dynamic）P1 做吗？**
- 这是**面试最能讲的点**（学 PSG-Agent），但多一个字段 + 注入时"stable 优先"的逻辑
- 我建议 **P1 做**（就一个字段 + 注入时 stable 优先），**你定**

**D3. `confidence` 的确认走哪种？**
- (a) 对话里回"对"（P1 轻量）
- (b) 独立接口 `POST /user/memory/confirm`（前端做好后）
- 我建议 **P1 走 (a)，(b) 留前端**

回完这 3 个我就改 v2 文档，你拿去"再找一波资料"时对照用。
