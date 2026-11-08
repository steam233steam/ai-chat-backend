# MCP：让模型能查外部信息

模型本身不知道今天的天气，也不知道某个网页上写了什么。MCP（Model Context Protocol）把这类「查一下」的能力做成一个个工具，由模型在需要时自己调用。

本项目的实现是**自研的一小段客户端**，没有引第三方 MCP 依赖，原因见文末。

## 现在能用什么

| 工具 | 参数 | 说明 |
|---|---|---|
| `mcp_info_now` | `tz`（默认 `Asia/Shanghai`） | 当前日期时间。模型答「现在几点」靠它 |
| `mcp_info_weather` | `city` | 城市天气，数据来自 wttr.in，免 API key |
| `mcp_info_fetch_url` | `url`、`max_chars`（默认 4000） | 抓网页正文并去标签，用于查资料；阻断内网地址 |

三个都在 `mcp-servers/info-server.mjs` 里，是一个 stdio 传输的 MCP 服务端，用 `node` 起。

## 怎么触发

在对话里直接问，模型自己决定调不调：

```
现在几点了
北京今天天气怎么样
帮我看看 https://example.com 讲了什么
```

只有**白名单模型**会挂工具（`app.mcp.tool-models`，默认 `deepseek-chat`）。本地 `qwen2.5-coder:7b` 的 Ollama 模板没有工具调用的支持，实测它会把调用打成一段文本 JSON 而不是 `tool_calls`，挂上去只会污染提示词，所以默认不给它挂。

## 配置

`application.properties`：

```properties
app.mcp.enabled=${APP_MCP_ENABLED:true}          # 总开关，关掉不启动任何子进程
app.mcp.timeout-ms=${APP_MCP_TIMEOUT_MS:15000}   # 单次调用的超时
app.mcp.tool-models=${APP_MCP_TOOL_MODELS:deepseek-chat}   # 留空=所有模型都挂
app.mcp.servers[0].name=info
app.mcp.servers[0].command=${APP_MCP_COMMAND:node}
app.mcp.servers[0].args[0]=mcp-servers/info-server.mjs
app.mcp.servers[0].enabled=true
```

`servers` 是数组，可以挂多个：`servers[1].name`、`servers[1].command`… 相对路径会按进程工作目录解析成绝对路径。

## 加一个新服务端

任何支持 stdio 的 MCP 服务端都能挂，比如官方 `@modelcontextprotocol/server-filesystem`：

```properties
app.mcp.servers[1].name=fs
app.mcp.servers[1].command=npx
app.mcp.servers[1].args[0]=-y
app.mcp.servers[1].args[1]=@modelcontextprotocol/server-filesystem
app.mcp.servers[1].args[2]=D:/tmp/sandbox
```

工具名会被统一成 `mcp_<服务名>_<工具名>`（只保留 `[A-Za-z0-9_-]`，最长 64 字符），避免不同服务端重名。

## 调试：不经过模型直接调

```bash
curl -H "Authorization: <登录 token>" http://localhost:8080/mcp/tools
curl -X POST -H "Authorization: <登录 token>" -H "Content-Type: application/json" \
     -d '{"tz":"Asia/Shanghai"}' http://localhost:8080/mcp/tools/mcp_info_now
```

这条路径的意义是：模型不调或调不对时，先确认工具本身是通的，把「工具坏了」和「模型不会用」分开。

## 设计上的几个选择

- **子进程懒启动**：只有第一次真正要挂工具（或第一次 REST 调用）时才 `spawn`，不给启动路径加延迟。
- **单服务失败不影响整体**：某个服务端起不来只 `log.warn`，其余工具照常可用，对话不受影响。
- **工具失败回文案而不是抛异常**：`isError` 的内容会作为工具结果喂回模型，模型能换个做法；抛异常则整轮对话失败。
- **不引 `spring-ai-starter-mcp-*`**：本地 `spring-ai-bom:1.0.0-M5` 里没有任何 MCP 模块，MCP starter 从 `1.0.0` 才有，而 `1.0.0` 要求 Spring Boot 3.4+（当前 3.2.5）。为了一个 stdio 客户端升级整条依赖链不划算，手写两百多行更可控。

## 测试

- `src/test/java/com/aichat/mcp/McpClientTest.java`：真起 node 子进程，验证握手 → `tools/list` → `tools/call`，以及未知工具会报错。
- `src/test/java/com/aichat/mcp/McpToolCatalogTest.java`：在 Spring 上下文里验证配置绑定、工具名注册、白名单过滤。

两个都带 `Assumptions`，本机没有 node 时自动跳过，不会让 CI 变红。

## 已知限制

- 一次对话里模型最多会连续调用几轮工具，没有额外限制；如果挂上很慢的服务端，建议配合 `app.mcp.timeout-ms` 调小。
- `fetch_url` 只做「协议 + 内网地址」两道拦截，不解析 JS，对纯前端渲染的页面拿不到正文。
- 工具结果直接进上下文，长网页会挤占 token；`max_chars` 默认 4000，别调太大。
