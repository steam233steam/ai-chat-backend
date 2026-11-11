# 意图层：Tool Calling 引擎

一句话：模型不再「按模板吐一段 JSON」，而是**自己决定要不要调用 `create_task` / `remember` 工具**，
把要建的任务、要记的事实作为工具参数递进来。新增 MCP 服务器后不需要改任何 Java 代码。

## 1. 数据流

```
用户消息
  → IntentDetector（轻量关键词路由：这是确认/取消/改记忆/新请求）
  → IntentToolEngine.extract(intent, message)
        ├─ 组装 system prompt + 工具集（create_task、remember）
        ├─ 模型可连续调用多次工具（上限 app.mindbutler.intent.tool-max-steps，默认 8）
        └─ IntentToolCatalog 把每次调用还原成 MemoryCandidate / TaskCandidate
  → IntentCandidateSanitizer（截断、去重、敏感词、工具名格式）
  → PendingActionService（pending 状态机 + 确认后落库）
```

模型一次都不调工具 = 这句是闲聊，直接走聊天（不报错、不提示）。

## 2. 两个内置工具

| 工具 | 参数 | 说明 |
|---|---|---|
| `create_task` | `title`、`dueAt`、`recurrence`、`recurrenceRule`、`action`、`tool`、`toolArgs` | 有时间的安排一律走这里；`action=mcp` + `tool=weather` 表示到点去查数据 |
| `remember` | `key`（style/fact/relation）、`value`、`memType`、`confidence` | 只记陈述性事实与偏好，不再弹确认，静默入库 |

`dueAt` 接受 ISO（`2026-11-11T09:00:00`）与 `yyyy-MM-dd HH:mm`；`recurrenceRule` 支持 `BYDAY=MO`。

## 3. 加一个 MCP 服务器（不改代码）

1. 在 `application.properties` / `application-local.properties` 里加 `app.mcp.servers.<name>.*`；
2. 重启后端，`GET /mcp/tools` 能看到新工具；
3. 用户说「让 AI 用 xx 查一下」，模型会把 `tool` 填成该工具名；`PendingActionServiceImpl.resolveMcpToolName`
   会按「公共名 → 原始名 → 后缀」三种规则映射到真实的 `mcp_<server>_<tool>`。

工具名只做格式校验（`[a-z0-9_-]{1,40}`），不再有写死的白名单。

## 4. 配置

| 键 | 默认 | 说明 |
|---|---|---|
| `app.mindbutler.intent.tool-max-steps` | 8 | 一次抽取最多允许连续调几次工具（一句话多件事会被放宽） |
| `app.mindbutler.intent.timeout-ms` | 8000 | 整次抽取的超时，超时走兜底文案 |
| `app.mindbutler.intent.daily-limit` | 200 | 每人每天意图识别次数 |

## 5. 怎么验证

- 单测：`IntentToolCatalogTest`（工具 schema 必须是合法 JSON——曾经因为 schema 里未转义引号导致命中率掉到 25%）、`IntentServiceImplTest`（用 mock 的引擎驱动全流程）。
- 真模型评测：`IntentToolEngineEvalTest`，设 `app.mindbutler.eval.real-model=true` 且配 key 才跑，
  40 条口语的门槛是 ≥85%（当前 35/40）。
- 线上：`journalctl -u aichat | grep 意图工具引擎` 能看到 `工具调用=[create_task]`。

## 6. 已知局限

- `IntentDetector` 仍是关键词路由（确认/取消/改记忆）。这是廉价且确定的部分，暂不交给模型。
- `记住我喜欢简洁一点` 这类带「一点」的口语两个引擎都没命中（评测 #01），属于模型判定边界问题。
- 动作链（查完再发）还没做，属于 S4。

## 口语容错：为什么「识别不了」变少了

| 之前 | 现在 |
|---|---|
| `create_task` 的 `dueAt` 必填，模型拿不到时间就干脆不调工具，整件事被丢掉 | `dueAt` 可选：没有时间就建「待办」，进任务页的「不限时间」分组 |
| 提示词写「拿不准就不调工具」 | 改成「能确定多少填多少，其余留空」——宁可用确认弹窗兜住，也不要静默丢弃 |
| 问句（现在几点了 / 明天天气怎么样 / 帮我翻译这句）会被做成定时任务 | 问句与「现在就要答案」的请求在调模型之前就放行给聊天，不起任务、也省一次调用 |
| 模型编造工具名（`news`、`web`），任务到点报「工具不存在」 | 工具别名先就近映射（news→search、time→now），映射不上再降级为站内提醒 |

体检工具：`IntentProbeTest`（`src/test/java/com/aichat/Service/intent/IntentProbeTest.java`）把 26 句真实口吻的句子
逐条打印成「抽到了什么」，跑一次就能看出是「没调工具」还是「调了但字段空」：

```bash
set APP_MINDBUTLER_EVAL_REAL_MODEL=true
.\mvnw.cmd -o test -Dtest=IntentProbeTest
```

实测（26 句）：建任务 18 句全部正确、记记忆 4 句、问句与闲聊 4 句正确放行，误建任务 0 条。
