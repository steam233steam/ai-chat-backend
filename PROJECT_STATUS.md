# AI Chat 项目开发状态

## 项目概览
- **项目名称**: aichat
- **技术栈**: Spring Boot 3.2.5 + Spring AI (1.0.0-M5) + Redis + Ollama + MySQL + MyBatis
- **Java版本**: 17
- **工作目录**: D:\maven-project\aichat
- **开始开发日期**: 2026-09-12
- **当前阶段**: 用户认证系统 + 聊天功能 + 全局异常处理 + 安全加固完成 ✅

## 架构分层

```
com.aichat/
├── AichatApplication.java          # 启动类
├── Config/
│   ├── RedisConfig.java            # Redis 配置
│   ├── ChatClientConfig.java       # ChatClient Bean 配置（统一系统提示词）
│   ├── WebMvcConfig.java           # MVC 配置（拦截器 + CORS）
│   ├── LoginInterceptor.java       # 登录拦截器
│   └── RefreshTokenInterceptor.java # Token 刷新拦截器
├── Controller/
│   ├── ChatController.java         # 聊天接口（/chat 前缀 + 参数校验）
│   └── UserController.java         # 用户接口（/user 前缀）
├── Dto/
│   ├── Result.java                 # 统一返回结果（支持自定义 code）
│   ├── SMessage.java              # 消息实体（含时间戳，去 Lombok）
│   ├── User.java                   # 用户实体
│   ├── UserDto.java               # 用户 DTO（含 isNewUser 字段）
│   └── LoginFormDTO.java          # 登录表单 DTO
├── Exception/
│   ├── BusinessException.java      # 业务异常（含错误码）
│   └── GlobalExceptionHandler.java # 全局异常处理器
├── Mapper/
│   └── UserMapper.java             # MyBatis Mapper
├── Repository/
│   ├── ChatSessionRepository.java  # 会话仓储接口（用户隔离）
│   ├── UserRepository.java         # 用户仓储接口
│   └── impl/
│       ├── ChatSessionRepositoryimpl.java
│       └── UserRepositoryimpl.java
├── Service/
│   ├── ChatService.java            # 聊天服务接口
│   ├── UserService.java            # 用户服务接口
│   └── impl/
│       ├── ChatServiceImpl.java
│       └── UserServiceimpl.java
└── Utils/
    ├── UserHolder.java             # 用户上下文（ThreadLocal）
    ├── PasswordUtils.java          # 密码工具（BCrypt）
    ├── RegexUtils.java             # 正则工具（含密码校验）
    ├── RegexPatterns.java          # 正则表达式常量
    └── RedisConstants.java         # Redis Key 常量
```

## 当前状态（2026-10-17 15:01 更新）

### ✅ 已完成功能
#### 用户认证系统
- [x] 发送验证码（POST /user/code）+ 60秒防刷冷却
- [x] 验证码登录（POST /user/login）+ 验证码一次性
- [x] 密码登录（POST /user/login/password）
- [x] 设置密码（PUT /user/set-password）+ 手机号归属校验
- [x] 修改资料（PUT /user/profile）+ 昵称长度限制
- [x] 获取当前用户（GET /user/me）+ isNewUser 标记
- [x] 检查是否新用户（GET /user/is-new-user）
- [x] 登出（POST /user/logout）

#### 聊天功能
- [x] 同步对话（POST /chat/ask）+ 参数长度校验
- [x] 流式对话（GET /chat/ask-stream）+ SSE
- [x] 获取会话历史（GET /chat/history）+ 时间戳
- [x] 获取会话列表（GET /chat/sessions）
- [x] 更新会话标题（PUT /chat/title）+ 长度限制
- [x] 清空会话（DELETE /chat/clear-session）

