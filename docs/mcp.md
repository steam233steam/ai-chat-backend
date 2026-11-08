# MCP：让模型能查外部信息

模型本身不知道今天的天气，也不知道某个网页上写了什么。MCP（Model Context Protocol）把这类「查一下」的能力做成一个个工具，由模型在需要时自己调用。

本项目的实现是**自研的一小段客户端**，没有引第三方 MCP 依赖，原因见文末。

## 现在能用什么

| 工具 | 参数 | 说明 |
|---|---|---|
| `mcp_info_now` | `tz`（默认 `Asia/Shanghai`） | 当前日期时间。模型答「现在几点」靠它 |
| `mcp_info_weather` | `city` | 城市天气，数据来自 wttr.in，免 API key |
| `mcp_info_fetch_url` | `url`、`max_chars`（默认 4000） | 抓网页正文并去标签，用于查资料；阻断内网地址 |
| `mcp_info_search` | `q`、`count`（默认 5，最多 10） | 必应搜索，返回标题/链接/摘要；要细节再配 `fetch_url` 读正文 |

四个都在 `mcp-servers/info-server.mjs` 里，是一个 stdio 传输的 MCP 服务端，用 `node` 起。

## 怎么触发

在对话里直接问，模型自己决定调不调：

```
现在几点了
北京今天天气怎么样
帮我看看 https://example.com 讲了什么
帮我搜一下今年诺贝尔文学奖得主是谁
```

只有**白名单模型**会挂工具（`app.mcp.tool-models`，默认 `deepseek-chat`）。本地 `qwen2.5-coder:7b` 的 Ollama 模板没有工具调用的支持，实测它会把调用打成一段文本 JSON 而不是 `tool_calls`，挂上去只会污染提示词，所以默认不给它挂。

## 配置

`application.properties`：

```properties
app.mcp.enabled=${APP_MCP_ENABLED:true}          # 总开关，关掉不启动任何子进程
app.mcp.timeout-ms=${APP_MCP_TIMEOUT_MS:15000}   # 单次调用的超时
app.mcp.tool-models=${APP_MCP_TOOL_MODELS:deepseek-chat}   # 留空=所有模型都挂
app.mcp.servers.info.command=${APP_MCP_COMMAND:node}
app.mcp.servers.info.args[0]=mcp-servers/info-server.mjs
app.mcp.servers.info.enabled=true
```

`servers` 是 **Map，key 就是服务名**，可以挂多个、也可以分散在多个配置文件里追加（`servers.notes.*` 写在 `application-local.properties` 里就行）。
一开始用的是 `servers[0]` 这种列表写法，但 Spring 的列表绑定在「主配置给 `[0]`、本地配置只给 `[1]`」时会整段报 `were left unbound` —— 索引不连续就绑不上，换成 Map 才稳。

## 加一个新服务端（已接：官方 filesystem）

任何支持 stdio 的 MCP 服务端都能挂。仓库现在接了两个：

| 服务名 | 实现 | 工具 | 说明 |
|---|---|---|---|
| `info` | 仓库内 `mcp-servers/info-server.mjs` | `now` / `weather` / `fetch_url` / `search` | 时间、天气、网页正文、必应搜索 |
| `notes` | 官方 `@modelcontextprotocol/server-filesystem@2.2.0` | `read_text_file` / `write_file` / `list_directory` / `search_files` | 根目录锁在仓库 `notes/`，只暴露 4 个工具 |

第三方服务端的接法（`application-local.properties`，因为路径是本机绝对路径，不进仓库）：

```properties
app.mcp.servers.notes.command=D:/nodejs/node.exe
app.mcp.servers.notes.args[0]=D:/tools/mcp-node/node_modules/@modelcontextprotocol/server-filesystem/dist/index.js
app.mcp.servers.notes.args[1]=D:/maven-project/aichat/notes
app.mcp.servers.notes.expose[0]=read_text_file
app.mcp.servers.notes.expose[1]=write_file
app.mcp.servers.notes.expose[2]=list_directory
app.mcp.servers.notes.expose[3]=search_files
app.mcp.servers.notes.enabled=true
```

装依赖（一次性）：`npm i --prefix D:/tools/mcp-node @modelcontextprotocol/server-filesystem`

Windows 上的两个坑：
1. **不要在 `command` 里写 `npx`**：`ProcessBuilder` 在 Windows 上起不了 `.cmd`/`.bat`，只会报找不到文件；本地 `npm i` 之后直接用 `node <dist/index.js>` 最稳，也顺带省掉每次冷启动下载包的时间。
2. **`expose` 一定要写**：filesystem 服务端会暴露 16 个工具，全挂上去每个都占提示词预算，只留真正要用的 4 个。

工具名会被统一成 `mcp_<服务名>_<工具名>`（只保留 `[A-Za-z0-9_-]`，最长 64 字符），避免不同服务端重名。

## 实测记录（2026-11-08）

- 握手：官方 filesystem 2.2.0 与 memory 2.1.0 都能跟我们客户端声明的 `protocolVersion=2024-11-05` 谈成，返回自己的 `serverInfo`，`tools/list` 正常。
- 端到端：`帮我看看 notes 目录里 plan.md 写了什么` → 模型调 `mcp_notes_read_text_file` → 2.94s 正确总结；REST `POST /mcp/tools/mcp_notes_write_file` 也能写盘。
- 意图层会先拦截：带时间/「记住」字样的句子会走 pending 确认，轮不到工具。想测工具就用不含意图关键词的句子。
- 搜索：现成的 `duckduckgo-mcp-server`（Python）在本机**跑不通**——它对 DuckDuckGo 的请求全部超时（`html.duckduckgo.com` 12s 无响应，curl 返回码 000），而 `cn.bing.com` 直连正常（200，10 条 `b_algo`）。所以 `search` 是自己写的：抓必应结果页的 `li.b_algo`，解析标题/链接/摘要，并把 `/ck/a?u=a1<base64>` 这种跳转链接还原成真实地址。实测 314ms 返回 3 条，中文可直搜。
- 搜索的已知局限：必应是 HTML 页面，结构变了就得改选择器；没有翻页；不做去重和广告过滤，所以摘要里偶尔会有站点自己的推广文案。
- 工具到底调没调，看日志：`McpFunctionCallback` 每次调用都打一行 `MCP 工具 mcp_info_search，参数 {...}` 和耗时。实测 `现在几点，必须调用工具查` → `mcp_info_now` 14ms，答出精确到秒的时间；`帮我搜一下 2026 年诺贝尔文学奖得主` → `mcp_info_search` 309ms、返回 804 字符，整轮 3.2s。
- Windows 上 `mvnw ... > app.log` 重定向出来的日志是 **GBK**，用 `Get-Content -Encoding UTF8` 看中文会乱码，要 `-Encoding Default`。

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
