# 任务闹钟（触发器 + 动作 + 执行记录）

一句话：**任务不是待办清单，是一个闹钟。** 时间规则到了，就执行这条任务配的"动作"，然后把这次执行记下来。

```
        ┌──────────── 每分钟 ────────────┐
        │ TaskAlarmScheduler.tick()      │
        │  ① Redis 锁（多实例不重复响）  │
        │  ② 查 due_at 已到期 & 本轮没响 │
        │  ③ 执行 action_type 对应动作   │
        │  ④ 写 tb_task_run              │
        │  ⑤ 标记 last_notified_at       │
        └────────────────────────────────┘
```

## 为什么这么设计

待办清单只解决"记下来"，闹钟解决"到点替我做事"。两者只差一层抽象：

| 层 | 存什么 | 可变部分 |
|---|---|---|
| 触发器 | `due_at` + `recurrence` + `recurrence_rule` | 时间规则（once/daily/weekly，monthly 预留） |
| 动作 | `action_type` + `action_config` | 到点干什么：站内提醒 / webhook / 以后接别的 |
| 记录 | `tb_task_run` | 响过几次、成没成、为什么失败 |

关键点：**时间规则和动作解耦**。加一种新动作（发邮件、调 MCP 取数、写日报）只需要新建一个
`TaskAction` 实现类注册成 Bean，调度器一行都不用改。

## 为什么不接真实 QQ

个人 QQ 没有官方推送 API，能走的只有三条路：

1. 第三方协议（NapCat 之类）—— 违反协议、有封号风险，不建议放进简历项目；
2. 官方机器人 —— 需要企业/主体资质；
3. **群里加个机器人，拿到 webhook 地址** —— 企业微信、钉钉、飞书都支持，10 行代码就能推。

所以本项目的做法是：**把"推送"抽象成 `webhook` 动作**，谁想接什么就换成谁的 webhook 地址。
这跟外卖系统不送真外卖是一个道理——**链路完整、接口可替换**，比硬接一个会封号的野路子更有说服力。

## 动作类型

| `action_type` | 行为 | `action_config` |
|---|---|---|
| `notify`（默认） | 只记一条执行记录，前端轮询 `/user/tasks/due` 就能看到 | 不需要 |
| `webhook` | POST JSON 到指定地址，2xx 算成功 | `{"url":"https://..."}` |

想加新动作：

```java
@Component
public class SmsTaskAction implements TaskAction {
    public String name() { return "sms"; }
    public String run(UserTask task, LocalDateTime now) { /* 发短信 */ return "已发短信"; }
}
```

然后把任务的 `action_type` 改成 `sms` 即可（目前没有 UI，直接用 SQL 或后续加接口）。

## 幂等与容错

- **不重复响**：`last_notified_at` 记本轮响过；查询条件是 `last_notified_at IS NULL OR last_notified_at < due_at`。
  重复任务完成时 `complete()` 会清空 `last_notified_at` 并滚动 `due_at`，所以下一轮照常响。
- **多实例**：Redis 锁 `aichat:task:alarm:lock`（带 token 的 compare-and-delete 释放）；Redis 挂了降级为无锁执行
  —— 宁可重复响一次，也不漏。
- **失败隔离**：单条任务失败只写 `status=failed` 并继续处理下一条；失败也会标记 `last_notified_at`，
  否则每分钟重试一次把日志刷爆。
- **执行记录失败**：只 warn，不影响任务本身。

## 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/user/tasks/due` | 到期待办（原有） |
| GET | `/user/tasks/{id}/runs?limit=20` | 这条任务响过几次、成没成 |
| PUT | `/user/tasks/{id}` | 改标题/时间/动作（`actionType` + `actionConfig`，带 `version`）；只支持 notify / webhook |

前端在 `/tasks` 页：列表按状态筛选、展开看执行记录、点「设置」改动作（选 webhook 时填地址）。

## 配置

| 配置项 | 默认 | 说明 |
|---|---|---|
| `app.task.alarm.enabled` | `true` | 总开关 |
| `app.task.alarm.interval-ms` | `60000` | 扫描间隔 |
| `app.task.alarm.initial-delay-ms` | `20000` | 启动后首次延迟 |
| `app.task.alarm.batch` | `50` | 单轮最多处理条数 |
| `app.task.alarm.lock-seconds` | `50` | 锁时长 |

## 怎么验证

```bash
# 1. 建一条 2 分钟后到期的任务（或直接改库 due_at）
# 2. 等一个扫描周期，看日志
grep "任务闹钟本轮处理" <日志>
# 3. 查执行记录
curl -H "Authorization: <token>" http://localhost:8080/user/tasks/<id>/runs
```

单测在 `src/test/java/com/aichat/Service/support/TaskAlarmSchedulerTest.java`：正常响、动作失败、未知动作、
没有到期任务、单条失败不影响其它任务。

## 已知局限

- 只支持**分钟级**精度（每分钟扫一次），秒级提醒需要调整间隔或改用延迟队列。
- 时间规则只有 once/daily/weekly（monthly 在 `recurrence` 枚举里预留但未开放）。
- 没有"稍后提醒 / 错过补偿"：错过就是错过，下一轮按 `due_at` 判断。
- `webhook` 不做重试退避，失败就记 failed（面试可讲的改进点）。