#### 安全与工具
- [x] Token 拦截器（RefreshTokenInterceptor）
- [x] 登录拦截器（LoginInterceptor）+ CORS 预检放行
- [x] BCrypt 密码加密
- [x] 手机号/验证码/密码正则校验
- [x] ThreadLocal 用户上下文
- [x] 新用户标记功能
- [x] 全局异常处理器（BusinessException）
- [x] 验证码发送防刷冷却（60 秒）
- [x] 会话数据用户隔离
- [x] CORS 配置（本地开发端口）

### 📝 核心改进（2026-10-17 更新）
1. **全局异常处理** - 新增 `BusinessException` + `GlobalExceptionHandler`，统一错误响应格式
2. **验证码防刷** - 同一手机号 60 秒内只能发一次验证码（Redis `setIfAbsent` 实现）
3. **验证码一次性** - 校验通过后立即删除，防止重放攻击
4. **密码强度校验** - 设置密码前校验格式（4-32 位字母数字下划线）
5. **手机号归属校验** - 设置密码时校验手机号必须属于当前登录账号，防止越权
6. **新用户密码策略** - 首次注册不生成随机密码，必须走验证码登录后由用户设置
7. **昵称默认规则** - 新用户默认昵称改为"用户+手机尾号"
8. **参数长度校验** - sessionid≤64、question≤8000、title≤50、昵称≤20
9. **CORS 配置** - 允许本地前端跨域（localhost:* 和 127.0.0.1:*）
10. **CORS 预检放行** - OPTIONS 请求不校验登录态
11. **标题按 Unicode 计算** - 使用 codePointCount 正确处理中文
12. **系统提示词集中化** - ChatClientConfig 统一定义 SYSTEM_PROMPT
13. **流式输出优化** - 客户端断开时也保存已生成部分，避免上下文丢失
14. **新用户标记策略调整** - 只在设置密码时清除，改昵称不清除
15. **application.properties 优化** - 使用环境变量 + local profile
16. **SMessage 去 Lombok** - 避免 IDE 未开启注解处理时批量报错

### API 接口总览

#### 用户接口（/user 前缀）
| 方法 | 路径 | 参数 | 说明 |
|------|------|------|------|
| POST | /user/code | phone | 发送验证码（60秒冷却） |
| POST | /user/login | phone, code (Body) | 验证码登录 |
| POST | /user/login/password | phone, password (Body) | 密码登录 |
| PUT | /user/set-password | phone, code, password (Body) | 设置密码 |
| PUT | /user/profile | name (Body), token (Header) | 修改资料 |
| GET | /user/me | - | 获取当前用户（含 isNewUser） |
| GET | /user/is-new-user | - | 检查是否新用户 |
| POST | /user/logout | token (Header) | 登出 |

#### 聊天接口（/chat 前缀）
| 方法 | 路径 | 参数 | 说明 |
|------|------|------|------|
| POST | /chat/ask | sessionid, question | 发送聊天消息（同步） |
| GET | /chat/ask-stream | sessionid, question | 流式发送聊天消息（SSE） |
| GET | /chat/history | sessionid | 获取会话历史（含时间戳） |
| GET | /chat/sessions | - | 获取会话列表 |
| PUT | /chat/title | sessionid, title | 更新会话标题 |
| DELETE | /chat/clear-session | sessionid | 清空会话 |

### 技术要点
1. 用户数据存储在 MySQL（tb_user 表），Redis 缓存会话和 Token
2. 密码使用 BCrypt 自动加盐加密
3. Token 有效期 7 天，每次请求自动续期
4. 验证码有效期 5 分钟，同一手机号 60 秒冷却
5. 新用户标记有效期 365 天，仅设置密码后自动取消
6. 会话数据按用户隔离，key 格式：`aichat:chat:sessions:{userId}`
7. 消息历史按用户隔离，key 格式：`aichat:chat:session:{userId}:{sessionId}`
8. 会话标题按用户隔离，key 格式：`aichat:chat:title:{userId}:{sessionId}`
9. 全局异常处理：BusinessException 携带错误码，统一 JSON 响应
10. 双拦截器：Token 刷新 + 登录校验，CORS 预检放行
11. 参数校验：长度限制 + 格式校验 + 归属校验

