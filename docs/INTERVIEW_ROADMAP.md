# 面试项目化改造路线

> 记录本项目从"个人学习项目"到"可面试项目"的改造思路，按性价比排序。

## 一、当前项目 vs 瑞吉外卖的定位

- 业务广度：偏弱（单点 AI 聊天场景，无多角色、无下单闭环）
- 技术深度：偏强（Cache-Aside、拦截器链、Redis 多数据结构、Spring AI 流式）
- 生产感：中等（有全局异常、README、CI，缺 Swagger / Docker / 限流）

**面试策略**：不要按"业务广度"和瑞吉比，而是把叙事拉到"专注技术深度"。

## 二、P0 功能补齐（1 周内完成，面试强加分）

### 1. 管理员后台（推荐先做）
- 查所有用户列表（分页 + 关键字搜索）
- 强制下线用户（删除其 Token）
- 封号 / 解封（加 `status` 字段）
- 统计：每个用户的历史消息数、Token 过期时间
- **面试价值**：补上"多角色"，画出完整的 用户 / 服务 / 管理员 系统图

### 2. 消息搜索
- 按用户 + 会话 + 关键字模糊搜索历史消息
- 用 MySQL 全文索引或 LIKE 前缀匹配
- 分页（`LIMIT/OFFSET`）
- **面试价值**：SQL 深度、索引设计

### 3. 用户偏好设置
- 每个用户独立配置：模型温度、系统提示词、上下文窗口大小
- 加 `tb_user_setting` 表
- **面试价值**：配置管理、可扩展设计

## 三、P1 高并发 / 稳定性（面试可聊）

### 4. Redis 限流
- 每个用户每分钟 10 条消息
- 用令牌桶 / 滑动窗口（Redis + Lua）
- **面试价值**：高并发场景应对

### 5. AI 调用熔断
- Ollama 失败 N 次后熔断，返回兜底文案
- **面试价值**：容错设计

### 6. 会话冷热分离
- 7 天内的走 Redis，7 天前的只查 MySQL（不回填）
- 节省 Redis 内存
- **面试价值**：内存成本意识

## 四、P2 工程化（让项目"像生产"）

### 7. Swagger + SpringDoc
- 加 `springdoc-openapi-starter-webmvc-ui` 依赖
- 所有 Controller 加 `@Tag` / `@Operation` / `@Parameter`
- **面试价值**：团队协作

### 8. Docker Compose
- 4 个容器：MySQL、Redis、Ollama、后端
- 一条 `docker compose up` 起完整环境
- **面试价值**：部署能力

### 9. 多环境配置
- `application-dev.properties` / `application-prod.properties`
- 敏感配置走环境变量
- **面试价值**：工程规范

### 10. MDC 日志 traceId
- 每个请求生成唯一 traceId
- 请求链路日志全带上
- **面试价值**：排障能力

### 11. 补 3 个集成测试
- 登录 → 发消息 → 查历史
- 会话删除
- 用户登出后 Token 失效
- **面试价值**：质量意识

## 五、面试话术模板

> "这是一个 AI 聊天后端，用了 Spring Boot 3 + Spring AI + Redis + MySQL + MyBatis。
> 我关注的是工程规范和 Redis 缓存设计，不是业务广度。
>
> 核心设计：
> 1. 三层架构：Controller → Service → Repository → Mapper，数据访问完全隔离
> 2. Redis 多数据结构：String 存验证码、Hash 存 Token、ZSet 存会话列表、List 存消息
> 3. Cache-Aside + 写穿透：MySQL 为主，Redis 7 天热缓存，miss 回源回填，写失败主动失效
> 4. 拦截器链：RefreshToken 先解析 Token，Login 后校验登录，ThreadLocal 传上下文
> 5. AI 能力：Spring AI 调 Ollama，流式用 Reactor Flux + SSE，@Async 生成会话标题
>
> 业务简单是为了把技术做深，不是为了赶进度。"

## 六、执行优先级

| 优先级 | 任务 | 预估 |
|--------|------|------|
| 1 | 管理员后台 | 2 天 |
| 2 | 消息搜索 | 1 天 |
| 3 | 用户偏好设置 | 1 天 |
| 4 | Redis 限流 | 1 天 |
| 5 | Docker Compose + Swagger | 1 天 |
| 6 | 集成测试 | 0.5 天 |
| 7 | 优化 README + 架构图 | 0.5 天 |

**总计约 1 周改造后，项目可以撑起简历 + 面试。**
