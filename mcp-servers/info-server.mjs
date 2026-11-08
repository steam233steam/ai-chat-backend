#!/usr/bin/env node
/**
 * aichat 内置的信息查询 MCP 服务端（stdio 传输，换行分隔 JSON-RPC 2.0）。
 *
 * 提供三个工具：
 *   now        当前时间（可选时区）
 *   weather    天气（wttr.in，免 key）
 *   fetch_url  抓网页正文（去掉标签，限制长度；阻断内网地址）
 *
 * 用法：node mcp-servers/info-server.mjs
 * 不往 stdout 打任何非 JSON 内容，日志一律走 stderr。
 */
import readline from 'node:readline'

const PROTOCOL_VERSION = '2024-11-05'
const SERVER_INFO = { name: 'aichat-info', version: '1.0.0' }

const TOOLS = [
  {
    name: 'now',
    description: '获取当前日期和时间，可指定 IANA 时区（如 Asia/Shanghai）。',
    inputSchema: {
      type: 'object',
      properties: {
        tz: { type: 'string', description: 'IANA 时区，默认 Asia/Shanghai' },
      },
      required: [],
    },
  },
  {
    name: 'weather',
    description: '查询城市当前天气（中文城市名可用，如 北京）。数据来自 wttr.in，免 API key。',
    inputSchema: {
      type: 'object',
      properties: {
        city: { type: 'string', description: '城市名，如 北京 / Shanghai' },
      },
      required: ['city'],
    },
  },
  {
    name: 'fetch_url',
    description: '抓取一个公开网页并返回纯文本正文（去掉 HTML 标签），用于查资料。禁止内网地址。',
    inputSchema: {
      type: 'object',
      properties: {
        url: { type: 'string', description: 'http/https 地址' },
        max_chars: { type: 'integer', description: '最多返回多少字符，默认 4000' },
      },
      required: ['url'],
    },
  },
]

function send(message) {
  process.stdout.write(`${JSON.stringify(message)}\n`)
}

function reply(id, result) {
  send({ jsonrpc: '2.0', id, result })
}

function replyError(id, code, message) {
  send({ jsonrpc: '2.0', id, error: { code, message } })
}

function textResult(text, isError = false) {
  return { content: [{ type: 'text', text }], isError }
}

const PRIVATE_HOST = /^(localhost|0\.0\.0\.0|127\.|10\.|192\.168\.|169\.254\.|172\.(1[6-9]|2\d|3[01])\.|\[?::1\]?$)/i

function assertPublicUrl(raw) {
  let url
  try {
    url = new URL(raw)
  } catch {
    throw new Error('URL 格式不合法')
  }
  if (url.protocol !== 'http:' && url.protocol !== 'https:') {
    throw new Error('只允许 http/https')
  }
  if (PRIVATE_HOST.test(url.hostname)) {
    throw new Error('不允许访问内网地址')
  }
  return url
}

function stripHtml(html) {
  return html
    .replace(/<script[\s\S]*?<\/script>/gi, ' ')
    .replace(/<style[\s\S]*?<\/style>/gi, ' ')
    .replace(/<noscript[\s\S]*?<\/noscript>/gi, ' ')
    .replace(/<[^>]+>/g, ' ')
    .replace(/&nbsp;/g, ' ')
    .replace(/&amp;/g, '&')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"')
    .replace(/\s+/g, ' ')
    .trim()
}

async function withTimeout(promise, ms, label) {
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), ms)
  try {
    return await promise(controller.signal)
  } catch (e) {
    if (e.name === 'AbortError') throw new Error(`${label}超时（${ms}ms）`)
    throw e
  } finally {
    clearTimeout(timer)
  }
}

const handlers = {
  async now(args) {
    const tz = args.tz || 'Asia/Shanghai'
    const at = new Date()
    let local
    try {
      local = new Intl.DateTimeFormat('zh-CN', {
        timeZone: tz, dateStyle: 'full', timeStyle: 'medium',
      }).format(at)
    } catch {
      throw new Error(`不认识的时区：${tz}`)
    }
    return textResult(`${local}（${tz}）\nISO: ${at.toISOString()}`)
  },

  async weather(args) {
    const city = (args.city || '').trim()
    if (!city) throw new Error('缺少 city')
    const url = `https://wttr.in/${encodeURIComponent(city)}?format=3&lang=zh`
    const res = await withTimeout(
      (signal) => fetch(url, { signal, headers: { 'User-Agent': 'curl/8' } }), 8000, '天气查询')
    if (!res.ok) throw new Error(`wttr.in 返回 ${res.status}`)
    return textResult((await res.text()).trim())
  },

  async fetch_url(args) {
    const url = assertPublicUrl(args.url || '')
    const maxChars = Number.isInteger(args.max_chars) ? Math.min(args.max_chars, 20000) : 4000
    const res = await withTimeout(
      (signal) => fetch(url, { signal, headers: { 'User-Agent': 'Mozilla/5.0 (aichat-mcp)' } }),
      10000, '网页抓取')
    if (!res.ok) throw new Error(`目标返回 ${res.status}`)
    const raw = await res.text()
    const text = stripHtml(raw)
    const clipped = text.length > maxChars ? `${text.slice(0, maxChars)}…（已截断，共 ${text.length} 字）` : text
    return textResult(clipped || '（页面没有可读正文）')
  },
}

async function callTool(id, params) {
  const name = params?.name
  const handler = handlers[name]
  if (!handler) {
    replyError(id, -32602, `未知工具：${name}`)
    return
  }
  try {
    reply(id, await handler(params.arguments || {}))
  } catch (e) {
    // 工具级失败用 isError 回，不当作协议错误，模型能看到原因并换个做法
    reply(id, textResult(`工具执行失败：${e.message}`, true))
  }
}

function handle(msg) {
  const { id, method, params } = msg
  if (method === 'initialize') {
    reply(id, {
      protocolVersion: PROTOCOL_VERSION,
      capabilities: { tools: { listChanged: false } },
      serverInfo: SERVER_INFO,
    })
    return
  }
  if (method === 'notifications/initialized' || id === undefined) {
    return
  }
  if (method === 'ping') {
    reply(id, {})
    return
  }
  if (method === 'tools/list') {
    reply(id, { tools: TOOLS })
    return
  }
  if (method === 'tools/call') {
    void callTool(id, params)
    return
  }
  replyError(id, -32601, `不认识的方法：${method}`)
}

const rl = readline.createInterface({ input: process.stdin, crlfDelay: Infinity })
rl.on('line', (line) => {
  if (!line.trim()) return
  let msg
  try {
    msg = JSON.parse(line)
  } catch {
    process.stderr.write(`[info-server] 收到非 JSON 行：${line.slice(0, 200)}\n`)
    return
  }
  handle(msg)
})

process.stderr.write('[info-server] 已启动，等待 JSON-RPC\n')
