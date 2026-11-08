# 记忆时间衰减（Memory Decay）

> 一句话：记忆的注入顺序不再只看 `confidence`，而是 `confidence × 0.5^(闲置天数 / 半衰期)` ——
> 三个月没被用到的高置信度记忆，会让位给上周刚聊过的偏好。

## 1. 解决什么问题

P1 的注入顺序是 `confidence DESC, update_time DESC`。这个排序有两个毛病：

1. **老记忆永久霸榜**：一条 `confidence=0.95` 的旧偏好，会把三个月来新形成的记忆一直压在下面。
2. **「更新过」不等于「用得上」**：`update_time` 只在写入时变，记忆被注入、被模型实际用到并不会留下痕迹。

衰减把「最近有没有被用到」这个信号补进来，而且不引入新的状态机：**只记录事实，权重现算**。

## 2. 为什么是「查询时算」而不是定时任务

| 方案 | 做法 | 代价 |
|---|---|---|
| A（采用） | 存 `last_used_at` / `use_count`，注入时现算 score | 每次注入多一次 O(n log n) 排序，n ≤ 100 |
| B（否决） | 定时任务按天刷 `weight` 列 | 要加调度、要防并发、权重会过期不一致，且「没跑任务的那天」数据是错的 |

选 A 的关键理由：**权重是纯函数，任何时刻都能从两列事实重算出来**。没有后台任务就没有「任务挂了导致数据错」这一类故障，也不用担心多实例重复刷新。

## 3. 数据模型

`docs/migration/2026-11-08-memory-decay.sql`（可重复执行）：

```sql
ALTER TABLE tb_user_memory ADD COLUMN last_used_at DATETIME NULL;
ALTER TABLE tb_user_memory ADD COLUMN use_count INT NOT NULL DEFAULT 0;
```

- `last_used_at`：上次被注入进 prompt 的时间，NULL 表示从没用过。
- `use_count`：累计被注入次数，只用于观测和后续调参，**不参与打分**。

注入成功后批量更新：

```sql
UPDATE tb_user_memory
   SET last_used_at = #{now}, use_count = use_count + 1
 WHERE user_id = #{userId} AND status = 'active' AND id IN (...)
```

## 4. 公式

```
idleDays = max(0, now - (last_used_at ?? update_time)) 的天数
score    = confidence × 0.5 ^ (idleDays / halfLifeDays)
```

| 参数 | 默认 | 说明 |
|---|---|---|
| `app.mindbutler.memory.decay-enabled` | `true` | 关掉退回旧的 `confidence, update_time` 排序 |
| `...decay-half-life-stable` | `180` 天 | 稳定偏好（名字、长期习惯）衰减慢 |
| `...decay-half-life-dynamic` | `30` 天 | 临时状态（这周在备考、最近在看某剧）衰减快 |

半衰期举例（稳定记忆，半衰期 180 天）：

| 闲置 | 权重系数 | confidence=0.9 时的 score |
|---|---|---|
| 0 天 | 1.00 | 0.900 |
| 90 天 | 0.71 | 0.639 |
| 180 天 | 0.50 | 0.450 |
| 360 天 | 0.25 | 0.225 |

## 5. 注入流程（`MemoryServiceImpl.listForInjection`）

1. 候选池放大 3 倍：`pool = min(max(stableLimit + dynamicLimit, 5) × 3, 100)`。
   **为什么**：先按旧 `ORDER BY` 取前 N 再排衰减，久未使用但该被捞回来的记忆早就被截掉了。
2. `rank()`：过滤过期 → 按 score 降序 → 取配额；同分时 `update_time` 新的优先（保证同一批记忆每次注入顺序稳定，不抖动）。
3. stable 组在前、dynamic 组在后，保持原有 prompt 结构。
4. 注入成功后 `touchUsage()` 批量记一次使用；**写失败只 `log.warn`**，本轮对话照常返回。

边界处理：

- `last_used_at` 为 NULL → 用 `update_time` 起算（刚写进来的记忆不该因为「还没被用过」吃亏）。
- 时间是未来（时钟回拨 / 脏数据）→ 按闲置 0 天，不放大权重。
- `halfLifeDays <= 0` → 不衰减，也不除零。
- `confidence <= 0` → score 直接为 0。

## 6. 怎么验证

```bash
# 1. 纯函数（8 个）
./mvnw.cmd -o test -Dtest=MemoryDecayTest

# 2. 服务层排序 / 兜底 / 记使用（4 个）
./mvnw.cmd -o test -Dtest=MemoryServiceImplTest

# 3. 全量
./mvnw.cmd -o test     # 294 个用例
```

手工看效果：把某条记忆的 `last_used_at` 改成 400 天前，再发一句普通聊天，日志里注入顺序会变；注入后 `last_used_at` 被刷成当前时间。

## 7. 已知局限

- 记的是「被注入」，不是「被模型真的用了」——注入进 prompt 的记忆模型可能没用上。要做到精确得等后接 rerank 或引用标注。
- `use_count` 目前只观测，没有参与公式（可以将来做「用了就加分」的正反馈）。
- 排序是进程内 O(n log n)，n 上限 100，够用；真到几千条记忆要换成 SQL 侧计算或预排序。
- 用户无法手动「钉住」某条记忆（优先级最高、永不衰减），这是 P2 可做的交互。

## 8. 面试怎么讲（三句话）

1. **问题**：记忆注入如果只按置信度排，老记忆会永久霸榜，新形成的偏好永远排不上来。
2. **方案**：只存事实（上次使用时间、使用次数），权重用半衰期公式在查询时现算——stable 180 天、dynamic 30 天，可配。
3. **取舍**：否决了定时任务刷权重，因为权重是纯函数，现算没有「任务挂了数据就错」的故障面；代价是每次注入多一次排序，而上限只有 100 条。
