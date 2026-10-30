# AI Chat 后端接口文档

> 文档版本：v6.1  
> 最后更新：2026-10-28  
> 基础路径：`http://localhost:8080`  
> 变更记录：v6.1 - 会话锁模型 + 带发言人标注的历史 + 消息/会话返回 model 字段 + 409 错误码

---

## 目录
1. [统一响应格式](#1-统一响应格式)
2. [用户接口](#2-用户接口)
3. [聊天接口](#3-聊天接口)
4. [错误码说明](#4-错误码说明)
5. [数据存储说明](#5-数据存储说明)
6. [多模型说明](#6-多模型说明)
7. [会话锁模型说明](#7-会话锁模型说明)
8. [前端使用示例](#8-前端使用示例)
9. [认证说明](#9-认证说明)
10. [CORS 说明](#10-cors-说明)
11. [本地开发说明](#11-本地开发说明)

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

获取 AI 回复，同时保存对话历史（MySQL 持久化 + Redis 缓存）。

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
- 只把最近 N 条历史送给模型（默认 20，`app.chat.context-max-messages` 可调）
- AI 标题异步生成（不阻塞主流程），只有标题仍是兜底值时才覆盖
- 会话锁模型：新会话锁到本次传入的模型；已有会话若传入模型与锁定模型不一致 → 409

---

### 3.3 发送消息（流式）

使用 SSE（Server-Sent Events）流式返回 AI 回复，实现打字机效果。

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
- 每条 AI 回复完整后落库（MySQL + Redis，含模型名）
- 模型名校验失败或会话锁模型冲突时报 400/409，不会进入流式

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
    },
    {
      "sessionId": "s002",
      "title": "Java 多线程编程",
      "model": "qwen2.5-coder:7b"
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

#### 请求示例

全量历史（兼容旧行为）：
```bash
GET /chat/history?sessionid=s001
Authorization: 550e8400-e29b-41d4-a716-446655440000
```

分页（取最新 20 条）：
```bash
GET /chat/history?sessionid=s001&limit=20
Authorization: 550e8400-e29b-41d4-a716-446655440000
```

分页（取更早一页，游标为上一页最小的 id）：
```bash
GET /chat/history?sessionid=s001&beforeId=100&limit=20
Authorization: 550e8400-e29b-41d4-a716-446655440000
```

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
- `content`: 消息内容
- `model`: 本轮使用的模型名（model 列上线前的历史消息为 null）
- `timestamp`: 时间戳（毫秒）
- 不传 `limit` 时返回全量历史（现有前端无需改动）
- `beforeId` 必须配 `limit` 使用，否则报错
- `limit` 范围 1-100
- 分页查询不回写缓存（避免把"一页"当成全量历史）
- 响应顺序：`beforeId` 之前的最近 `limit` 条，按 id 升序

#### 错误响应

```json
{
  "code": 400,
  "message": "beforeId 需要配合 limit 使用",
  "data": null
}
```

```json
{
  "code": 400,
  "message": "limit 必须在 1-100 之间",
  "data": null
}
```

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

## 4. 错误码说明

| 错误码 | 说明 | 处理建议 |
|--------|------|----------|
| 200 | 请求成功 | - |
| 400 | 请求参数错误 | 检查参数格式、长度、模型名 |
| 401 | 未登录 | 请先登录 |
| 404 | 接口不存在 | 检查路径 |
| 409 | 会话锁模型冲突 | 该会话已锁定某模型，切换模型请新建会话 |
| 413 | 上传文件过大 | 检查文件大小 |
| 500 | 系统错误 | AI 服务不可用或服务器异常 |

---

## 5. 数据存储说明

### 5.1 存储职责

| 存储 | 角色 | 内容 |
|------|------|------|
| MySQL | 持久化主存储 | `tb_user`、`tb_chat_session`、`tb_chat_message` |
| Redis | 7 天热缓存 | 消息 List、会话 ZSet、标题 Hash、锁模型 Hash、Token、验证码 |

**原则**：先写 MySQL，再更新 Redis；读 Redis 未命中时回源 MySQL 并回填。  
Redis 故障或过期不影响数据正确性，只影响性能。

### 5.2 数据库表结构

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
  model VARCHAR(64) DEFAULT NULL,  -- 会话锁定的模型
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
  model VARCHAR(64),  -- 本轮使用的模型名
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_user_session (user_id, session_id, id)
);
```

完整脚本见 `docs/schema.sql`。

### 5.3 Redis 缓存结构

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

**消息结构（SMessage）**：
```json
{
  "id": 100,
  "text": "消息内容",
  "type": "user|assistant|system",
  "model": "qwen2.5-coder:7b",
  "timestamp": 1727356800000
}
```

**说明**：
- `model` 为 null 表示该消息产生于 model 列上线之前（历史数据）
- 标题和锁模型统一用 Hash 结构（key 到 userId，field 用 sessionId）

### 5.4 缓存一致性策略

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

## 6. 多模型说明

### 6.1 模型路由

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

### 6.2 配置项

| 配置 | 默认值 | 说明 |
|------|--------|------|
| `app.models.available` | `qwen2.5-coder:7b,deepseek-chat` | 模型白名单（逗号分隔） |
| `app.models.default-model` | `qwen2.5-coder:7b` | 默认模型 |
| `app.models.ollama-models` | `qwen2.5-coder:7b` | 走 Ollama 的模型，其余走 OpenAI 兼容协议 |

### 6.3 环境变量

| 变量 | 说明 |
|------|------|
| `DEEPSEEK_API_KEY` | DeepSeek API Key（不提交 Git） |
| `DEEPSEEK_BASE_URL` | DeepSeek API 地址（默认 `https://api.deepseek.com`） |
| `OLLAMA_BASE_URL` | Ollama 服务地址（默认 `http://localhost:11434`） |
| `OLLAMA_MODEL` | 默认 Ollama 模型 |

### 6.4 上下文裁剪

| 配置 | 默认值 | 说明 |
|------|--------|------|
| `app.chat.context-max-messages` | `20` | 送给模型的历史消息条数，<=0 表示不裁剪 |

**说明**：
- 只把最近 N 条历史送给模型，控制 Prompt 长度
- 历史接口仍返回全量/分页数据，裁剪只影响 AI 上下文
- 长对话不会撑爆 Prompt

---

## 7. 会话锁模型说明 ⭐ 新增

### 7.1 设计目的

避免同一会话反复切换模型导致"分布外上下文"污染、弱模型身份漂移。

### 7.2 锁模型规则

| 场景 | 行为 |
|------|------|
| 新会话（无锁定模型） | 把本次解析出的模型锁进会话 |
| 已有会话且传入模型 == 锁定模型 | 放行 |
| 已有会话且传入模型 != 锁定模型 | 报 409，提示"要换模型请新建会话" |

### 7.3 前端使用建议

- 发送消息前先 `GET /chat/sessions` 查看会话锁定的模型
- 用户想换模型时，提示"新建会话"而不是切模型
- 收到 409 错误时，引导用户新建会话

### 7.4 带发言人标注的历史

多模型切换时，同一会话的历史可能来自不同模型。后端把每条历史标注发言人，喂给当前模型时让其区分"哪些是我说的、哪些是别的模型说的"：

```
发言人: qwen2.5-coder:7b（其他模型）
  用户: 写个快排
发言人: qwen2.5-coder:7b（其他模型）
  助手: public void quickSort...
发言人: deepseek-chat（我）
  用户: 那热门模型呢
发言人: deepseek-chat（我）
  助手: 当前热门的是...
```

**说明**：
- 共享上下文（跨模型不断裂，指代能对上）
- 不认领错误（不把别的模型的话当成自己的记忆/结论）
- 旧消息缺少 model 时标记为"未知模型"
- 弱模型防呆：明确告诉"其他模型的发言不是你说的话，仅供参考"

---

## 8. 前端使用示例

### 8.1 登录流程

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

### 8.2 新用户引导流程

```javascript
// 检查用户是否为新用户，如果是则引导设置密码
async function checkAndGuideNewUser() {
  const me = await getMe();
  if (me.code === 200 && me.data.isNewUser) {
    showPasswordSetupGuide(); // 显示设置密码引导
  }
}

// 新用户设置密码流程
async function setupPassword(phone, code, password) {
  const result = await setPassword(phone, code, password);
  if (result.code === 200) {
    await getMe(); // 刷新用户信息
  }
  return result;
}
```

### 8.3 模型选择

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

### 8.4 聊天流程（含会话锁模型 + 分页）

```javascript
// 发送消息（同步，带模型）
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
    // 可引导用户新建会话
  }
  return result;
}

// 流式发送消息（带模型）
async function streamSendMessage(sessionId, question, modelName, onChunk, onComplete) {
  const token = localStorage.getItem('token');
  const params = new URLSearchParams({ sessionid: sessionId, question });
  if (modelName) params.append('modelName', modelName);
  
  const response = await fetch(`${API_BASE}/chat/ask-stream?${params}`, {
    headers: { 'Authorization': token }
  });
  
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';

  while (true) {
    const { done, value } = await reader.read();
    if (done) break;

    buffer += decoder.decode(value, { stream: true });
    const lines = buffer.split('\n');
    buffer = lines.pop();

    for (const line of lines) {
      if (line.startsWith('data: ')) {
        const data = line.slice(6);
        if (data === '[DONE]') {
          onComplete();
          return;
        }
        onChunk(data);
      }
    }
  }
}

// 获取会话列表（含锁模型）
async function getSessions() {
  const token = localStorage.getItem('token');
  const response = await fetch(`${API_BASE}/chat/sessions`, {
    headers: { 'Authorization': token }
  });
  return await response.json();
  // 返回的 data 里每项含 model 字段，可用于展示"该会话用的什么模型"
}

// 获取会话历史（全量，兼容旧行为，含 model 字段）
async function getHistory(sessionId) {
  const token = localStorage.getItem('token');
  const response = await fetch(`${API_BASE}/chat/history?sessionid=${sessionId}`, {
    headers: { 'Authorization': token }
  });
  return await response.json();
}

// 获取会话历史（分页：取最新一页）
async function getHistoryLatest(sessionId, limit = 20) {
  const token = localStorage.getItem('token');
  const response = await fetch(`${API_BASE}/chat/history?sessionid=${sessionId}&limit=${limit}`, {
    headers: { 'Authorization': token }
  });
  return await response.json();
}

// 获取会话历史（分页：取更早一页，游标为上一页最小的 id）
async function getHistoryBefore(sessionId, beforeId, limit = 20) {
  const token = localStorage.getItem('token');
  const response = await fetch(`${API_BASE}/chat/history?sessionid=${sessionId}&beforeId=${beforeId}&limit=${limit}`, {
    headers: { 'Authorization': token }
  });
  return await response.json();
}

// 更新会话标题
async function updateTitle(sessionId, title) {
  const token = localStorage.getItem('token');
  const response = await fetch(`${API_BASE}/chat/title?sessionid=${sessionId}&title=${encodeURIComponent(title)}`, {
    method: 'PUT',
    headers: { 'Authorization': token }
  });
  return await response.json();
}

// 清空会话
async function clearSession(sessionId) {
  const token = localStorage.getItem('token');
  const response = await fetch(`${API_BASE}/chat/clear-session?sessionid=${sessionId}`, {
    method: 'DELETE',
    headers: { 'Authorization': token }
  });
  return await response.json();
}
```

### 8.5 会话模型展示（前端 UI 建议）

```javascript
// 渲染会话列表时展示锁定的模型
async function renderSessionList() {
  const result = await getSessions();
  const sessions = result.data;
  
  sessions.forEach(s => {
    const modelLabel = s.model || '未锁定';
    // 显示 "s.title - modelLabel"
    // 如果用户想换模型，提示新建会话
  });
}
```

### 8.6 Axios 封装

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
`error.response.data` 中即为 `Result` 格式的错误体。409 表示会话锁模型冲突。

---

## 9. 认证说明

### 9.1 Token 机制

1. 登录成功后，服务端生成 UUID Token
2. Token 存储到 Redis，有效期 7 天
3. 后续请求需在请求头携带 Token：`Authorization: {token}`
4. 每次请求自动刷新 Token 过期时间（续期）
5. 登出时删除 Token

### 9.2 新用户标记机制

1. 首次通过验证码登录的用户会被标记为"新用户"
2. 新用户标记存储在 Redis，有效期 365 天
3. 新用户需要设置密码后才能使用密码登录
4. 设置密码后，新用户标记自动取消
5. 前端可通过 `GET /user/me` 的 `isNewUser` 字段或 `GET /user/is-new-user` 检查

### 9.3 安全特性

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

### 9.4 拦截器说明

| 拦截器 | 功能 | 拦截路径 |
|--------|------|----------|
| RefreshTokenInterceptor | 解析 Token，加载用户信息到 UserHolder，刷新过期时间 | `/**`（排除公开接口） |
| LoginInterceptor | 校验登录态，未登录返回 401；CORS 预检放行 | `/chat/**`, `/user/**`（排除公开接口） |

**公开接口**：
- `/user/code` - 发送验证码
- `/user/login` - 验证码登录
- `/user/login/password` - 密码登录
- `/chat/models` - 获取可用模型

---

## 10. CORS 说明

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

## 11. 本地开发说明

### 11.1 环境架构

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

### 11.2 启动步骤

1. 复制并填写本地敏感配置：`cp src/main/resources/application-local.properties.example src/main/resources/application-local.properties`
2. 使用 DeepSeek 时配置 key：`export DEEPSEEK_API_KEY=sk-xxx`
3. 启动虚拟机（必须先启动）
4. 初始化数据库：`mysql -h 127.0.0.1 -P 13306 -uroot -p1234 < docs/schema.sql`
5. 旧库执行迁移（按顺序）：
   - `docs/migration/2026-10-22-fix-tb-user-password-null.sql`
   - `docs/migration/2026-10-28-add-chat-message-model.sql`
   - `docs/migration/2026-10-28-add-chat-session-model.sql`
6. 确认 Ollama：`ollama list`（需有 qwen2.5-coder:7b）
7. 启动后端：`mvn spring-boot:run`
8. 启动前端：`npm install && npm run dev`

详细步骤见 `docs/本地启动.md`。

### 11.3 配置文件

- `application.properties` - 默认配置（支持环境变量覆盖）
- `application-local.properties` - 本地敏感配置（密码，已 gitignore）

**说明**：本地开发需在 `application-local.properties` 里显式设 `app.sms.dev-mode=true`，
否则默认关闭（生产安全），验证码不会打印到日志。

---

*文档由项目自动生成，如有变更请及时更新*
