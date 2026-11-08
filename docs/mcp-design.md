# aichat MCP Server 设计（v1，待实现）

> 状态：**设计稿，未实现**。评审通过后再动手。
> 决定：**零新依赖、纯 Java 自实现 stdio MCP Server**（路线 B）。
> 目标读者：我自己 + 面试官（结构要能直接讲）。

---

## 1. 目标与非目标

### 要做
把 aichat 已有的**记忆 / 任务**能力暴露成 MCP 工具，让 Cursor、Claude Desktop 这类 MCP 客户端直接调用，
从而在**不打开本项目前端**的情况下完成：

> 「记住我下周三交作业」 → 建任务；「我这周有啥要交的」 → 查任务；「我之前说过喜欢什么风格」 → 查记忆

### 不做
| 不做 | 原因 |
|---|---|
| 接米游社等第三方数据源 | 无官方 API、可能要逆向、不稳定；5 天换不来面试分 |
| 远程 HTTP/SSE transport | 本地 stdio 足够；远程要 OAuth + 多租户鉴权，成本翻倍 |
| 多用户 | 本地进程绑定单个 `userId`（见 §6 安全模型） |
| 升级 Spring AI 到 1.0.0 用官方 starter | M5 的 BOM 没有 MCP 模块；升 1.0.0 需连 Boot 3.2.5→3.4，278 个测试要全量回归，风险 > 收益 |
| resources / prompts 能力 | 先只做 tools；`resources` 是只读数据暴露，收益低于风险 |

### 为什么自己实现（面试要说清）
1. MCP 协议层很薄：JSON-RPC 2.0 + 三个方法（`initialize` / `tools/list` / `tools/call`），约 300 行。
2. 官方 starter 要捆绑 Spring Boot 大版本升级，属于"为了一个已能自写的东西引入全局风险"。
3. 自己写才能讲清协议细节（版本协商、错误码、framing），这比"我加了一行 starter"值钱。

---

## 2. 协议要点（实现依据）

| 项 | 约定 |
|---|---|
| 消息格式 | JSON-RPC 2.0，**每行一条**（newline-delimited JSON），UTF-8，不带 `\n` 转义问题用 Jackson 的 writeValueAsString |
| 传输 | stdin 读请求、stdout 写响应。**stdout 只能出协议消息**，日志一律走 stderr |
| 版本协商 | `initialize` 时取客户端 `protocolVersion`：支持 `2025-03-26` / `2024-11-05`，不匹配则回本端最高支持版本（客户端自行决定是否继续） |
| 握手顺序 | `initialize`(请求) → `notifications/initialized`(通知，无响应) → `tools/list` / `tools/call` |
| 能力声明 | `capabilities: { tools: { listChanged: false } }`；不做动态增删工具，简化实现 |
| 工具失败 | 用 `result.isError = true` + 文本说明，**不要**用 JSON-RPC error（协议错误与业务失败必须分开） |
| 协议错误码 | 解析失败 `-32700`、方法不存在 `-32601`、参数非法 `-32602`、内部错误 `-32603` |
| 并发 | 单读线程 + 固定线程池处理请求（默认 4），响应带 `id` 回写；写 stdout 加锁 |
| 取消 | 收到 `notifications/cancelled` 时置标志位，长查询尽快返回（第一版可只记录不强制中断） |
| 退出 | stdin EOF（客户端关闭）→ 冲刷 stdout → `System.exit(0)` |

### 关键报文形状

