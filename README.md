[![Backend CI](https://github.com/steam233steam/ai-chat-backend/actions/workflows/ci.yml/badge.svg)](https://github.com/steam233steam/ai-chat-backend/actions/workflows/ci.yml)

# AI Chat 后端

基于 Spring Boot 3 + Spring AI 的智能对话后端，接入 Ollama 本地模型，支持同步/流式两种响应模式。

> 前端仓库：[ai-chat-frontend](https://github.com/steam233steam/ai-chat-frontend)

## 技术栈

Spring Boot 3.2.5 · Spring AI 1.0.0-M5 (Ollama) · MySQL · MyBatis · Redis · Hutool · Java 17

## 功能特性

- **双方式登录**：手机验证码登录 + 密码登录（BCrypt 加密）
- **新用户引导**：首次登录自动标记，需完善密码/昵称
- **多轮对话**：Redis 管理会话上下文，支持流式 SSE 输出
- **多用户隔离**：会话数据按 userId 隔离，含越权校验
- **工程化**：三层架构 + 全局异常处理 + 拦截器链 + 参数校验

## 快速启动

**依赖服务**：MySQL (aichat 库) · Redis · Ollama

```bash
# 复制并填写本地密码
cp src/main/resources/application-local.properties.example src/main/resources/application-local.properties

# 启动
mvn spring-boot:run
# 服务端口 8080，CORS 已放行本地开发端口
```

完整启动步骤见 `docs/本地启动.md`，建库脚本见 `docs/schema.sql`。

## API 一览

### 用户接口 `/user`
| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/user/code` | 发送验证码（60s 冷却） |
| POST | `/user/login` | 验证码登录 |
| POST | `/user/login/password` | 密码登录 |
| PUT | `/user/set-password` | 设置密码 |
| PUT | `/user/profile` | 修改昵称 |
| GET | `/user/me` | 获取当前用户 |
| GET | `/user/is-new-user` | 检查是否新用户 |
| POST | `/user/logout` | 登出 |

### 聊天接口 `/chat`
| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/chat/ask` | 同步对话 |
| GET | `/chat/ask-stream` | 流式对话 (SSE) |
| GET | `/chat/history` | 会话历史 |
| GET | `/chat/sessions` | 会话列表 |
| PUT | `/chat/title` | 更新标题 |
| DELETE | `/chat/clear-session` | 清空会话 |

完整接口文档见 `docs/API.md`。

## 架构设计

```
Controller  →  Service  →  Repository / Mapper  →  Redis / MySQL
     │
     └── 拦截器链：RefreshTokenInterceptor → LoginInterceptor
```

- **Controller**：接收请求、参数校验、路由
- **Service**：业务逻辑（用户认证、对话流程、越权校验）
- **Repository**：Redis 数据访问（会话、Token、验证码、新用户标记）
- **Mapper**：MyBatis 数据库访问（用户表）
- **拦截器链**：自动解析 Token → 填充 UserHolder → 校验登录态

## 安全设计

- **密码加密**：BCrypt 自动加盐，明文不落库
- **Token 管理**：Redis Hash 存储，7 天 TTL，每次请求自动续期
- **会话隔离**：所有会话 Key 带 userId 前缀，操作前校验归属
- **参数校验**：手机号/密码正则、长度限制、手机号归属校验
- **防重放**：验证码一次性，校验后立即删除；60s 发送冷却
- **全局异常**：BusinessException + GlobalExceptionHandler，统一 JSON 响应

## Redis Key 设计

| Key | 类型 | TTL | 说明 |
|-----|------|-----|------|
| `aichat:login:code:{phone}` | String | 5 分钟 | 登录验证码 |
| `aichat:login:token:{token}` | Hash | 7 天 | 用户信息 (id/name/phone) |
| `aichat:chat:sessions:{userId}` | ZSet | 7 天 | 用户会话列表 |
| `aichat:chat:session:{userId}:{sessionId}` | List | 7 天 | 会话消息 |
| `aichat:chat:title:{userId}:{sessionId}` | Hash | 7 天 | 会话标题 |
| `aichat:user:new:{uid}` | String | 365 天 | 新用户标记 |

## 项目结构

```
com.aichat/
├── Config/          # 拦截器、CORS、ChatClient、Redis 配置
├── Controller/      # 用户 + 聊天接口
├── Dto/             # 实体、DTO、统一返回
├── Exception/       # 业务异常 + 全局异常处理器
├── Mapper/          # MyBatis 用户表操作
├── Repository/      # Redis 数据访问（会话、用户）
├── Service/         # 业务逻辑（用户认证、对话流程）
└── utils/           # ThreadLocal、加密、正则、Redis 常量
```

## CI

GitHub Actions 在每次 push 时执行单元测试与打包（`.github/workflows/ci.yml`）。

`AichatApplicationTests` 是 `@SpringBootTest`，启动需要真实的 Redis / MySQL / Ollama，因此未纳入 CI；CI 只跑不依赖外部服务的单元测试。

## 文档

- `docs/API.md` — 接口文档
- `docs/schema.sql` — 数据库脚本
- `docs/本地启动.md` — 本地运行指南
- `docs/学习笔记.md` — 开发笔记
- `PROJECT_STATUS.md` — 开发状态记录
