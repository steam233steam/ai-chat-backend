# 意图层改造：从「提示词枚举」到「Tool Calling」

> 状态：待执行 ｜ 创建 2026-11-10 ｜ 目标版本：P1.6

## 1. 为什么要改

现在「一句话变成任务/记忆」的链路是：

```
关键词判断(IntentDetector) → 拼死提示词(IntentPrompts) → 模型吐 JSON
→ 解析(IntentJsonParser) → 白名单过滤(IntentCandidateSanitizer)
→ 映射成 PendingAction → 确认 → 落库
```

问题：

- 能理解什么**由提示词里的枚举决定**（`style|fact|relation`、`weather|search|now|fetch_url`）。
- 新增能力要改两处代码：提示词枚举 + 白名单常量；否则模型就算说了也会被白名单过滤掉。
- 模型只能在你划的档位里挑，绕一点的说法掉兜底。

而聊天那一侧**已经在用 tool calling**（「现在几点」会真的调 `mcp_info_now`），所以框架本身没问题，是意图侧没接上同一套机制。

## 2. 改造后的模型

```
一句话 → LLM(工具集=内置动作 + MCP 动态工具) → 模型返回工具调用
→ 执行工具(创建任务/改任务/删任务/记偏好/查记忆) → 产出 PendingAction
→ 用户在卡片上确认 → 落库
```

关键点：

1. **工具清单运行时生成**：内置 5 个动作 + `McpToolCatalog` 现查的 MCP 工具。新装 MCP 自动可见，**不改代码、不改提示词**。
2. **模型直接给结构化参数**（`create_task({title, dueAt, recurrence, action})`），不再吐自由 JSON 让正则去解析。
3. **待确认机制保留**：工具执行只产出「计划」，不直接落库，仍走 pending → confirm。

## 3. 文件清单

### 新增

| 文件 | 作用 | 预估 |
|---|---|---|
| `Service/intent/IntentToolActions.java` | 5 个 `@Tool` 方法：`create_task` / `update_task` / `cancel_task` / `remember` / `recall`，内部调 `TaskService` / `MemoryService`，只产出 plan 不落库 | ~220 行 |
| `Service/intent/IntentToolCatalog.java` | 组装工具列表：内置 5 个 + MCP catalog 动态工具（复用 `McpToolCatalog.callbacksFor`） | ~120 行 |
| `Service/intent/IntentToolEngine.java` | 引擎：拼系统提示（只讲角色与约束，不讲枚举）+ 发请求 + 收集工具调用 + 转 `IntentOutcome` | ~260 行 |
| `Service/intent/ToolPlan.java` | 一次调用链的中间态：`List<ToolStep>`，用于序列化成 pending payload | ~90 行 |
| `docs/intent-tool-calling.md` | 设计与对比数据 | — |

### 改写

| 文件 | 改什么 |
|---|---|
| `Service/impl/IntentServiceImpl.java` | `handle()` 顶部按 `app.intent.engine` 选引擎；删 `extractLoose`/`extract`/`update` 里的提示词分支（约 180 行）；保留 confirm / reject / query / tryResolveClarify |
| `Config/MindButlerProperties.java` | 加 `intentEngine`（`prompt`/`tools`）、`intentToolMaxSteps`（默认 3） |
| `application.properties` | 加 `app.mindbutler.intent.engine=${APP_INTENT_ENGINE:tools}` |
| `MindButlerEvalRunnerTest` | 支持双引擎跑同一批用例，输出对比表 |
| `IntentServiceImplTest`（491 行） | 按新引擎重写主路径用例；保留 confirm/reject/clarify 用例 |

### 删除（切换完成后）

| 文件 | 行数 | 理由 |
|---|---|---|
| `utils/IntentPrompts.java` | 66 行 | 枚举来源，全部作废 |
| `utils/IntentJsonParser.java` | 156 | 不再解析自由 JSON |
| `Service/support/IntentCandidateSanitizer.java` | 182 | 白名单过滤，正是「装了 MCP 却执行不了」的元凶 |
| `Service/impl/IntentExtractorImpl.java` + `Service/IntentExtractor.java` | 106 + 18 | 旧的抽取入口 |
| `Dto/IntentExtractionResult.java` | 27 | 随之作废 |
| `utils/IntentDetector.java` | 202 | 仅保留 CONFIRM/REJECT 的判断（拆出 ~40 行到 `ConfirmRejectDetector`），其余删 |

**保留不动**：`PendingActionService(Impl)` 的建单/确认/落库、`MbMarkers`、`MemoryChangeParser`（改删记忆的澄清承接）、`IntentDailyLimiter`、`SensitiveContentFilter`、`IntentQueryReplyBuilder`、任务调度与动作。

### 数据库

无表结构变化。pending 的 `payload` 仍是 JSON，只是从「模型吐的 JSON」变成「工具调用的参数序列化」，格式变更需要兼容读旧的 3 种 actionType（memory/task/mixed/memory_change）。

## 4. 分阶段（每阶段可独立验证、可回滚）

| 阶段 | 内容 | 产出 | 预估 |
|---|---|---|---|
| S0 ✅ 2026-11-10 | 打 tag `intent-refactor-base`；影子通道：新引擎实现但默认不启用，只在评测里跑 | **已完成**：40 条评测，旧 35/40（87.5%）、新 35/40（87.5%），逐条差异见 `target/surefire-reports` | 0.5 天 |
| S1 | 工具集 + 引擎落地，`app.intent.engine=tools` 可在本地切 | 本地 40 条评测跑通 | 1 天 |
| S2 | 灰度切换默认引擎，保留 `prompt` 兜底开关 1 个版本 | 线上验证 | 0.5 天 |
| S3 | 删死代码 + 测试重写 + 文档 | 全量测试绿 | 0.5 天 |
| S4 | 动作链（原 P1）：`create_task` 的 action 支持数组，`search` 自动读正文 | 「查完再发」可用 | 1 天 |

## 5. 风险与兜底

| 风险 | 兜底 |
|---|---|
| 模型不调工具，直接闲聊 | 已存在：没有工具调用 → 走普通聊天；只有命中任务/记忆意图才提示 |
| 模型乱调工具（一句话建 5 个任务） | `intentToolMaxSteps=3` 上限 + 单次最多 1 个 create_task |
| 延迟变高 | 测量：抽取本身 +0.3~0.8s；超时沿用 `intent.timeout-ms`，超时走兜底文案 |
| 费用上升 | 工具 schema 会占 token；通过 `intentToolMaxSteps` 与精简 description 控制 |
| 旧数据不兼容 | payload 解析保留旧分支，读到旧格式仍能确认 |

## 6. 验收标准

1. `MindButlerEvalRunnerTest` 的 40 条用例，新引擎准确率 **≥** 旧引擎（逐条对比，记录退化项）
2. 新增 3 条「未改代码即可识别」用例：临时挂一个假 MCP 服务器（工具名 `mcp_fake_ping`），说「让 AI 用 ping 查一下」→ 能建出任务
3. 全量测试绿（≥358，8 个集成测试除外）
4. 线上：一句话建任务、改记忆、取消任务三条主链路手动验证
5. 删除的类不再被引用（`grep` 为 0）

## 7. 回滚

- 代码：`git reset --hard <tag>`，或把 `app.mindbutler.intent.engine` 改回 `prompt`（S3 之前有效）
- 数据：payload 兼容旧格式，无需回滚
