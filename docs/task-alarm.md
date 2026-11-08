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

## 怎么创建任务

三条路，都走同一条「意图 → 待确认 → 落库」管线：

1. 聊天里直接说（「明天九点提醒我交作业」）；
2. 任务页右上角「新建任务」→ 写一句话 → 解析 → 确认；
3. `POST /user/tasks` 直接传结构化字段（脚本/测试用）。

第 2 条没有独立的后端接口——它复用 `/chat/ask`，拿回 `[[MB:pending=N]]` 标记后再确认。
这样"理解"只有一份实现，页面和聊天不会出现两套解析规则。

## 任务页怎么用

前端 `/tasks` 是任务的唯一管理入口（聊天页右上角「任务」按钮、侧边栏底部入口都到这里）。

- **概览四格**：已到期 / 今天 / 本周 / 待办。点一下按时间段过滤，再点一次取消。
- **一句话新建**：页面顶部的输入框是常驻的，写「明天九点提醒我交作业」回车即可，走的是和聊天完全相同的意图管线（解析 → 待确认弹窗 → 确认落库），没有第二套实现。
- **按时间分组**：待办按 已到期 / 今天 / 明天 / 本周 / 以后 / 不限时间 分组，组头带数量；已完成、已取消各自一列。
- **卡片操作**：完成、执行记录（展开看每次触发的时间/状态/详情）、设置（改时间、重复规则、动作）、删除。
- **搜索**：按标题在已加载的 50 条里筛（个人使用量级足够，不做后端模糊查询）。

## 动作类型

| `action_type` | 行为 | `action_config` |
|---|---|---|
| `notify`（默认） | 只记一条执行记录，前端轮询 `/user/tasks/due` 就能看到 | 不需要 |
| `webhook` | POST JSON 到指定地址，2xx 算成功 | `{"url":"https://..."}` |
| `mcp` | 到点调一次 MCP 工具，把结果写进执行记录（查天气/搜索/时间/抓网页） | `{"tool":"mcp_info_weather","args":{"city":"北京"}}` |

### mcp 动作

任务不只可以「提醒你」，还可以「到点替你查一次」。`action_type=mcp` 时，调度器会调用
`GET /mcp/tools` 里列出的工具，把返回压成一行写进 `tb_task_run.detail`，前端在任务页的
「执行记录」里就能看到结果。

只放行信息查询类工具（`weather` / `search` / `now` / `fetch_url`）——文件读写这类有副作用的工具
不适合放进无人值守的定时任务；意图层若识别出不认识的工具，会降级成 `notify` 而不是报错。

三种创建方式都支持 mcp 动作：

1. 聊天里说「明天早上九点帮我查一下北京天气」→ 意图层填 `tool=weather` + `toolArgs.city=北京` → 确认后落库；
2. 任务页「设置」里选「让 AI 查点东西（MCP 工具）」→ 选工具 + 填参数；
3. `POST /user/tasks` 直接传 `actionType` / `actionConfig`。

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