```jsonc
// 请求
{"jsonrpc":"2.0","id":1,"method":"initialize","params":{
  "protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"Cursor","version":"0.x"}}}

// 响应
{"jsonrpc":"2.0","id":1,"result":{
  "protocolVersion":"2025-03-26",
  "capabilities":{"tools":{"listChanged":false}},
  "serverInfo":{"name":"aichat-mcp","version":"1.0.0"},
  "instructions":"aichat 的记忆与任务工具；create_task 会直接落库，请先与用户确认时间。"}}

// tools/list
{"jsonrpc":"2.0","id":2,"result":{"tools":[
  {"name":"search_memories","description":"...","inputSchema":{"type":"object","properties":{...},"required":["query"]}}]}}

// tools/call 成功
{"jsonrpc":"2.0","id":3,"result":{"content":[{"type":"text","text":"找到 2 条记忆：..."}],"isError":false}}

// tools/call 业务失败
{"jsonrpc":"2.0","id":4,"result":{"content":[{"type":"text","text":"参数 query 不能为空"}],"isError":true}}
```

---

## 3. 工具设计（3 个）

> 原则：**工具描述是写给模型看的**——决定它调不调、参数填不填对；schema 用 enum / 上下界约束，不要给自由文本。

### 3.1 `search_memories`（只读）

```json
{
  "name": "search_memories",
  "description": "按关键词检索该用户已确认的长期记忆（风格偏好、目标、日程、关系）。只读。用于回答『我之前说过什么』这类问题。",
  "inputSchema": {
    "type": "object",
    "properties": {
      "query":  {"type": "string", "description": "检索关键词，2-30 字", "minLength": 1, "maxLength": 30},
      "type":   {"type": "string", "enum": ["style", "fact", "schedule", "relation"], "description": "可选，按维度过滤"},
      "limit":  {"type": "integer", "minimum": 1, "maximum": 5, "default": 3}
    },
    "required": ["query"],
    "additionalProperties": false
  }
}
```

- 实现：`MemoryService` 查 → 按 `mem_type`（stable 优先）排序 → 每条截断 → 总字符预算 1200（复用注入时的预算逻辑）。
- 返回：`N 条记忆：1) [style/stable] … 2) …`

### 3.2 `create_task`（写）

```json
{
  "name": "create_task",
  "description": "为用户创建一条待办任务。调用前必须已从用户话里确定任务内容和时间；时间不明确就先问用户，不要猜。同一句话重试时请复用同一个 idempotencyKey。",
  "inputSchema": {
    "type": "object",
    "properties": {
      "text":           {"type": "string", "minLength": 1, "maxLength": 200, "description": "任务内容原文"},
      "dueAt":          {"type": "string", "description": "ISO-8601 本地时间，如 2026-11-11T09:00:00；无时间则省略"},
      "recurrence":     {"type": "string", "enum": ["once", "daily", "weekly"], "default": "once"},
      "recurrenceRule": {"type": "string", "description": "weekly 时必填，如 周三"},
      "idempotencyKey": {"type": "string", "maxLength": 64, "description": "幂等键，建议用会话 id + 时间戳"}
    },
    "required": ["text", "idempotencyKey"],
    "additionalProperties": false
  }
}
```

- 幂等：`idempotencyKey` 落 Redis（`mcp:idem:{userId}:{key}` → taskId，TTL 24h）；重复调用直接回原 taskId，不重复建。
- 时间解析：`dueAt` 走既有的时间解析（与意图提取共用），解析失败 → `isError:true` + 提示格式。
- **风险分级**：创建属"可撤销的写"，**不**走 `pending_action` 二次确认（否则每次 demo 都要切前端，体验断裂）；但必写审计日志。
  - 后续加 `delete_task` / `update_memory` 时必须走 `PendingActionService` 确认（已有状态机，正好复用）。

### 3.3 `list_due_tasks`（只读）

```json
{
  "name": "list_due_tasks",
  "description": "列出未来 N 小时内到期的任务，用于回答『我这周有啥要交的』。只读。",
  "inputSchema": {
    "type": "object",
    "properties": {"withinHours": {"type": "integer", "minimum": 1, "maximum": 72, "default": 24}},
    "additionalProperties": false
  }
}
```

- 返回：`3 条：1) 2026-11-11 09:00 交作业（once）2) …`，最多 20 条，超出提示"还有 N 条"。

