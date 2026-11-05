# 任务能力边界与投递通道（P1.5b）

> 背景：用户说“提醒我打游戏，然后定时定点在 QQ 提醒”，当前 P1 没有 QQ 通道。
> 如果直接建一条任务，用户会以为到点真的会收到 QQ 消息，但实际什么都不会发生。
> 这类“假成功”比明确说“做不到”更伤信任。

## 1. 结论

P1 的任务能力只有两件事：

- 把任务记录到 `tb_user_task`，在聊天侧边栏/任务列表展示；
- 提供到期查询（`GET /user/tasks/due`），为应用内提醒/外部推送做数据基础。

2026-11-05 已补上**页面打开时的应用内到期提醒**：前端每 30 秒轮询 `/user/tasks/due`，
只弹 10 分钟内到期的任务，按 `taskId + dueAt` 去重，点击通知切到任务 tab。
它不依赖 MCP，也不是后台推送；页面关闭/后台时不会提醒。

P1 **不做**：

- QQ / 微信 / 短信 / 邮件 / 电话 / 钉钉 / 飞书 / Telegram / WhatsApp 的主动推送；
- 替用户下单、支付、转账、订票、打车、点外卖、代发消息等真实世界操作；
- 后台定时任务 + 消息投递 + 失败重试（这些是 P1.6 的投递层）。

## 2. 代码落点

`TaskCapabilityGuard`（`src/main/java/com/aichat/utils/TaskCapabilityGuard.java`）
在调用 LLM 提取之前做确定性拦截：

| 输入 | 行为 |
|---|---|
| `明天 8 点在 QQ 提醒我打游戏` | 拒绝，说明没有 QQ 通道，引导改成“明天 8 点提醒我打游戏” |
| `用微信提醒我开会` | 拒绝，说明没有微信通道 |
| `提醒我查邮件 / 回邮件 / 打电话给妈妈 / 付款` | 允许，这些是“提醒用户去做”，不是“AI 代做” |
| `帮我下单 / 替我转账 / 帮我买咖啡` | 拒绝，说明不能代执行真实世界操作 |

拦截点是 `IntentServiceimpl.extract`（TASK 意图）和 `extractLoose`（LLM 兜底）之前，
所以不会调用提取模型、不会写 pending、不会产生一条看起来会提醒的假任务。

前端同时在两个位置把边界说清楚：

- 任务确认弹窗：`当前只会保存到任务列表，不会主动发 QQ / 微信消息。`
- 侧边栏任务底部：`目前只做应用内记录，不会主动发 QQ / 微信。`

## 3. 为什么不能只靠 prompt

模型很擅长顺着用户说“好的，已帮你设置 QQ 提醒”，但系统并没有这个能力。
任务状态必须由后端能力注册表决定，不能由模型承诺：

- prompt 只负责“理解用户要什么”；
- 能力判断必须是代码里的确定性规则/配置；
- 确认落库前再校验一次通道是否真的可用。

## 4. 当前能力矩阵

| 能力 | 状态 | 说明 |
|---|---|---|
| 任务记录 / 列表 / 完成 / 删除 | ✅ P1 | `tb_user_task` + `/user/tasks` |
| 页面打开时的应用内到期提醒 | ✅ P1.5b | 前端 30s 轮询 `/user/tasks/due` + Notification |
| 后台定时推送 | ❌ P1.6 | 需要调度器 + outbox + 通道 adapter |
| QQ / 微信 / 短信 / 邮件 / 电话 | ❌ P1.6 | 需要绑定、授权、平台频控 |
| 代下单 / 支付 / 转账 / 订票 / 打车 | ❌ 不做（当前） | 涉及真实世界操作和资金安全 |

## 5. P1.6 接入外部通道时的设计

### 5.1 数据模型

```sql
ALTER TABLE tb_user_task
  ADD COLUMN delivery_channel VARCHAR(24) NOT NULL DEFAULT 'in_app'
    COMMENT 'in_app|qq|wechat|sms|email|...',
  ADD COLUMN capability_status VARCHAR(16) NOT NULL DEFAULT 'ready'
    COMMENT 'ready|unsupported|pending_binding',
  ADD COLUMN requested_channel VARCHAR(24) NULL
    COMMENT '用户原始请求的通道，用于降级提示/审计';
```

### 5.2 投递层

```text
TaskScheduler (扫描 due_at <= now AND status=pending)
  -> TaskDeliveryOutbox (唯一键 task_id + channel + scheduled_at，幂等)
  -> ChannelAdapter (QQ/WeChat/SMS/Email/...)
  -> 成功: last_notified_at + outbox=sent
  -> 失败: retry_count + next_retry_at + 告警
```

必须有的能力：

- 用户绑定：QQ/微信需要 OAuth/机器人好友关系，不能只凭一个号码发消息；
- 用户授权与退订：消息通道涉及隐私和平台规则；
- 幂等：同一任务同一通道只发一次，重试不重复；
- 限流：平台频控 + 用户级频控；
- 审计：谁在什么时候通过哪个通道收到了什么；
- 降级：通道不可用时明确告诉用户“只记了应用内任务”，不能静默成功。

### 5.3 API 演进

```text
GET  /user/capabilities                 -> 当前账号可用的投递通道
POST /user/channel-bindings/qq          -> 绑定 QQ（授权码/机器人会话）
GET  /user/tasks/{id}/deliveries        -> 投递记录
POST /user/tasks/{id}/test-delivery     -> 测试通道
```

`TaskVO` 增加：

```json
{
  "deliveryChannel": "in_app",
  "requestedChannel": "qq",
  "capabilityStatus": "unsupported",
  "capabilityHint": "QQ 通道未绑定"
}
```

## 6. 产品交互原则

1. **先判断能不能做，再决定要不要创建**：不能做的请求不建任务，给替代方案。
2. **能降级就明确降级**：用户要 QQ，当前只有应用内，问“要不要先记成应用内任务”，不要默认替换。
3. **不承诺未来能力**：不要说“稍后会提醒”，除非调度器/通道真的已上线。
4. **任务详情展示投递状态**：`应用内` / `QQ 未绑定` / `已发送` / `发送失败`。

## 7. 验证

- `TaskCapabilityGuardTest`：QQ/微信/短信/邮件/电话/钉钉/飞书/Telegram/WhatsApp 拒绝；
  “查邮件/回邮件/打电话给妈妈/付款”不误伤；代下单/转账/购买拒绝。
- `IntentServiceimplTest.shouldRejectUnsupportedDeliveryChannelBeforeCallingExtractor`：
  证明拦截发生在 LLM 提取之前，且不会写 pending。
