# 记忆变更：聊天里改 / 删

> 用户说"把目标改成明年考公"、"把作息的记忆删掉"，系统识别出来、给出预览、确认之后才真正改库。
> 代码：`utils/MemoryChangeParser.java`、`Dto/MemoryChange.java`、`Service/impl/IntentServiceImpl.java#update`、
> `Service/impl/PendingActionServiceImpl.java#applyChanges`。

## 为什么用规则解析，不让模型解析

| 方案 | 好处 | 问题 |
|---|---|---|
| 让 LLM 输出"要改哪条记忆" | 表达再绕也能理解 | 会**猜**：猜错就是把用户的记忆改没了；同一条输入两次结果可能不同，没法写稳定测试 |
| 规则解析（本方案） | 可测、可解释、不花钱、不联网 | 覆盖有限，说话方式太绕就识别不出来 |

决定取规则，并把"识别不出来"设计成**追问**而不是**猜**：

- 说"把目标删掉"、库里 goal 有两条 → 回「我找到 2 条可能的记忆：「今年考研」、「明年上岸」。你想改哪一条？」
- 没提到任何记忆类别、也没提到内容 → 回「没找到要改的记忆……」并给例子
- 改完内容和原来一样 → 回「改完和现在一样，我就先不动了。」

删除是不可逆的，宁可多问一句。

## 流程

```
用户消息
  └─ 意图层判断为 UPDATE（"改成/改为/删掉/忘掉…"）
      ├─ 敏感信息？ → 直接拒绝，不进 pending
      ├─ 说的是任务（任务/提醒/待办/延后/推迟/提前/改到…）且没点名记忆类别？
      │     → 回"任务的时间、重复规则请到任务列表改"（本轮不做任务改删）
      └─ 走 MemoryChangeParser
            ├─ 没解析出 → 直接把追问文案回给用户（不建 pending）
            └─ 解析出 → 建 memory_change pending
                  payload: { changes: [{id, action, before, after}], preview, rawInput }
                  聊天里只回一行标记 [[MB:pending=<id>]]，前端拿它拉弹窗
                        └─ 用户确认 → applyChanges 逐条执行 → 乐观锁 version 校验 → 回执
```

**预览文案**（弹窗里给人看的那句）由后端生成，例如：

- 改：「要把这条记忆改成：「明年上岸」。原来是：目标 · 后年上岸。」
- 删：「要删掉这条记忆：目标 · 明年上岸。删掉后我不会再拿它回答你。」

确认后的回执也是后端给的，避免前端硬编码：

- 改：「「后年上岸」改成「明年上岸」」
- 删：「已删掉「明年上岸」」
- 目标已经不在了：「这几条记忆已经不在了，没有改动。」

## 选条规则（`MemoryChangeParser.parse`）

1. **按类别缩范围**：`style←风格/偏好/喜好/语气/回答方式`、`goal←目标/计划/打算`、
   `schedule←作息/日程/时间安排/提醒时间`、`relation←关系/称呼/昵称`。
2. 类别命中且该类别只有 1 条 → 直接就是它。
3. 否则按**内容重合度**打分（≥2 个不同的字才算一次命中），只有唯一最高分才算命中。
4. 其余情况一律追问。

> 打分前会先剔掉"记忆/目标/风格/这条/那条"等**类别词**（`SCORE_STOPWORDS`）。
> 不剔的话，"把目标的记忆删掉"里的"记忆"二字会和「验收-隔离记忆」撞上 2 个字，
> 于是两条 goal 记忆的情况下被误判成唯一命中——这是本轮实测踩到的坑。

## 数据与兼容

- `tb_pending_action.action_type` 从 `ENUM('memory','task','mixed')` 改成 `VARCHAR(32)`，
  否则写 `memory_change` 会被 MySQL 截断（`Data truncated for column 'action_type'`）。
  迁移：`docs/migration/2026-11-08-pending-action-type-varchar.sql`。
- 旧 pending 的 payload 没有 `changes` 字段，`parseChanges` 返回空列表 → 确认时按"已经不在了"处理，
  不会报错。

## 验证

```powershell
# 全量单测（解析 10 个 + 服务层 4 个 + 控制器 1 个）
.\mvnw.cmd -o test

# 端到端（后端需在 8080、SPRING_PROFILES_ACTIVE=local）
python D:\tools\aichat-accept\e2e-change2.py
```

端到端覆盖：新建记忆 → 改名（预览带 before/after）→ 类别有两条时追问 → 指定内容删除 → 任务类请求被引导到任务列表。

## 已知局限

- 只认上面那张触发词表，"帮我把那条goal调成明年"这类中英混杂的会走追问。
- 一次只处理**一类**变更（多条同类可以，跨类别不行）。
- 任务的时间/重复规则改动没做，只引导到任务列表。
- 变更历史没有单独记录表，靠 `tb_user_memory.version` 和日志回溯。