---

## 4. 架构与代码落点

```
src/main/java/com/aichat/mcp/
├── McpApplication.java        # 独立入口，--mcp 启用；Spring 上下文 none-web，日志转 stderr
├── StdioTransport.java        # 读 stdin 行 / 写 stdout 行（加锁）、EOF 处理
├── JsonRpcCodec.java          # 请求/响应/错误对象 + 解析（Jackson）
├── McpServer.java             # 握手状态机、方法路由、版本协商、线程池
├── ToolRegistry.java          # name → Tool 映射，导出 tools/list
├── Tool.java                  # interface: name(), description(), inputSchema(), call(JsonNode args, ToolContext)
├── ToolContext.java           # userId、requestId、审计句柄
├── AuditLog.java              # 每次调用一行 JSON 到 logs/mcp-audit.jsonl
└── tools/
    ├── SearchMemoriesTool.java
    ├── CreateTaskTool.java
    └── ListDueTasksTool.java
```

**复用而非重写**：三个工具内部只调 `MemoryService` / `TaskService`，不碰 Mapper，保持四层架构不被打破（见 [`啃代码路线.md`](啃代码路线.md) 附录「包职责边界」）。

**启动方式**（stdout 只出协议，日志必须让路）：

```bash
mvn -q -DskipTests package
java -jar target/aichat-0.0.1-SNAPSHOT.jar \
  --spring.profiles.active=mcp --mcp \
  --logging.file.name=logs/mcp.log --spring.main.banner-mode=off
```

配置项（`application-mcp.properties`）：

| key | 默认 | 说明 |
|---|---|---|
| `aichat.mcp.user-id` | 无（必填） | 本进程绑定哪个用户，缺失直接启动失败 |
| `aichat.mcp.threads` | 4 | 处理线程数 |
| `aichat.mcp.memory-char-budget` | 1200 | 记忆返回字符预算 |
| `aichat.mcp.audit-file` | `logs/mcp-audit.jsonl` | 审计落盘 |

客户端配置（Cursor `~/.cursor/mcp.json`）：

```json
{"mcpServers":{"aichat":{"command":"java","args":["-jar","D:\\maven-project\\aichat\\target\\aichat-0.0.1-SNAPSHOT.jar","--spring.profiles.active=mcp","--mcp"]}}}
```

---

## 5. 安全模型（面试重点）

| 风险 | 处置 |
|---|---|
| **多租户越权** | 不做远程，进程启动时用 `aichat.mcp.user-id` 绑定唯一用户；所有查询强制带该 `userId`，不接受来自模型的用户标识 |
| **危险操作** | 第一版只有"创建"，可撤销；删除/改记忆类工具**必须**走 `pending_action` 二次确认后才能落库 |
| **重放 / 重复调用** | `idempotencyKey`，Redis 24h |
| **上下文爆炸** | 返回结果硬上限（记忆 1200 字、任务 20 条），超限截断并提示 |
| **工具返回内容不可信** | 工具输出统一走 `type: text` 且不拼接进 system prompt；若将来做 MCP **client**，外部 server 的返回一律视为不可信输入（prompt injection 面） |
| **审计** | 每次 `tools/call` 记一行：时间、requestId、工具名、参数摘要（去敏）、耗时、是否错误 |
| **密钥** | MCP 进程不再单独存密钥，复用后端已有配置；日志脱敏规则与后端一致 |

---

## 6. 测试计划

| 层 | 用例 |
|---|---|
| 协议层（纯单测） | 坏 JSON → `-32700`；未知方法 → `-32601`；缺必填参数 → `-32602`；`initialize` 版本协商（支持 / 不支持两种回法）；未知 `id` 不回包 |
| 工具层 | `search_memories`：空 query、超长 query、limit 越界夹紧、无结果措辞；`create_task`：同 key 二次调用只建一条、`dueAt` 非法格式、weekly 缺 rule；`list_due_tasks`：上界 72h、0 条措辞 |
| 端到端 | `McpE2ETest` 用 `ProcessBuilder` 起 jar，喂 4 行 JSON，断言 4 行响应 |
| 手工验收 | Cursor 连上，跑一遍 §8 演示脚本 |