## 数据库表结构

### tb_user 表
```sql
CREATE TABLE tb_user (
  uid VARCHAR(32) PRIMARY KEY COMMENT '用户唯一标识',
  name VARCHAR(64) COMMENT '用户昵称',
  phone VARCHAR(20) UNIQUE COMMENT '手机号',
  password VARCHAR(128) COMMENT '密码（BCrypt 加密，首次为 NULL）',
  create_time DATETIME COMMENT '创建时间',
  update_time DATETIME COMMENT '更新时间'
);
```

## 依赖项
```xml
- spring-boot-starter-web
- spring-boot-starter-data-redis
- spring-ai-ollama-spring-boot-starter
- mysql-connector-j (8.0.33)
- mybatis-spring-boot-starter (3.0.3)
- hutool-core (5.8.25)
- hutool-crypto (5.8.25)
- spring-boot-starter-test
```

## 配置说明

### application.properties
```properties
# 激活 local profile（application-local.properties 不提交到 Git）
spring.profiles.active=${SPRING_PROFILES_ACTIVE:local}

# Ollama（支持环境变量覆盖）
spring.ai.ollama.base-url=${OLLAMA_BASE_URL:http://localhost:11434}
spring.ai.ollama.chat.options.model=${OLLAMA_MODEL:qwen2.5-coder:7b}

# Redis（支持环境变量覆盖）
spring.data.redis.host=${REDIS_HOST:localhost}
spring.data.redis.port=${REDIS_PORT:16379}
spring.data.redis.password=${REDIS_PASSWORD:}

# MySQL（支持环境变量覆盖）
spring.datasource.url=${MYSQL_URL:jdbc:mysql://localhost:13306/aichat?useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=utf8}
spring.datasource.username=${MYSQL_USERNAME:root}
spring.datasource.password=${MYSQL_PASSWORD:}

# 验证码开发模式（接入短信前打印到日志）
app.sms.dev-mode=${APP_SMS_DEV_MODE:true}
```

## 文档
- `docs/API.md` - 后端接口文档（供前端使用）
- `docs/学习笔记.md` - 项目学习笔记
- `docs/备注.md` - 开发备注
- `PROJECT_STATUS.md` - 项目状态记录

## 下一步计划（前端开发）
- [ ] 创建登录页面（验证码 + 密码两种方式）
- [ ] 创建新用户引导页面（设置密码）
- [ ] 创建聊天主页面（会话列表 + 聊天窗口）
- [ ] 实现流式输出展示（打字机效果）
- [ ] 实现用户资料修改
- [ ] 添加响应式设计
- [ ] 接入短信服务（替换 dev-mode）

---
*最后更新: 2026-10-17 15:01*


---

## 本地运行（2026-10-17 已跑通）

启动步骤见 **`docs/本地启动.md`**，建库脚本见 **`docs/schema.sql`**。

| 组件 | 实际位置 |
|------|----------|
| MySQL | VirtualBox 虚拟机 `studybox`（Ubuntu 22.10），guest 3306 → 宿主 **13306**，root / 1234 |
| Redis | 同一台虚拟机，guest 6379 → 宿主 **16379**，密码 qw928826 |
| Ollama | Windows 宿主 11434，模型 `qwen2.5-coder:7b` |
| 后端 | Windows 宿主 8080 |
| 前端 | Windows 宿主 5173（`npm run dev`） |

`application.properties` 默认端口（13306 / 16379）与虚拟机 NAT 转发一致，
`application-local.properties`（已在 .gitignore）只保留两个密码，不需要再覆盖数据源 URL。

联调通过：验证码登录 → 新用户设置密码 → 流式对话（Ollama）→ 刷新恢复历史 → 重命名/删除会话 → 修改昵称。
