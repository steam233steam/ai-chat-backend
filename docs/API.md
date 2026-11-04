# AI Chat 后端接口文档

> 文档版本：v7.5  
> 最后更新：2026-11-04 17:50  
> 基础路径：`http://localhost:8080`  
> 变更记录：v7.5 - MindButler P1.2（意图增强：LLM 兜底 NONE 也过 LLM + 人设提取语义修正 + QUERY 按会话隔离查记忆 + 疑问词扩充）；v7.4 - MindButler P1.1b（确认文案口语化 + 确认后反馈落库到会话历史 + QUERY 意图主题词扩充）；v7.3 - MindButler P1.1（查询意图直接读数据回答 + 记忆会话隔离）；v7.2 - 前端任务确认弹窗；v7.1 - 聊天文案 `[[MB:pending=N]]` 标记 + `GET /user/pending-actions/{id}`

---

## 目录
1. [统一响应格式](#1-统一响应格式)
2. [用户接口](#2-用户接口)
3. [聊天接口](#3-聊天接口)
4. [MindButler P1 接口](#4-mindbutler-p1-接口)
5. [错误码说明](#5-错误码说明)
6. [数据存储说明](#6-数据存储说明)
7. [多模型说明](#7-多模型说明)
8. [会话锁模型说明](#8-会话锁模型说明)
9. [模型输出净化说明](#9-模型输出净化说明)
10. [前端使用示例](#10-前端使用示例)
11. [认证说明](#11-认证说明)
12. [CORS 说明](#12-cors-说明)
13. [本地开发说明](#13-本地开发说明)

---

## 1. 统一响应格式

所有接口返回数据遵循以下格式：

```json
{
  "code": 200,
  "message": "成功",
  "data": {}
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| code | Integer | 状态码，200 成功，400/401/404/409/500 失败 |
| message | String | 提示信息 |
| data | T | 返回数据，类型根据接口而定 |

**说明**：业务异常统一通过 `GlobalExceptionHandler` 处理，错误响应也遵循此格式。

---

## 2. 用户接口

> 前缀：`/user`

### 2.1 发送验证码

发送验证码到指定手机号（同一手机号 60 秒内只能发一次）。

- **接口地址**：`POST /user/code`
- **Content-Type**：`application/x-www-form-urlencoded`
- **公开接口**（无需登录）

#### 请求参数

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| phone | String | 是 | 手机号 |

#### 请求示例

```bash
POST /user/code?phone=13800138000
```

#### 响应示例

```json
{
  "code": 200,
  "message": "验证码已发送",
  "data": null
}
```

#### 错误响应

```json
{
  "code": 400,
  "message": "验证码发送过于频繁，请 60 秒后再试",
  "data": null
}
```

**说明**：
- 验证码有效期 5 分钟
- 同一手机号 60 秒冷却，防止短信轰炸
- 开发模式下验证码打印在日志中（仅前 2 位脱敏），不直接返回

---

### 2.2 验证码登录

使用手机号 + 验证码登录，登录成功后返回 Token。

- **接口地址**：`POST /user/login`
- **Content-Type**：`application/json`
- **公开接口**（无需登录）

#### 请求参数

```json
{
  "phone": "13800138000",
  "code": "123456"
}
```

#### 响应示例

```json
{
  "code": 200,
  "message": "成功",
  "data": "550e8400-e29b-41d4-a716-446655440000"
}
```

#### 错误响应

```json
{
  "code": 400,
  "message": "验证码错误或已过期",
  "data": null
}
```

**说明**：
- 新用户自动创建账号（默认昵称"用户+手机尾号"），密码为 NULL
- 验证码一次性使用，校验通过后立即删除
- Token 有效期 7 天，后续请求需携带 Token
- 新用户会被标记，需要后续设置密码

---

### 2.3 密码登录

使用手机号 + 密码登录。

- **接口地址**：`POST /user/login/password`
- **Content-Type**：`application/json`
- **公开接口**（无需登录）

#### 请求参数

```json
{
  "phone": "13800138000",
  "password": "xxxxxx"
}
```

#### 响应示例

```json
{
  "code": 200,
  "message": "成功",
  "data": "550e8400-e29b-41d4-a716-446655440000"
}
```

#### 错误响应

```json
{
  "code": 400,
  "message": "手机号或密码错误",
  "data": null
}
```

**说明**：
- 为避免暴露手机号是否已注册，统一提示"手机号或密码错误"
- 未设置密码的新用户无法使用密码登录（password 为 NULL）

---

### 2.4 设置密码

设置或重置密码（需要验证码）。

- **接口地址**：`PUT /user/set-password`
- **Content-Type**：`application/json`
- **请求头**：`Authorization: {token}`

#### 请求参数

```json
{
  "phone": "13800138000",
  "code": "123456",
  "password": "newpassword123"
}
```

#### 响应示例

```json
{
  "code": 200,
  "message": "密码设置成功",
  "data": null
}
```

#### 错误响应

```json
{
  "code": 400,
  "message": "密码需为 4-32 位字母、数字或下划线",
  "data": null
}
```

**说明**：
- 手机号必须与当前登录账号一致，防止越权
- 密码需符合强度要求（4-32 位字母、数字或下划线）
- 设置成功后自动取消新用户标记

---

### 2.5 获取当前用户信息

获取当前登录用户的信息，包含是否为新用户的标记。

- **接口地址**：`GET /user/me`
- **请求头**：`Authorization: {token}`

#### 响应示例

```json
{
  "code": 200,
  "message": "成功",
  "data": {
    "id": "USER_ID_abc123",
    "name": "用户8000",
    "phone": "13800138000",
    "isNewUser": true
  }
}
```

#### 错误响应（未登录）

```json
{
  "code": 401,
  "message": "请先登录",
  "data": null
}
```

---

### 2.6 检查是否新用户

检查当前用户是否为新用户（需要设置密码）。

- **接口地址**：`GET /user/is-new-user`
- **请求头**：`Authorization: {token}`

#### 响应示例

```json
{
  "code": 200,
  "message": "成功",
  "data": true
}
```

**说明**：
- 返回 `true` 表示是新用户，需要设置密码
- 返回 `false` 表示已设置密码，可正常使用

---

### 2.7 修改资料

修改用户昵称（最长 20 字符）。

- **接口地址**：`PUT /user/profile`
- **Content-Type**：`application/json`
- **请求头**：`Authorization: {token}`

#### 请求参数

```json
{
  "name": "新昵称"
}
```

#### 响应示例

```json
{
  "code": 200,
  "message": "修改成功",
  "data": null
}
```

#### 错误响应

```json
{
  "code": 400,
  "message": "昵称长度不能超过 20 个字符",
  "data": null
}
```

**说明**：修改昵称不会清除新用户标记（需在设置密码后清除）。

---

### 2.8 登出

注销登录，删除 Token。

- **接口地址**：`POST /user/logout`
- **请求头**：`Authorization: {token}`

#### 响应示例

```json
{
  "code": 200,
  "message": "登出成功",
  "data": null
}
```

---

## 3. 聊天接口

> 前缀：`/chat`，除 `/chat/models` 外所有接口需要登录，请求头携带 Token：`Authorization: {token}`

### 3.1 获取可用模型

获取可用模型白名单（前端渲染下拉框用）。

- **接口地址**：`GET /chat/models`
- **公开接口**（无需登录）

#### 响应示例

```json
{
  "code": 200,
  "message": "成功",
  "data": [
    "qwen2.5-coder:7b",
    "deepseek-chat"
  ]
}
```

**说明**：
- 返回的是模型名数组，前端可直接用于下拉框
- 模型名需在白名单内，否则会报 400

---

### 3.2 发送消息（同步）

获取 AI 回复，同时保存对话历史（MySQL 持久化 + Redis 缓存）。回复内容经输出净化（剥安全标签）后落库。

> ⭐ **意图拦截**：`POST /chat/ask` 发送前会先走 `IntentService.handle`。命中意图（记忆/任务/确认/拒绝/查询）时只返回一条文案，**不继续走模型聊天**；否则走正常聊天。意图分类：
> - **MEMORY / TASK**（"记住…" / "明天…"）→ LLM 提取候选 + 写 pending，文案尾部带 `[[MB:pending=N]]` 标记，前端据此渲染"确认弹窗"（见 4.3）
> - **QUERY**（"我有哪些任务 / 你记住了我什么 / 你的记忆怎么样"）→ P1.1 新增：**不调 LLM**，直接读当前用户的任务/记忆数据拼自然文案回答（P1.2 起记忆按会话隔离读：`listForInjection(sessionId)`，别的会话的人设不串进来）；没有数据时明确说"还没有"
> - **NONE（P1.2 LLM 兜底）**：关键词没命中但命中宽松触发词（"你是 X" / "叫我 X" / "我平时 X"）→ 过一次 LLM 判断有无可提取内容，有则写 pending 弹窗，无则走正常聊天（`app.mindbutler.llm-intent-fallback` 开关控制，默认开）
> - **CONFIRM / REJECT / UPDATE / NO_PENDING** → 固定文案处理现有 pending

- **接口地址**：`POST /chat/ask`
- **Content-Type**：`application/x-www-form-urlencoded`

#### 请求参数

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| sessionid | String | 是 | 会话 ID（≤64 字符） |
| question | String | 是 | 用户消息内容（≤8000 字符） |
| modelName | String | 否 | 模型名（白名单内），不传用默认模型 |

#### 请求示例

```bash
POST /chat/ask?sessionid=s001&question=你好&modelName=deepseek-chat
Authorization: 550e8400-e29b-41d4-a716-446655440000
```

#### 响应示例

```json
{
  "code": 200,
  "message": "成功",
  "data": "你好！有什么我可以帮你的吗？"
}
```

#### 错误响应

```json
{
  "code": 400,
  "message": "不支持的模型: gpt-4",
  "data": null
}
```

```json
{
  "code": 409,
  "message": "该会话已锁定为 qwen2.5-coder:7b，切换模型请新建会话",
  "data": null
}
```

**说明**：
- 首次发消息时自动创建会话并生成默认标题（取问题前 20 个字符）
- 会话列表按最近活跃时间倒序排列
- 历史按条数（默认 20）+ 字符预算（默认 24000）裁剪后以真实 User/Assistant 角色送入模型
- 回复经 `ModelOutputSanitizer` 净化（剥 `<ds_safety>` 标签），落库和返回的都是干净内容
- AI 标题异步生成（不阻塞主流程），标题也走净化
- 会话锁模型：新会话锁到本次传入的模型；已有会话若传入模型与锁定模型不一致 → 409
- 命中意图时，`data` 是确认文案，此时不产生 AI 模型回复：
  - **MEMORY / TASK**（"记住…" / "明天…"）→ LLM 提取候选 + 写 pending，文案尾部带 `[[MB:pending=N]]` 机器标记，前端据此渲染确认弹窗（见 4.3）
  - **QUERY**（"我有哪些任务 / 你记住了我什么 / 你的记忆怎么样"，P1.1 新增）→ **不调 LLM**，直接读当前用户任务/记忆数据回答（P1.2 起记忆按会话隔离读：`listForInjection(sessionId)`，别的会话人设不串进来）；无数据时明确说"还没有"
  - **NONE（P1.2 LLM 兜底）**：关键词没命中但命中宽松触发词（"你是 X" / "叫我 X" / "我平时 X"）→ 过一次 LLM 判断有无可提取内容，有则写 pending 弹窗，无则走正常聊天（`app.mindbutler.llm-intent-fallback` 开关控制，默认开）
  - **CONFIRM / REJECT** → 处理现有 pending；P1.1b 起确认后会把反馈文案作为 assistant 消息落库到会话历史（前端拉历史可见，聊天自然衔接）
  - **UPDATE / NO_PENDING / 兜底** → 固定文案，不带标记

---

### 3.3 发送消息（流式）

使用 SSE（Server-Sent Events）流式返回 AI 回复，实现打字机效果。回复经流式安全过滤器净化后发送。

> ⭐ **意图拦截**：与同步一致，命中意图时只发一条 `data` 事件（多行：确认文案 + `[[MB:pending=N]]` 标记）后结束，不走模型流。

- **接口地址**：`GET /chat/ask-stream`
- **Content-Type**：`text/event-stream`

#### 请求参数

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| sessionid | String | 是 | 会话 ID（≤64 字符） |
| question | String | 是 | 用户消息内容（≤8000 字符） |
| modelName | String | 否 | 模型名（白名单内），不传用默认模型 |

#### 请求示例

```bash
GET /chat/ask-stream?sessionid=s001&question=写一个快速排序&modelName=qwen2.5-coder:7b
Authorization: 550e8400-e29b-41d4-a716-446655440000
```

#### 响应示例（SSE 格式）

```
data: 快速排序是一种高效的排序算法，采用分治法策略。

data: 下面是 Java 实现：

data: ```java
data: public static void quickSort(int[] arr, int low, int high) {
data:     if (low < high) {
data:         int pi = partition(arr, low, high);
data:         quickSort(arr, low, pi - 1);
data:         quickSort(arr, pi + 1, high);
data:     }
data: }
data: ```

data: [DONE]
```

**说明**：
- 客户端主动断开时，已生成的部分也会保存（避免上下文丢失）
- 每条 AI 回复完整后落库（MySQL + Redis，含模型名 + 净化）
- 流式输出经 `StreamingSafetyFilter` 跨 chunk 剥 `<ds_safety>` 标签
- 模型调用失败时兜底文案不写入历史（避免污染下一轮上下文）
- 模型名校验失败或会话锁模型冲突时报 400/409，不会进入流式
- 命中意图时，只发一条 `data` 事件（确认文案 + 可选 pending 标记）后结束，不走模型流
- 前端在 SSE `onComplete` 后从完整文案提取 pendingId，再调 `GET /user/pending-actions/{id}` 拉弹窗详情

---

### 3.4 获取会话列表

获取当前用户的所有会话列表（按最近活跃时间倒序），含锁定的模型。

- **接口地址**：`GET /chat/sessions`
- **请求头**：`Authorization: {token}`

#### 响应示例

```json
{
  "code": 200,
  "message": "成功",
  "data": [
    {
      "sessionId": "s001",
      "title": "你好，请介绍...",
      "model": "deepseek-chat"
    }
  ]
}
```

**说明**：
- `model` 为会话锁定的模型名，未锁定（旧会话）时为 null
- Redis 缓存过期后会话列表自动从 MySQL 恢复，数据不会丢失
- 批量取模型（一次 HGETALL + 最多一次 MySQL），避免逐会话查库

---

### 3.5 获取会话历史

获取指定会话的所有消息历史，包含时间戳、消息 ID（分页游标）和模型名。

- **接口地址**：`GET /chat/history`
- **请求头**：`Authorization: {token}`

#### 请求参数

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| sessionid | String | 是 | 会话 ID |
| beforeId | Long | 否 | 分页游标（传该 ID 之前的一页），需配 limit |
| limit | Integer | 否 | 每页条数（1-100），不传时返回全量历史 |

#### 响应示例

```json
{
  "code": 200,
  "message": "成功",
  "data": [
    {
      "id": 100,
      "role": "user",
      "content": "你好，请介绍一下你自己",
      "model": "qwen2.5-coder:7b",
      "timestamp": 1727356800000
    },
    {
      "id": 101,
      "role": "assistant",
      "content": "你好！我是一个乐于助人的 AI 助手...",
      "model": "qwen2.5-coder:7b",
      "timestamp": 1727356805000
    }
  ]
}
```

**说明**：
- `id`: 消息自增 ID（分页游标）
- `role`: 消息角色（user/assistant）
- `content`: 消息内容（已净化，不含安全标签）
- `model`: 本轮使用的模型名（model 列上线前的历史消息为 null）
- `timestamp`: 时间戳（毫秒）
- 不传 `limit` 时返回全量历史（现有前端无需改动）
- `beforeId` 必须配 `limit` 使用，否则报错
- `limit` 范围 1-100
- 分页查询不回写缓存（避免把"一页"当成全量历史）
- 响应顺序：`beforeId` 之前的最近 `limit` 条，按 id 升序

---

### 3.6 更新会话标题

更新指定会话的标题（最长 50 字符）。

- **接口地址**：`PUT /chat/title`
- **请求头**：`Authorization: {token}`

#### 请求参数

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| sessionid | String | 是 | 会话 ID |
| title | String | 是 | 新标题（≤50 字符） |

#### 响应示例

```json
{
  "code": 200,
  "message": "标题更新成功",
  "data": null
}
```

#### 错误响应

```json
{
  "code": 400,
  "message": "title 长度不能超过 50 个字符",
  "data": null
}
```

**说明**：
- 手动更新标题后，异步 AI 标题生成不会覆盖（只有标题仍等于兜底值时才覆盖）
- 会话不存在时会自动创建一条会话记录

---

### 3.7 清空会话历史

删除指定会话的所有历史记录（MySQL + Redis，含锁模型缓存）。

- **接口地址**：`DELETE /chat/clear-session`
- **请求头**：`Authorization: {token}`

#### 请求参数

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| sessionid | String | 是 | 会话 ID |

#### 响应示例

```json
{
  "code": 200,
  "message": "删除成功",
  "data": null
}
```

#### 错误响应（会话不存在或仅有 Redis 缓存已过期）

```json
{
  "code": 400,
  "message": "删除失败，会话不存在或已过期",
  "data": null
}
```

**说明**：先删 MySQL（消息 + 会话元数据），再清 Redis 缓存（含锁模型 Hash 的对应 field）。

---

## 4. MindButler P1 接口 ⭐ 新增

> 前缀：`/user`，需要登录，请求头携带 Token：`Authorization: {token}`  
> MindButler P1 = 长期记忆（结构化画像）+ 口语化任务 + 待确认动作（v3 状态机）  
> 所有接口按当前登录用户隔离；跨用户访问统一 404（避免枚举）

### 4.1 用户任务 `/user/tasks`

口语化任务的 manual CRUD + 完成 + 到期查询；AI 创建走 M3 意图提取器 → pending → 本服务 create。

#### 4.1.1 到期待办

- **接口地址**：`GET /user/tasks/due`
- **说明**：`status=pending`、有 `due_at` 且已到期，按 `due_at` 升序；P1 只读不回写 `last_notified_at`，前端按 `(id, dueAt)` 去重

| 参数名 | 类型 | 必填 | 默认 | 说明 |
|--------|------|------|------|------|
| limit | int | 否 | 50 | 条数上限，不超过 `app.mindbutler.task.due-limit` |

#### 响应示例

```json
{
  "code": 200,
  "message": "成功",
  "data": [
    {
      "id": 1,
      "title": "交周报",
      "dueAt": "2026-11-05T09:00:00",
      "timezone": "Asia/Shanghai",
      "recurrence": "weekly",
      "recurrenceRule": "BYDAY=MO",
      "status": "pending",
      "overdue": false,
      "version": 0
    }
  ]
}
```

#### 4.1.2 任务列表

- **接口地址**：`GET /user/tasks`

| 参数名 | 类型 | 必填 | 默认 | 说明 |
|--------|------|------|------|------|
| status | String | 否 | pending | pending / done / cancelled |
| beforeId | Long | 否 | - | 游标（id < beforeId，升序返回） |
| limit | int | 否 | 50 | 1-100 |

#### 4.1.3 查单条任务

- **接口地址**：`GET /user/tasks/{id}`
- 含 `overdue` 计算字段；非本人或已软删返回 404

#### 4.1.4 手动创建任务（兜底）

- **接口地址**：`POST /user/tasks`
- 不走 LLM；`recurrence` 默认 `once`；`weekly` 必须带 `recurrenceRule`；`recurring` 必须有 `dueAt`

```json
{
  "title": "周五交周报",
  "dueAt": "2026-11-06T09:00:00",
  "recurrence": "weekly",
  "recurrenceRule": "BYDAY=FR",
  "rawInput": "记得周五交周报"
}
```

#### 4.1.5 更新任务（乐观锁）

- **接口地址**：`PUT /user/tasks/{id}`
- 只能改 `pending` 任务；`status` 只允许 `pending/cancelled`；`version` 不匹配返回 409

```json
{
  "title": "改期到下周五",
  "dueAt": "2026-11-13T09:00:00",
  "version": 0
}
```

#### 4.1.6 完成任务

- **接口地址**：`POST /user/tasks/{id}/complete`
- `once` → `done`；`daily/weekly` → 保持 `pending` 且 `due_at` 滚动到下一次；`version` 不匹配 409

```json
{
  "version": 0
}
```

#### 4.1.7 删除任务（软删）

- **接口地址**：`DELETE /user/tasks/{id}`
- 幂等；已是删除态返回 404

**TaskVO 字段**：`id, title, dueAt, timezone, recurrence, recurrenceRule, status, deletedAt, completedAt, rawInput, version, createTime, updateTime, overdue`

---

### 4.2 用户记忆 `/user/memories`

#### 4.2.0 记忆会话隔离（P1.1）⭐

`tb_user_memory` 新增 `session_id` 列（迁移脚本 `docs/migration/2026-11-03-mindbutler-p1-memory-session.sql`）：

| 取值 | 含义 |
|------|------|
| `NULL` | 全局画像（跨会话生效，默认） |
| 有值 | 仅该会话生效（会话级人设，如"这个会话里你是猫娘"） |

**行为说明**：
- AI 提取时 LLM 多输出 `scope` 字段（`global` / `session`），长期习惯/偏好默认 global，本会话人设/临时口吻为 session；拿不准填 global
- 注入链路（`PromptBuilder.build(base, sessionScope)`）只取「全局(NULL) + 当前会话」的记忆，会话 A 设定的人设**不会带进**会话 B
- 去重范围随作用域：会话级记忆只在同会话去重，全局记忆跨会话去重
- 存量数据 `session_id` 全为 NULL → 行为不变，向后兼容
- 前端无需改接口：记忆 CRUD 不变；`MemoryVO` 新增 `sessionId` 字段（NULL=全局，有值=会话级）

---

### 4.2.1 用户记忆 `/user/memories`

长期记忆（结构化画像，固定 schema，不用向量库）的 manual CRUD + 注入查询 + 导出。

#### 4.2.1 查询生效记忆

- **接口地址**：`GET /user/memories`
- 返回当前用户 active 且未过期的记忆，按类型 + 更新时间倒序；`limit` 控制总数 1-100

#### 4.2.2 导出全部记忆

- **接口地址**：`GET /user/memories/export`
- 导出当前用户全部未删除记忆（含 `superseded`），用于隐私查看 / 数据迁移

#### 4.2.3 查单条记忆

- **接口地址**：`GET /user/memories/{id}`
- 含 `status` 与 `expired` 计算字段；非本人或已删除返回 404

#### 4.2.4 手动创建记忆

- **接口地址**：`POST /user/memories`
- `source=manual`、`confidence=1.00`；`stable` 不可设过期；内容命中敏感词直接拒绝；同一用户已有完全相同的 active 内容 → 409

```json
{
  "memKey": "goal",
  "content": "用户目标是转后端开发",
  "memType": "stable"
}
```

**说明**：
- `memKey`：`style | goal | schedule | relation`（P1 枚举）
- `memType`：`stable`（长存）/ `dynamic`（会过期）
- `content` 上限 256 字符

#### 4.2.5 更新记忆（乐观锁）

- **接口地址**：`PUT /user/memories/{id}`
- 只能改 active 且未过期的记忆；`version` 不匹配返回 409

```json
{
  "content": "用户目标改为全栈",
  "memType": "stable",
  "version": 0
}
```

#### 4.2.6 删除记忆（软删）

- **接口地址**：`DELETE /user/memories/{id}`
- 幂等；已是删除态返回 404

**MemoryVO 字段**：`id, memKey, content, memType, status, confidence, source, sourceMessageId, replacesId, expiresAt, version, createTime, updateTime, expired, sessionId`（P1.1 新增 `sessionId`：NULL=全局，有值=会话级）

---

### 4.3 待确认动作 `/user/pending-actions`

M3 口语路径：聊天里命中触发词 → 提取器写 pending → 前端用"确认/取消"。这里的 REST 是"确认弹窗"入口（用户点确认/取消）+ 当前 pending 查询。

#### 4.3.1 当前会话的待确认动作

- **接口地址**：`GET /user/pending-actions/current`

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| sessionId | String | 是 | 会话 ID |

没有 / 已过期返回 `data=null`；前端据此渲染确认弹窗。

#### 响应示例

```json
{
  "code": 200,
  "message": "成功",
  "data": {
    "id": 42,
    "actionType": "mixed",
    "status": "pending",
    "memories": [
      { "key": "goal", "value": "转后端", "memType": "stable", "confidence": 0.9 }
    ],
    "tasks": [
      { "action": "create", "title": "周五交周报", "dueAt": "2026-11-06T09:00:00", "recurrence": "weekly", "recurrenceRule": "BYDAY=FR" }
    ],
    "preview": "将记住你的目标，并新建 1 个每周任务，确认？",
    "expiresAt": "2026-11-03T17:36:00"
  }
}
```

#### 4.3.2 按 id 查单条待确认动作

- **接口地址**：`GET /user/pending-actions/{id}`
- 前端拿到 `/chat/ask` 文案里的 pendingId 后调本接口拉弹窗详情
- 非本人 / 不存在 / 已过期 → `data=null`（懒过期语义同 current）

#### 4.3.3 聊天文案里的 pending 标记（前端弹窗契约）⭐

`POST /chat/ask` 命中"提取出新候选并写 pending"时，返回的 `data` 文案**尾部**会带一个机器可读标记。
P1.1b 起文案本身改为口语化（不再用"我识别到："机械格式），例如：

```
我记住你的风格是「简洁」，周五 09:00（11-06）提醒你「交周报」，回复「好」我帮你设上，说「取消」就不存了。
[[MB:pending=42]]
```

| 项目 | 说明 |
|---|---|
| 标记格式 | `[[MB:pending={pendingId}]]`（待确认动作的自增 id） |
| 前端检测 | 正则 `\[\[MB:pending=(\d+)\]\]`，捕获到 id 就弹窗 |
| 弹窗数据源 | `GET /user/pending-actions/{pendingId}`（或 `GET /current?sessionId=`） |
| 确认 / 拒绝 | `POST /user/pending-actions/{pendingId}/confirm` / `reject` |
| 确认反馈落库 | P1.1b：确认后"好的，都帮你设好了…"自动作为 assistant 消息写入会话历史（前端拉历史可见；落库失败不影响确认本身） |
| 展示 | 标记是给机器读的，前端展示文案时**剥离** `[[MB:pending=...]]` 行 |
| 不带标记 | 普通聊天、确认/拒绝/过期/限流等所有兜底文案都**没有**标记 → 不弹窗 |

> 设计取舍：带 id 是为了让前端一次请求就能拿到弹窗所需的关键信息（pendingId + 文案），
> 不必再额外调一次 `current` 来解析。标记只出现在实时响应里；后端落库前会 `MbMarkers.strip` 剥离，
> 所以历史接口不会返回标记。页面刷新/重新进入会话时，前端用 `GET /user/pending-actions/current?sessionId=` 恢复弹窗。
>
> 流式接口同样适用：SSE 事件的完整 data 是多行文本，前端 `SSE onComplete` 后用同一个正则提取 id 即可。

#### 4.3.4 确认待确认动作

- **接口地址**：`POST /user/pending-actions/{id}/confirm`
- 原子确认；重复确认返回 `alreadyConfirmed=true`（不重复建）；已拒绝 / 过期 409
- 确认时按 payload 实际内容（memories/tasks）分 memory-only / task-only / mixed 三路落库
- **P1.1b 起**：确认成功后自动把"好的，都帮你设好了…"反馈文案作为 assistant 消息落库到该会话历史（落库失败不影响确认本身），前端拉历史 / 刷新时可见这条回音

```json
{
  "code": 200,
  "message": "成功",
  "data": {
    "alreadyConfirmed": false,
    "memories": [ { "id": 7, "memKey": "goal", "content": "转后端" } ],
    "tasks": [ { "id": 3, "title": "周五交周报" } ]
  }
}
```

#### 4.3.5 拒绝待确认动作

- **接口地址**：`POST /user/pending-actions/{id}/reject`
- 幂等；已拒绝静默成功；已确认 / 过期 409

**PendingActionVO 字段**：`id, actionType, status, memories[], tasks[], preview, expiresAt`  
**MemoryCandidate**：`key, value, memType, confidence, replacesId, scope`（P1.1 新增 `scope`：global=全局画像，session=仅当前会话）  
**TaskCandidate**：`action, title, dueAt, timezone, recurrence, recurrenceRule`  
**ConfirmResult**：`alreadyConfirmed, memories[], tasks[]`

---

## 5. 错误码说明

| 错误码 | 说明 | 处理建议 |
|--------|------|----------|
| 200 | 请求成功 | - |
| 400 | 请求参数错误 | 检查参数格式、长度、模型名 |
| 401 | 未登录 | 请先登录 |
| 404 | 接口不存在 / 资源不存在 | 检查路径或资源归属 |
| 409 | 冲突（锁模型 / 乐观锁 / pending 状态 / 重复记忆） | 刷新数据或新建会话 |
| 413 | 上传文件过大 | 检查文件大小 |
| 500 | 系统错误 | AI 服务不可用或服务器异常 |

**409 常见场景**：
- 会话锁模型冲突（切换模型需新建会话）
- 乐观锁 version 不匹配（任务 / 记忆更新）
- pending 已确认 / 已拒绝 / 过期（重复确认）
- 手动创建记忆内容已存在

---

## 6. 数据存储说明

### 6.1 存储职责

| 存储 | 角色 | 内容 |
|------|------|------|
| MySQL | 持久化主存储 | `tb_user`、`tb_chat_session`、`tb_chat_message`、`tb_user_memory`、`tb_user_task`、`tb_pending_action` |
| Redis | 7 天热缓存 | 消息 List、会话 ZSet、标题 Hash、锁模型 Hash、Token、验证码 |

**原则**：先写 MySQL，再更新 Redis；读 Redis 未命中时回源 MySQL 并回填。  
Redis 故障或过期不影响数据正确性，只影响性能。

### 6.2 数据库表结构

```sql
-- 用户表
CREATE TABLE tb_user (
  uid VARCHAR(32) PRIMARY KEY,
  name VARCHAR(64),
  phone VARCHAR(20) UNIQUE,
  password VARCHAR(255) NULL,
  create_time DATETIME,
  update_time DATETIME
);

-- 会话表（唯一键 user_id + session_id，含锁模型列）
CREATE TABLE tb_chat_session (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id VARCHAR(32) NOT NULL,
  session_id VARCHAR(64) NOT NULL,
  title VARCHAR(100) DEFAULT NULL,
  model VARCHAR(64) DEFAULT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_user_session (user_id, session_id),
  KEY idx_user_update (user_id, update_time)
);

-- 消息表（自增 id 保证稳定排序 + 分页游标，含模型列）
CREATE TABLE tb_chat_message (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id VARCHAR(32) NOT NULL,
  session_id VARCHAR(64) NOT NULL,
  role VARCHAR(16) NOT NULL,
  content TEXT NOT NULL,
  model VARCHAR(64),
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_user_session (user_id, session_id, id)
);

-- 用户长期记忆（结构化画像；固定 schema + status 生命周期 + expires_at 过期）
CREATE TABLE tb_user_memory (
  id BIGINT NOT NULL AUTO_INCREMENT,
  user_id VARCHAR(32) NOT NULL,
  mem_key VARCHAR(32) NOT NULL,
  content VARCHAR(512) NOT NULL,
  mem_type ENUM('stable','dynamic') NOT NULL DEFAULT 'stable',
  status ENUM('active','superseded','deleted') NOT NULL DEFAULT 'active',
  confidence DECIMAL(3,2) NOT NULL DEFAULT 1.00,
  source ENUM('manual','ai') NOT NULL DEFAULT 'manual',
  source_message_id BIGINT NULL,
  replaces_id BIGINT NULL,
  expires_at DATETIME NULL,
  version INT NOT NULL DEFAULT 0,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id)
);

-- 用户任务/计划（due_at + recurrence + 乐观锁 + 软删）
CREATE TABLE tb_user_task (
  id BIGINT NOT NULL AUTO_INCREMENT,
  user_id VARCHAR(32) NOT NULL,
  title VARCHAR(128) NOT NULL,
  due_at DATETIME NULL,
  timezone VARCHAR(40) NOT NULL DEFAULT 'Asia/Shanghai',
  recurrence ENUM('once','daily','weekly','monthly') NOT NULL DEFAULT 'once',
  recurrence_rule VARCHAR(128) NULL,
  status ENUM('pending','done','cancelled') NOT NULL DEFAULT 'pending',
  deleted_at DATETIME NULL,
  last_notified_at DATETIME NULL,
  completed_at DATETIME NULL,
  raw_input VARCHAR(512) NULL,
  source_message_id BIGINT NULL,
  version INT NOT NULL DEFAULT 0,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id)
);

-- 待确认动作（AI 提案，用户确认后才写进 memory/task；确认态独立状态机）
CREATE TABLE tb_pending_action (
  id BIGINT NOT NULL AUTO_INCREMENT,
  user_id VARCHAR(32) NOT NULL,
  session_id VARCHAR(64) NOT NULL,
  action_type ENUM('memory','task','mixed') NOT NULL,
  payload JSON NOT NULL,
  status ENUM('pending','confirmed','rejected','expired') NOT NULL DEFAULT 'pending',
  expires_at DATETIME NOT NULL,
  source_message_id BIGINT NULL,
  pending_slot VARCHAR(140) GENERATED ALWAYS AS (
    CASE WHEN status = 'pending' THEN CONCAT(user_id, ':', session_id) ELSE NULL END
  ) STORED,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id)
);
```

完整脚本见 `docs/schema.sql`。

### 6.3 Redis 缓存结构

| Key | 类型 | 说明 | TTL |
|-----|------|------|-----|
| `aichat:login:code:{phone}` | String | 验证码 | 5 分钟 |
| `aichat:login:code:cooldown:{phone}` | String | 验证码发送冷却 | 60 秒 |
| `aichat:login:token:{token}` | Hash | 用户信息 | 7 天 |
| `aichat:user:new:{uid}` | String | 新用户标记 | 365 天 |
| `aichat:chat:sessions:{userId}` | ZSet | 会话列表（按更新时间排序） | 7 天 |
| `aichat:chat:session:{userId}:{sessionId}` | List | 消息历史（含 id 游标 + model + 时间戳） | 7 天 |
| `aichat:chat:title:{userId}` | Hash | 会话标题（field=sessionId） | 7 天 |
| `aichat:chat:sessionmodel:{userId}` | Hash | 会话锁模型（field=sessionId） | 7 天 |

**说明**：
- `model` 为 null 表示该消息产生于 model 列上线之前（历史数据）
- 标题和锁模型统一用 Hash 结构（key 到 userId，field 用 sessionId）
- 记忆 / 任务 / 待确认动作是 MySQL 持久化主存储，不走 Redis 缓存（P1 范围）

### 6.4 缓存一致性策略

| 场景 | 处理 |
|------|------|
| Redis 缓存未命中 | 回源 MySQL 并回填缓存 |
| Redis 写入失败 | 主动失效相关缓存，降级使用 MySQL |
| 缓存回填失败 | 删除不完整缓存，避免脏数据 |
| 会话列表缓存过期 | 从 MySQL 整体重建（避免列表不完整） |
| 清空会话 | 先删 MySQL 再清 Redis（含锁模型 Hash 的对应 field） |
| 旧版缓存缺少 id | 视为未命中，回源 MySQL 重建 |
| model 为 null | 是历史消息的正常状态，不作为缓存失效条件 |
| 分页查询 | 不回写缓存（避免把"一页"当成全量历史） |
| 锁模型 | 以 MySQL 为准回填缓存（并发首条消息时 COALESCE 只保留先到的模型） |

---

## 7. 多模型说明

### 7.1 模型路由

后端支持多个模型，通过 `ModelRouter` 按模型名分发：

| 模型 | 协议 | 说明 |
|------|------|------|
| `qwen2.5-coder:7b` | Ollama（本地） | 默认模型，本地部署 |
| `deepseek-chat` | OpenAI 兼容（DeepSeek） | 云端模型 |

**路由逻辑**：
1. 前端传 `modelName`，不传用默认模型
2. 模型名在 `app.models.ollama-models` 里 → 走 Ollama
3. 其余白名单模型 → 走 OpenAI 兼容协议（DeepSeek）
4. 模型名不在 `app.models.available` 白名单 → 报 400（不静默兜底）

### 7.2 配置项

| 配置 | 默认值 | 说明 |
|------|--------|------|
| `app.models.available` | `qwen2.5-coder:7b,deepseek-chat` | 模型白名单（逗号分隔） |
| `app.models.default-model` | `qwen2.5-coder:7b` | 默认模型 |
| `app.models.ollama-models` | `qwen2.5-coder:7b` | 走 Ollama 的模型，其余走 OpenAI 兼容协议 |

### 7.3 环境变量

| 变量 | 说明 |
|------|------|
| `DEEPSEEK_API_KEY` | DeepSeek API Key（不提交 Git） |
| `DEEPSEEK_BASE_URL` | DeepSeek API 地址（默认 `https://api.deepseek.com`） |
| `OLLAMA_BASE_URL` | Ollama 服务地址（默认 `http://localhost:11434`） |
| `OLLAMA_MODEL` | 默认 Ollama 模型 |

### 7.4 上下文裁剪

| 配置 | 默认值 | 说明 |
|------|--------|------|
| `app.chat.context-max-messages` | `20` | 送给模型的历史消息条数，<=0 表示不裁剪 |
| `app.chat.context-max-chars` | `24000` | 送给模型的历史总字符预算，<=0 表示不限制 |

**说明**：
- 按条数 + 字符预算双重控制，控制 Prompt 长度
- 超预算时从最新往旧取，优先保留最近上下文
- 单条消息自身超预算时保留其尾部（至少让模型看到最近上下文）
- 历史接口仍返回全量/分页数据，裁剪只影响 AI 上下文

---

## 8. 会话锁模型说明

### 8.1 设计目的

避免同一会话反复切换模型导致"分布外上下文"污染、弱模型身份漂移。

### 8.2 锁模型规则

| 场景 | 行为 |
|------|------|
| 新会话（无锁定模型） | 把本次解析出的模型锁进会话 |
| 已有会话且传入模型 == 锁定模型 | 放行 |
| 已有会话且传入模型 != 锁定模型 | 报 409，提示"要换模型请新建会话" |

### 8.3 前端使用建议

- 发送消息前先 `GET /chat/sessions` 查看会话锁定的模型
- 用户想换模型时，提示"新建会话"而不是切模型
- 收到 409 错误时，引导用户新建会话

### 8.4 历史角色

- 历史消息以真实 `UserMessage` / `AssistantMessage` 角色送入模型（不拼进 system）
- 当前问题作为最后一条 `UserMessage`
- 安全边界：历史只作对话角色，绝不提升为系统指令（防提示注入）
- 旧消息缺少 model 时正常处理，不作为缓存失效条件

---

## 9. 模型输出净化说明

### 9.1 背景

部分模型（如 DeepSeek 的推理侧安全层）在特定输入下会把内部审核标签（形如 `<ds_safety>...</ds_safety>`）夹带进 `content`。正常 API 调用中这类标签不应出现在消息内容里。

### 9.2 净化策略

| 路径 | 处理 |
|------|------|
| 同步回复 | 落库前 `ModelOutputSanitizer.sanitize` 剥标签 |
| 流式回复 | `StreamingSafetyFilter` 跨 chunk 剥标签（标签可能被拆成 "<ds_sa" + "fety>"） |
| 标题生成 | `ModelOutputSanitizer.sanitizeTitle` 去安全标签 + HTML + 引号 + 换行 + 控制字符 |

### 9.3 行为说明

- 完整标签对：忽略大小写、支持跨行和标签内空白
- 残缺标签开头（只吐了开标签没吐闭合）：从该处到末尾全丢
- 清理后为空：用固定兜底文案"（模型回复被过滤，请稍后重试或换个问法）"，不存空消息
- 流式调用失败时兜底文案不写入历史（避免污染下一轮上下文）

### 9.4 前端影响

- 前端收到的回复内容已净化，不会看到 `<ds_safety>` 等内部标签
- 历史接口返回的 `content` 也是净化后的干净内容
- 无需前端额外过滤

---

## 10. 前端使用示例

### 10.1 登录流程

```javascript
const API_BASE = 'http://localhost:8080';

// 发送验证码
async function sendCode(phone) {
  const response = await fetch(`${API_BASE}/user/code?phone=${phone}`, {
    method: 'POST'
  });
  return await response.json();
}

// 验证码登录
async function loginByCode(phone, code) {
  const response = await fetch(`${API_BASE}/user/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ phone, code })
  });
  const result = await response.json();
  if (result.code === 200) {
    localStorage.setItem('token', result.data);
  }
  return result;
}

// 密码登录
async function loginByPassword(phone, password) {
  const response = await fetch(`${API_BASE}/user/login/password`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ phone, password })
  });
  const result = await response.json();
  if (result.code === 200) {
    localStorage.setItem('token', result.data);
  }
  return result;
}

// 获取当前用户（含 isNewUser）
async function getMe() {
  const token = localStorage.getItem('token');
  const response = await fetch(`${API_BASE}/user/me`, {
    headers: { 'Authorization': token }
  });
  return await response.json();
}

// 设置密码
async function setPassword(phone, code, password) {
  const token = localStorage.getItem('token');
  const response = await fetch(`${API_BASE}/user/set-password`, {
    method: 'PUT',
    headers: { 
      'Content-Type': 'application/json',
      'Authorization': token
    },
    body: JSON.stringify({ phone, code, password })
  });
  return await response.json();
}

// 修改昵称
async function updateProfile(name) {
  const token = localStorage.getItem('token');
  const response = await fetch(`${API_BASE}/user/profile`, {
    method: 'PUT',
    headers: { 
      'Content-Type': 'application/json',
      'Authorization': token
    },
    body: JSON.stringify({ name })
  });
  return await response.json();
}

// 登出
async function logout() {
  const token = localStorage.getItem('token');
  await fetch(`${API_BASE}/user/logout`, {
    method: 'POST',
    headers: { 'Authorization': token }
  });
  localStorage.removeItem('token');
}
```

### 10.2 新用户引导流程

```javascript
// 检查用户是否为新用户，如果是则引导设置密码
async function checkAndGuideNewUser() {
  const me = await getMe();
  if (me.code === 200 && me.data.isNewUser) {
    showPasswordSetupGuide(); // 显示设置密码引导
  }
}
```

### 10.3 模型选择

```javascript
// 获取可用模型（渲染下拉框）
async function getAvailableModels() {
  const response = await fetch(`${API_BASE}/chat/models`);
  const result = await response.json();
  if (result.code === 200) {
    return result.data; // ["qwen2.5-coder:7b", "deepseek-chat"]
  }
  return [];
}
```

### 10.4 聊天流程（含会话锁模型 + 分页 + 意图拦截）

```javascript
const API_BASE = 'http://localhost:8080';

// 从文案里提取 pendingId（命中意图时文案尾部带 [[MB:pending=42]]）
function extractPendingId(reply) {
  if (!reply) return null;
  const m = reply.match(/\[\[MB:pending=(\d+)\]\]/);
  return m ? Number(m[1]) : null;
}

// 展示文案时剥离标记
function stripMbMarker(reply) {
  return reply?.replace(/\[\[MB:pending=\d+\]\]/g, '').trim();
}

// 发送消息（同步，带模型 + 意图拦截）
async function sendMessage(sessionId, question, modelName) {
  const token = localStorage.getItem('token');
  const params = new URLSearchParams({ sessionid: sessionId, question });
  if (modelName) params.append('modelName', modelName);
  const response = await fetch(`${API_BASE}/chat/ask?${params}`, {
    method: 'POST',
    headers: { 'Authorization': token }
  });
  const result = await response.json();

  // 处理 409 会话锁模型冲突
  if (result.code === 409) {
    alert('该会话已锁定其他模型，要换模型请新建会话');
  }

  // 命中意图时：文案尾部带 [[MB:pending=N]] 标记
  // 用 pendingId 直接拉弹窗详情（不必再查 current）
  const pendingId = extractPendingId(result.data);
  if (pendingId != null) {
    await openConfirmPopup(pendingId);
  }
  // 展示时剥离标记
  displayMessage(stripMbMarker(result.data));
  return result;
}

// 按 pendingId 拉弹窗详情（命中意图时由 sendMessage 触发）
async function openConfirmPopup(pendingId) {
  const token = localStorage.getItem('token');
  const response = await fetch(`${API_BASE}/user/pending-actions/${pendingId}`, {
    headers: { 'Authorization': token }
  });
  const result = await response.json();
  if (result.code === 200 && result.data) {
    renderConfirmCard(result.data); // 含 memories[]/tasks[]/preview
    // 用户点确认 → confirmPending(pendingId)；点拒绝 → rejectPending(pendingId)
  }
  return result;
}

// 兜底：按会话查当前待确认动作（页面重新进入 / 恢复未处理的确认弹窗）
async function getCurrentPending(sessionId) {
  const token = localStorage.getItem('token');
  const response = await fetch(`${API_BASE}/user/pending-actions/current?sessionId=${sessionId}`, {
    headers: { 'Authorization': token }
  });
  const result = await response.json();
  if (result.code === 200 && result.data) {
    renderConfirmCard(result.data);
  }
  return result;
}

// 确认待确认动作
async function confirmPending(id) {
  const token = localStorage.getItem('token');
  const response = await fetch(`${API_BASE}/user/pending-actions/${id}/confirm`, {
    method: 'POST',
    headers: { 'Authorization': token }
  });
  return await response.json();
}

// 拒绝待确认动作
async function rejectPending(id) {
  const token = localStorage.getItem('token');
  const response = await fetch(`${API_BASE}/user/pending-actions/${id}/reject`, {
    method: 'POST',
    headers: { 'Authorization': token }
  });
  return await response.json();
}
```

### 10.5 用户任务（MindButler P1）

```javascript
// 获取到期待办
async function getDueTasks(limit = 50) {
  const token = localStorage.getItem('token');
  const response = await fetch(`${API_BASE}/user/tasks/due?limit=${limit}`, {
    headers: { 'Authorization': token }
  });
  return await response.json();
}

// 获取任务列表（游标分页）
async function getTasks(status = 'pending', beforeId, limit = 50) {
  const token = localStorage.getItem('token');
  const params = new URLSearchParams({ status, limit });
  if (beforeId) params.append('beforeId', beforeId);
  const response = await fetch(`${API_BASE}/user/tasks?${params}`, {
    headers: { 'Authorization': token }
  });
  return await response.json();
}

// 手动创建任务（兜底）
async function createTask(request) {
  const token = localStorage.getItem('token');
  const response = await fetch(`${API_BASE}/user/tasks`, {
    method: 'POST',
    headers: { 
      'Content-Type': 'application/json',
      'Authorization': token
    },
    body: JSON.stringify(request)
  });
  return await response.json();
}

// 完成任务（once → done；daily/weekly 滚动到下次）
async function completeTask(id, version) {
  const token = localStorage.getItem('token');
  const response = await fetch(`${API_BASE}/user/tasks/${id}/complete`, {
    method: 'POST',
    headers: { 
      'Content-Type': 'application/json',
      'Authorization': token
    },
    body: JSON.stringify({ version })
  });
  return await response.json();
}
```

### 10.6 用户记忆（MindButler P1）

```javascript
// 获取生效记忆
async function getMemories(limit = 100) {
  const token = localStorage.getItem('token');
  const response = await fetch(`${API_BASE}/user/memories?limit=${limit}`, {
    headers: { 'Authorization': token }
  });
  return await response.json();
}

// 导出全部记忆（隐私查看 / 迁移）
async function exportMemories() {
  const token = localStorage.getItem('token');
  const response = await fetch(`${API_BASE}/user/memories/export`, {
    headers: { 'Authorization': token }
  });
  return await response.json();
}

// 手动创建记忆
async function createMemory(request) {
  const token = localStorage.getItem('token');
  const response = await fetch(`${API_BASE}/user/memories`, {
    method: 'POST',
    headers: { 
      'Content-Type': 'application/json',
      'Authorization': token
    },
    body: JSON.stringify(request)
  });
  return await response.json();
}
```

### 10.7 Axios 封装

```javascript
import axios from 'axios';

const api = axios.create({
  baseURL: 'http://localhost:8080',
  timeout: 10000
});

// 请求拦截器
api.interceptors.request.use(
  config => {
    const token = localStorage.getItem('token');
    if (token) {
      config.headers.Authorization = token;
    }
    return config;
  },
  error => Promise.reject(error)
);

// 响应拦截器
api.interceptors.response.use(
  response => response.data,
  error => {
    if (error.response?.status === 401) {
      localStorage.removeItem('token');
      window.location.href = '/login';
    }
    return Promise.reject(error);
  }
);

export default api;
```

**说明**：使用 axios 时注意，后端错误响应（400/404/409/500）也会进入响应拦截器的 error 分支，
`error.response.data` 中即为 `Result` 格式的错误体。409 表示会话锁模型冲突 / 乐观锁冲突 / pending 状态冲突 / 重复记忆。

---

## 11. 认证说明

### 11.1 Token 机制

1. 登录成功后，服务端生成 UUID Token
2. Token 存储到 Redis，有效期 7 天
3. 后续请求需在请求头携带 Token：`Authorization: {token}`
4. 每次请求自动刷新 Token 过期时间（续期）
5. 登出时删除 Token

### 11.2 新用户标记机制

1. 首次通过验证码登录的用户会被标记为"新用户"
2. 新用户标记存储在 Redis，有效期 365 天
3. 新用户需要设置密码后才能使用密码登录
4. 设置密码后，新用户标记自动取消
5. 前端可通过 `GET /user/me` 的 `isNewUser` 字段或 `GET /user/is-new-user` 检查

### 11.3 安全特性

| 特性 | 说明 |
|------|------|
| 验证码冷却 | 同一手机号 60 秒内只能发一次 |
| 验证码一次性 | 校验通过后立即删除，防止重放 |
| 验证码日志脱敏 | 开发模式只打前 2 位，避免手机号 + 验证码成对泄露 |
| 密码强度 | 4-32 位字母、数字或下划线 |
| 手机号归属 | 设置密码时校验手机号属于当前账号 |
| 统一错误提示 | 密码登录不暴露手机号是否注册 |
| 参数长度校验 | sessionid≤64、question≤8000、title≤50、昵称≤20 |
| 模型白名单 | 只能选白名单内的模型，否则报 400 |
| 会话锁模型 | 已有会话切换模型报 409 |
| @Valid 校验 | 手机号格式 + 非空 |
| 模型输出净化 | 剥 `<ds_safety>` 标签，同步/流式/标题三路 |
| 历史角色真实化 | 历史作 User/Assistant 角色，不拼 system（防提示注入） |
| 线程安全 | 拦截器入口 + 出口双重清理 ThreadLocal，防 SSE/异步串用户 |
| 跨用户隔离 | MindButler P1 接口按当前用户隔离，跨用户访问统一 404（避免枚举） |
| 乐观锁 | 任务 / 记忆更新用 version 字段，不匹配报 409 |
| 待确认状态机 | pending 确认原子 + 幂等，重复确认返回 alreadyConfirmed |
| 敏感词过滤 | 记忆内容命中敏感词直接拒绝 |

### 11.4 拦截器说明

| 拦截器 | 功能 | 拦截路径 |
|--------|------|----------|
| RefreshTokenInterceptor | 入口清理 ThreadLocal，解析 Token，加载用户信息到 UserHolder，刷新过期时间；出口再清理 | `/**`（排除公开接口） |
| LoginInterceptor | 校验登录态，未登录返回 401；CORS 预检放行 | `/chat/**`, `/user/**`（排除公开接口） |

**公开接口**：
- `/user/code` - 发送验证码
- `/user/login` - 验证码登录
- `/user/login/password` - 密码登录
- `/chat/models` - 获取可用模型

---

## 12. CORS 说明

后端已配置 CORS，允许本地开发端口跨域：

| 配置项 | 值 |
|--------|-----|
| 允许来源 | `http://localhost:*`, `http://127.0.0.1:*` |
| 允许方法 | GET, POST, PUT, DELETE, OPTIONS |
| 允许头部 | `*` |
| 允许凭证 | true |
| 最大有效期 | 3600 秒 |

**说明**：前端开发时直接使用 `http://localhost:5173` 等端口访问 `http://localhost:8080` 即可，无需额外处理 CORS。

---

## 13. 本地开发说明

### 13.1 在线接口文档（Swagger / OpenAPI）

后端已接入 `springdoc-openapi`，提供在线接口文档：

| 地址 | 说明 |
|------|------|
| `http://localhost:8080/swagger-ui.html` | Swagger UI（交互式，可在线调试） |
| `http://localhost:8080/v3/api-docs` | OpenAPI 3.0 JSON 规范 |

**说明**：
- 所有 Controller 已加 `@Tag` + `@Operation` 注解，Swagger UI 里可看到中文说明
- 由 `springdoc.enabled` 开关控制（默认 true）
- 生产环境可用 `SPRINGDOC_ENABLED=false` 关掉，Swagger UI 和 OpenAPI 端点都不暴露
- 本地开发直接用 Swagger UI 联调接口即可，无需手写 curl

### 13.2 环境架构

```
Windows 宿主
├── 前端 Vite            http://localhost:5173
├── 后端 Spring Boot     http://localhost:8080
├── Ollama               127.0.0.1:11434   (qwen2.5-coder:7b)
├── DeepSeek             https://api.deepseek.com   (云端)
└── VirtualBox 虚拟机 studybox (Ubuntu 22.10)
    ├── MySQL   guest 3306  ── NAT 转发 ──> 宿主 13306
    ├── Redis   guest 6379  ── NAT 转发 ──> 宿主 16379
    ├── SSH     guest 22    ── NAT 转发 ──> 宿主 2222
    └── nginx   guest 80    ── NAT 转发 ──> 宿主 8082
```

### 13.3 启动步骤

1. 复制并填写本地敏感配置：`cp src/main/resources/application-local.properties.example src/main/resources/application-local.properties`
2. 使用 DeepSeek 时配置 key：`export DEEPSEEK_API_KEY=sk-xxx`
3. 启动虚拟机（必须先启动）
4. 初始化数据库：`mysql -h 127.0.0.1 -P 13306 -uroot -p1234 < docs/schema.sql`
5. 旧库执行迁移（按顺序）：
   - `docs/migration/2026-10-22-fix-tb-user-password-null.sql`
   - `docs/migration/2026-10-28-add-chat-message-model.sql`
   - `docs/migration/2026-10-28-add-chat-session-model.sql`
   - `docs/migration/2026-11-02-mindbutler-p1.sql` ⭐
   - `docs/migration/2026-11-03-mindbutler-p1-memory-session.sql` ⭐ P1.1（tb_user_memory 加 session_id）
6. 确认 Ollama：`ollama list`（需有 qwen2.5-coder:7b）
7. 启动后端：`mvn spring-boot:run`
8. 启动前端：`npm install && npm run dev`

详细步骤见 `docs/本地启动.md`。

### 13.4 配置文件

- `application.properties` - 默认配置（支持环境变量覆盖）
- `application-local.properties` - 本地敏感配置（密码，已 gitignore）

**说明**：本地开发需在 `application-local.properties` 里显式设 `app.sms.dev-mode=true`，
否则默认关闭（生产安全），验证码不会打印到日志。

### 13.5 MindButler P1 配置项

| 配置 | 默认值 | 说明 |
|------|--------|------|
| `app.mindbutler.enabled` | `true` | 总开关；关闭后只保留手动 CRUD，不做意图提取/注入 |
| `app.mindbutler.intent.enabled` | `true` | 意图提取开关；关闭后聊天不拦截（手动 CRUD 仍可用） |
| `app.mindbutler.intent.extract-model` | `deepseek-chat` | 意图提取器模型（结构化 JSON 能力更强） |
| `app.mindbutler.intent.timeout-ms` | `5000` | 提取超时（0=不限） |
| `app.mindbutler.intent.daily-limit` | `50` | 每用户每日提取次数（0=不限） |
| `app.mindbutler.intent.max-memories` | `5` | 单条消息最多记忆候选（超出截断） |
| `app.mindbutler.intent.max-tasks` | `5` | 单条消息最多任务候选（超出截断） |
| `app.mindbutler.memory.dynamic-ttl-days` | `90` | dynamic 记忆默认有效期；stable 不过期 |
| `app.mindbutler.memory.max-per-user` | `100` | 每用户 active 记忆上限 |
| `app.mindbutler.memory.inject-max-stable` | `5` | 注入 stable 画像上限 |
| `app.mindbutler.memory.inject-max-dynamic` | `5` | 注入 dynamic 画像上限 |
| `app.mindbutler.memory.inject-max-chars` | `1200` | 注入记忆总字符预算 |
| `app.mindbutler.task.zone` | `Asia/Shanghai` | P1 固定时区（启动校验只允许此值） |
| `app.mindbutler.task.max-per-user` | `500` | 每用户未删除任务上限 |
| `app.mindbutler.task.due-limit` | `50` | `GET /user/tasks/due` 默认/最大条数 |
| `app.mindbutler.pending.ttl-minutes` | `10` | 待确认动作有效期（过期需重新发起） |
| `app.mindbutler.pending.payload-max-bytes` | `16384` | 待确认动作 payload 上限（字节） |
| `app.mindbutler.eval.real-model` | `false` | M4 评测 runner：false=桩模式（100% 确定性），true=真跑 LLM |

---

*文档由项目自动生成，如有变更请及时更新*