**验收标准（DoD）**：上述全绿 + `tools/list` 在 Cursor 里能看到 3 个工具 + 演示脚本 3 分钟内跑完。

---

## 7. 里程碑（4 个 commit）

| # | 内容 | 预估 |
|---|---|---|
| 1 | 协议骨架：`StdioTransport` + `JsonRpcCodec` + `McpServer`（initialize / ping / tools/list 空表）+ 协议层单测 | 0.8d |
| 2 | `search_memories` + `ToolRegistry` + 审计日志 + 工具层单测 | 0.5d |
| 3 | `create_task`（幂等）+ `list_due_tasks` + 单测 | 0.7d |
| 4 | Cursor 实测 + `docs/mcp-design.md` 定稿 + 3 分钟录屏 | 0.5d |

---

## 8. 演示脚本（3 分钟）

1. 终端起服务（日志落到 `logs/mcp.log`，终端本身不打印日志）。
2. Cursor 设置里确认 `aichat` MCP 已连接，工具列表出现 3 个。
3. 对话：“记住我下周三上午九点交作业” → 工具调用 `create_task` → 回“建好了，11 月 11 日 09:00”。
4. 对话：“我这周还有啥要交的” → `list_due_tasks` → 列出刚建的那条。
5. 对话：“我之前说过喜欢什么风格” → `search_memories` → 返回记忆。
6. 切到终端 `tail logs/mcp-audit.jsonl` → 三行审计，证明“谁在什么时候调了什么”。

---

## 9. 面试讲法（背这 6 条）

1. **为什么自实现**：M5 的 BOM 没有 MCP 模块；升 1.0.0 要连 Boot 3.4 一起升，为 300 行协议引入全局回归风险不划算。
2. **协议三层**：握手（版本协商）→ 能力声明（tools）→ 调用（tools/call），失败用 `isError` 而不是协议错误。
3. **工具描述即提示词**：描述写清"什么时候调、什么情况先问用户"，schema 用 enum 收窄，模型才不会乱填。
4. **幂等**：client 会重试，`idempotencyKey` + Redis 24h 保证不重复建任务。
5. **风险分级**：读工具直接执行，写工具留审计，破坏性工具必须二次确认（复用 `pending_action` 状态机）。
6. **单向信任**：工具返回内容是不可信输入；调用第三方 server 时要隔离、白名单、裁剪。

---

## 10. 风险与遗留

| 风险 | 说明 | 应对 |
|---|---|---|
| stdout 被日志污染 | Spring Boot 默认把日志写 stdout，会直接破坏协议 | 强制 `logging.file.name` + 启动参数校验（首字节必须是 `{`，否则自检失败退出） |
| Cursor/Claude Desktop 版本行为差异 | 客户端对 `instructions`、`isError` 处理不一致 | 端到端至少测两个客户端；只依赖协议必需字段 |
| 会话无上下文 | MCP 客户端每轮独立，模型不知道"刚才那条任务" | 工具返回里带 taskId，后续工具可引用 |
| 未来要远程 | 需要 OAuth、多租户、限流 | 不在本期；届时 transport 抽象成接口，stdio/HTTP 两实现 |

---

## 11. 待评审的开放问题

1. `create_task` 是否要默认走 `pending_action` 确认？（当前决定：不走，理由是 demo 体验；若面试更看重安全叙事，可改成"参数带 `requireConfirmation:true` 时走确认"）
2. 要不要做第 4 个工具 `get_recent_chat_summary`？（依赖会话摘要，尚未实现，暂不做）
