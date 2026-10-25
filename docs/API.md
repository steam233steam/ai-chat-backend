# AI Chat 后端接口文档

> 文档版本：v5.1  
> 最后更新：2026-10-25  
> 基础路径：`http://localhost:8080`  
> 变更记录：v5.1 - 聊天数据持久化改造（MySQL 主存储 + Redis 缓存），接口行为不变，新增数据存储说明

---

## 目录
1. [统一响应格式](#1-统一响应格式)
2. [用户接口](#2-用户接口)
3. [聊天接口](#3-聊天接口)
4. [错误码说明](#4-错误码说明)
5. [数据存储说明](#5-数据存储说明)
6. [前端使用示例](#6-前端使用示例)
7. [认证说明](#7-认证说明)
8. [CORS 说明](#8-cors-说明)

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
| code | Integer | 状态码，200 成功，400/401/500 失败 |
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
- 开发模式下验证码打印在日志中，不直接返回

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

> 前缀：`/chat`，所有接口需要登录，请求头携带 Token：`Authorization: {token}`

### 3.1 发送消息（同步）

获取 AI 回复，同时保存对话历史（MySQL 持久化 + Redis 缓存）。

- **接口地址**：`POST /chat/ask`
- **Content-Type**：`application/x-www-form-urlencoded`

#### 请求参数

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| sessionid | String | 是 | 会话 ID（≤64 字符） |
| question | String | 是 | 用户消息内容（≤8000 字符） |

#### 请求示例

```bash
POST /chat/ask?sessionid=s001&question=你好
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
  "code": 500,
  "message": "AI 服务暂时不可用，请稍后重试",
  "data": null
}
```

**说明**：
- 首次发消息时自动创建会话并生成默认标题（取问题前 20 个字符）
- 会话列表按最近活跃时间倒序排列

---

### 3.2 发送消息（流式）⭐

使用 SSE（Server-Sent Events）流式返回 AI 回复，实现打字机效果。

- **接口地址**：`GET /chat/ask-stream`
- **Content-Type**：`text/event-stream`

#### 请求参数

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| sessionid | String | 是 | 会话 ID（≤64 字符） |
| question | String | 是 | 用户消息内容（≤8000 字符） |

#### 请求示例

```bash
GET /chat/ask-stream?sessionid=s001&question=写一个快速排序
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
- 每条 AI 回复完整后落库（MySQL + Redis）

---

### 3.3 获取会话列表

获取当前用户的所有会话列表（按最近活跃时间倒序）。

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
      "title": "你好，请介绍..."
    },
    {
      "sessionId": "s002",
      "title": "Java 多线程编程"
    }
  ]
}
```

**说明**：Redis 缓存过期后会话列表自动从 MySQL 恢复，数据不会丢失。

---

### 3.4 获取会话历史

获取指定会话的所有消息历史，包含时间戳。

- **接口地址**：`GET /chat/history`
- **请求头**：`Authorization: {token}`

#### 请求参数

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| sessionid | String | 是 | 会话 ID |

#### 响应示例

```json
{
  "code": 200,
  "message": "成功",
  "data": [
    {
      "role": "user",
      "content": "你好，请介绍一下你自己",
      "timestamp": 1727356800000
    },
    {
      "role": "assistant",
      "content": "你好！我是一个乐于助人的 AI 助手...",
      "timestamp": 1727356805000
    }
  ]
}
```

**说明**：
- `role`: 消息角色（user/assistant）
- `content`: 消息内容
- `timestamp`: 时间戳（毫秒）
- Redis 缓存未命中时自动从 MySQL 回源（按自增 id 稳定排序）

---

### 3.5 更新会话标题

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

**说明**：会话不存在时会自动创建一条会话记录（title 不会被空标题覆盖已有标题）。

---

### 3.6 清空会话历史

删除指定会话的所有历史记录（MySQL + Redis）。

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

**说明**：先删 MySQL（消息 + 会话元数据），再清 Redis 缓存。

---

## 4. 错误码说明

| 错误码 | 说明 | 处理建议 |
|--------|------|----------|
| 200 | 请求成功 | - |
| 400 | 请求参数错误 | 检查参数格式和长度 |
| 401 | 未登录 | 请先登录 |
| 500 | 系统错误 | AI 服务不可用或服务器异常 |

---

## 5. 数据存储说明 ⭐ 2026-10-22 更新

### 5.1 存储职责

| 存储 | 角色 | 内容 |
|------|------|------|
| MySQL | 持久化主存储 | `tb_user`、`tb_chat_session`、`tb_chat_message` |
| Redis | 7 天热缓存 | 消息 List、会话 ZSet、标题 Hash、Token、验证码 |

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

-- 会话表（唯一键 user_id + session_id）
CREATE TABLE tb_chat_session (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id VARCHAR(32) NOT NULL,
  session_id VARCHAR(64) NOT NULL,
  title VARCHAR(100) DEFAULT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_user_session (user_id, session_id),
  KEY idx_user_update (user_id, update_time)
);

-- 消息表（自增 id 保证稳定排序）
CREATE TABLE tb_chat_message (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id VARCHAR(32) NOT NULL,
  session_id VARCHAR(64) NOT NULL,
  role VARCHAR(16) NOT NULL,
  content TEXT NOT NULL,
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
| `aichat:chat:session:{userId}:{sessionId}` | List | 消息历史（含时间戳） | 7 天 |
| `aichat:chat:title:{userId}:{sessionId}` | Hash | 会话标题 | 7 天 |

**消息结构（SMessage）**：
```json
{
  "text": "消息内容",
  "type": "user|assistant|system",
  "timestamp": 1727356800000
}
```

### 5.4 缓存一致性策略

| 场景 | 处理 |
|------|------|
| Redis 缓存未命中 | 回源 MySQL 并回填缓存 |
| Redis 写入失败 | 主动失效相关缓存，降级使用 MySQL |
| 缓存回填失败 | 删除不完整缓存，避免脏数据 |
| 会话列表缓存过期 | 从 MySQL 整体重建（避免列表不完整） |
| 清空会话 | 先删 MySQL 再清 Redis |

---

## 6. 前端使用示例

### 6.1 登录流程

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
    localStorage.getItem('token', result.data);
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

### 6.2 新用户引导流程

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

### 6.3 聊天流程

```javascript
// 发送消息（同步）
async function sendMessage(sessionId, question) {
  const token = localStorage.getItem('token');
  const response = await fetch(`${API_BASE}/chat/ask?sessionid=${sessionId}&question=${encodeURIComponent(question)}`, {
    method: 'POST',
    headers: { 'Authorization': token }
  });
  return await response.json();
}

// 流式发送消息
async function streamSendMessage(sessionId, question, onChunk, onComplete) {
  const token = localStorage.getItem('token');
  const response = await fetch(`${API_BASE}/chat/ask-stream?sessionid=${sessionId}&question=${encodeURIComponent(question)}`, {
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

// 获取会话列表
async function getSessions() {
  const token = localStorage.getItem('token');
  const response = await fetch(`${API_BASE}/chat/sessions`, {
    headers: { 'Authorization': token }
  });
  return await response.json();
}

// 获取会话历史（含时间戳）
async function getHistory(sessionId) {
  const token = localStorage.getItem('token');
  const response = await fetch(`${API_BASE}/chat/history?sessionid=${sessionId}`, {
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

### 6.4 Axios 封装

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

**说明**：使用 axios 时注意，后端错误响应（400/500）也会进入响应拦截器的 error 分支，
`error.response.data` 中即为 `Result` 格式的错误体。

---

## 7. 认证说明

### 7.1 Token 机制

1. 登录成功后，服务端生成 UUID Token
2. Token 存储到 Redis，有效期 7 天
3. 后续请求需在请求头携带 Token：`Authorization: {token}`
4. 每次请求自动刷新 Token 过期时间（续期）
5. 登出时删除 Token

### 7.2 新用户标记机制

1. 首次通过验证码登录的用户会被标记为"新用户"
2. 新用户标记存储在 Redis，有效期 365 天
3. 新用户需要设置密码后才能使用密码登录
4. 设置密码后，新用户标记自动取消
5. 前端可通过 `GET /user/me` 的 `isNewUser` 字段或 `GET /user/is-new-user` 检查

### 7.3 安全特性

| 特性 | 说明 |
|------|------|
| 验证码冷却 | 同一手机号 60 秒内只能发一次 |
| 验证码一次性 | 校验通过后立即删除，防止重放 |
| 密码强度 | 4-32 位字母、数字或下划线 |
| 手机号归属 | 设置密码时校验手机号属于当前账号 |
| 统一错误提示 | 密码登录不暴露手机号是否注册 |
| 参数长度校验 | sessionid≤64、question≤8000、title≤50、昵称≤20 |

### 7.4 拦截器说明

| 拦截器 | 功能 | 拦截路径 |
|--------|------|----------|
| RefreshTokenInterceptor | 解析 Token，加载用户信息到 UserHolder，刷新过期时间 | `/**`（排除公开接口） |
| LoginInterceptor | 校验登录态，未登录返回 401；CORS 预检放行 | `/chat/**`, `/user/**`（排除公开接口） |

**公开接口**：
- `/user/code` - 发送验证码
- `/user/login` - 验证码登录
- `/user/login/password` - 密码登录

---

## 8. CORS 说明

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

## 9. 本地开发说明

### 9.1 环境架构

```
Windows 宿主
├── 前端 Vite            http://localhost:5173
├── 后端 Spring Boot     http://localhost:8080
├── Ollama               127.0.0.1:11434   (qwen2.5-coder:7b)
└── VirtualBox 虚拟机 studybox (Ubuntu 22.10)
    ├── MySQL   guest 3306  ── NAT 转发 ──> 宿主 13306
    ├── Redis   guest 6379  ── NAT 转发 ──> 宿主 16379
    ├── SSH     guest 22    ── NAT 转发 ──> 宿主 2222
    └── nginx   guest 80    ── NAT 转发 ──> 宿主 8082
```

### 9.2 启动步骤

1. 启动虚拟机（必须先启动）
2. 初始化数据库：`mysql -h 127.0.0.1 -P 13306 -uroot -p1234 < docs/schema.sql`
3. 旧库执行迁移：`mysql ... < docs/migration/2026-10-22-fix-tb-user-password-null.sql`
4. 确认 Ollama：`ollama list`（需有 qwen2.5-coder:7b）
5. 启动后端：`mvn -DskipTests package && java -Dfile.encoding=UTF-8 -jar target/aichat-0.0.1-SNAPSHOT.jar`
6. 启动前端：`npm install && npm run dev`

详细步骤见 `docs/本地启动.md`。

### 9.3 配置文件

- `application.properties` - 默认配置（支持环境变量覆盖）
- `application-local.properties` - 本地敏感配置（密码，已 gitignore）

---

*文档由项目自动生成，如有变更请及时更新*
