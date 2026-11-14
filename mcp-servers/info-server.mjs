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
        location: { type: 'string', description: 'city 的别名，二选一' },
      },
      required: [],
    },
  },
  {
    name: 'fetch_url',
    description: '抓取一个公开网页并返回纯文本正文（去掉 HTML 标签），用于查资料。禁止内网地址。',
    inputSchema: {
      type: 'object',
      properties: {
        url: { type: 'string', description: 'http/https 地址' },
        link: { type: 'string', description: 'url 的别名，二选一' },
        max_chars: { type: 'integer', description: '最多返回多少字符，默认 4000' },
      },
      required: [],
    },
  },
  {
    name: 'search',
    description: '用必应搜索网页，返回标题、链接和摘要，并默认抓取相关性最高结果的正文件（fetch_top=true）。注意：遇到赛事日程、比分这类数据，必应常只给官网首页，此时会明确返回“没查到可核实的内容”。',
    inputSchema: {
      type: 'object',
      properties: {
        q: { type: 'string', description: '搜索词，中文可直接用' },
        query: { type: 'string', description: 'q 的别名，二选一' },
        count: { type: 'integer', description: '返回条数，默认 5，最多 10' },
        fetch_top: { type: 'boolean', description: '是否抓第一条结果的正文，默认 true；只要摘要时传 false' },
        max_chars: { type: 'integer', description: '正文最多返回多少字，默认 1200，范围 300~4000' },
      },
      required: [],
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

function decodeEntities(text) {
  return text
    .replace(/&#x([0-9a-f]+);/gi, (_, hex) => String.fromCodePoint(parseInt(hex, 16)))
    .replace(/&#(\d+);/g, (_, dec) => String.fromCodePoint(Number(dec)))
    .replace(/&nbsp;|&ensp;|&emsp;|&thinsp;/g, ' ')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"')
    .replace(/&#39;|&apos;/g, "'")
    .replace(/&amp;/g, '&')
}

// 必应会把结果链接包成 /ck/a?u=a1<base64url>，解出来才是真实地址
function decodeBingLink(href) {
  try {
    const u = new URL(href, 'https://cn.bing.com')
    if (!/(^|\.)bing\.com$/i.test(u.hostname) || !u.pathname.startsWith('/ck/a')) return href
    const raw = u.searchParams.get('u')
    if (!raw || !raw.startsWith('a1')) return href
    const b64 = raw.slice(2).replace(/-/g, '+').replace(/_/g, '/')
    const padded = b64 + '='.repeat((4 - (b64.length % 4)) % 4)
    const decoded = Buffer.from(padded, 'base64').toString('utf8')
    return /^https?:\/\//i.test(decoded) ? decoded : href
  } catch {
    return href
  }
}

// 搜索结果里 <strong> 会把词切开，去标签时不能补空格，否则中文变成「北京天气 预报」
function inlineText(html) {
  return decodeEntities(String(html).replace(/<[^>]+>/g, '')).replace(/\s+/g, ' ').trim()
}

// 抓网页正文：只接受 html/纯文本，其它类型（pdf、图片、json）直接判失败，
// 交给调用方换下一条结果，避免把二进制当正文塞给模型。
// 国内站点大量是 GBK/GB2312，直接 res.text() 按 UTF-8 解会整页乱码。
// 这里先从响应头与 <meta charset> 猜编码，再用 TextDecoder 解。
function decodePage(buffer, contentType) {
  const head = new TextDecoder('latin1').decode(buffer.slice(0, 2048))
  const fromHeader = /charset=([\w-]+)/i.exec(contentType || '')?.[1]
  const fromMeta = /<meta[^>]+charset\s*=\s*["']?([\w-]+)/i.exec(head)?.[1]
  const label = (fromHeader || fromMeta || 'utf-8').toLowerCase().replace('gb2312', 'gbk')
  try {
    return new TextDecoder(label).decode(buffer)
  } catch {
    return new TextDecoder('utf-8').decode(buffer)
  }
}

// 中文没有空格，用「二字片段」估相关性：查询词与标题/摘要共有的片段越多越相关。
// 典型收益：搜「2026诺贝尔文学奖 得主」时，能认出"2026年大事一览"其实不相关。
function relevance(q, title, snippet) {
  const norm = (s) => String(s || '').replace(/[\s\p{P}\p{S}]+/gu, '')
  const wanted = new Set()
  const qq = norm(q)
  for (let i = 0; i + 2 <= qq.length; i++) wanted.add(qq.slice(i, i + 2))
  if (!wanted.size) return 0
  const hay = norm(title) + norm(snippet)
  let hit = 0
  for (const g of wanted) if (hay.includes(g)) hit++
  return hit / wanted.size
}

function looksLikeHomepage(url, title, q) {
  try {
    const path = new URL(url).pathname.replace(/\/+$/, '')
    const shallow = path === '' || path === '/' || /\/main(\.s?html?)?$/i.test(path)
    const tokenHit = q.split(/\s+/).filter(Boolean).some((w) => w.length > 1 && title.includes(w))
    return shallow && !tokenHit
  } catch {
    return true
  }
}

async function fetchReadable(url, maxChars) {
  const target = assertPublicUrl(url)
  const res = await withTimeout(
    (signal) => fetch(target, { signal, headers: { 'User-Agent': 'Mozilla/5.0 (aichat-mcp)' } }),
    9000, '正文抓取')
  if (!res.ok) throw new Error(`目标返回 ${res.status}`)
  const contentType = (res.headers.get('content-type') || '').toLowerCase()
  if (contentType && !/text\/html|text\/plain|application\/xhtml/.test(contentType)) {
    throw new Error(`不是网页正文（${contentType}）`)
  }
  const text = stripHtml(decodePage(await res.arrayBuffer(), contentType))
  if (!text) throw new Error('页面没有可读正文')
  return {
    text: text.length > maxChars ? `${text.slice(0, maxChars)}…` : text,
    total: text.length,
    finalUrl: res.url || String(target),
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
    const city = (args.city || args.location || args['城市'] || '').trim()
    if (!city) throw new Error('缺少 city')
    const url = `https://wttr.in/${encodeURIComponent(city)}?format=3&lang=zh`
    const res = await withTimeout(
      (signal) => fetch(url, { signal, headers: { 'User-Agent': 'curl/8' } }), 8000, '天气查询')
    if (!res.ok) throw new Error(`wttr.in 返回 ${res.status}`)
    return textResult((await res.text()).trim())
  },

  async fetch_url(args) {
    const maxChars = Number.isInteger(args.max_chars) ? Math.min(args.max_chars, 20000) : 4000
    const r = await fetchReadable(args.url || args.link || args.href || '', maxChars)
    const suffix = r.total > maxChars ? `（已截断，共 ${r.total} 字）` : ''
    return textResult(`${r.text}${suffix}`)
  },

  async search(args) {
    // 模型经常把搜索词写成 query/keyword，这里全部兼容，别让它因为参数名不同就失败
    const q = (args.q || args.query || args.keyword || args.搜索词 || '').trim()
    if (!q) throw new Error('缺少 q')
    const count = Math.min(Math.max(Number.isInteger(args.count) ? args.count : 5, 1), 10)
    const url = `https://cn.bing.com/search?q=${encodeURIComponent(q)}&setlang=zh-hans`
    const res = await withTimeout(
      (signal) => fetch(url, {
        signal,
        headers: {
          'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36',
          Accept: 'text/html,application/xhtml+xml',
          'Accept-Language': 'zh-CN,zh;q=0.9,en;q=0.8',
        },
      }), 10000, '搜索')
    if (!res.ok) throw new Error(`必应返回 ${res.status}`)
    const html = await res.text()
    const blocks = html.match(/<li class="b_algo"[\s\S]*?<\/li>/g) || []
    const items = []
    for (const block of blocks) {
      const hit = block.match(/<h2[^>]*>[\s\S]*?<a[^>]*href="([^"]+)"[^>]*>([\s\S]*?)<\/a>/)
      if (!hit) continue
      items.push({
        title: inlineText(hit[2]),
        link: decodeBingLink(hit[1]),
        snippet: inlineText(block.match(/<p[^>]*>([\s\S]*?)<\/p>/)?.[1] || ''),
      })
      if (items.length >= count) break
    }
    if (!items.length) throw new Error('必应没有返回可解析的结果（页面结构可能变了）')
    const lines = items.map((it, i) => `${i + 1}. ${it.title}\n   ${it.link}${it.snippet ? `\n   ${it.snippet}` : ''}`)
    const head = `搜索「${q}」共 ${items.length} 条：\n\n${lines.join('\n\n')}`

    // 只给摘要时，模型经常只能看到「官网首页」这种废信息；
    // 默认再抓前三条里第一条真正有正文的页面，失败就静默退回摘要。
    const lowRelevance = relevance(q, items[0].title, items[0].snippet) < 0.15
    const hint = lowRelevance
      ? '\n\n（这几条结果和查询词关系不大，建议换更具体的关键词再搜一次，比如加上年份、赛事名或“结果/战报”）'
      : ''
    if (args.fetch_top === false) {
      return textResult(head + hint)
    }
    const bodyChars = Math.min(Math.max(Number.isInteger(args.max_chars) ? args.max_chars : 1200, 300), 4000)
    // 首页类结果正文全是导航和版权声明；同时按二字片段相关性排序，
    // 避免「搜诺贝尔文学奖、抓回2026年大事」这种离谱匹配。
    const scored = items
      .map((it) => ({ it, rel: relevance(q, it.title, it.snippet) }))
      .sort((a, b) => b.rel - a.rel)
    const decent = scored.filter((s) => s.rel >= 0.15).map((s) => s.it)
    // 官网首页的正文只有导航和版权声明，喂给模型等于喂垃圾：
    // 全部结果都是首页时直接判失败，让上层的执行记录显示"没查到"，而不是假装成功。
    const candidates = (decent.length ? decent : items).filter((it) => !looksLikeHomepage(it.link, it.title, q))
    if (!candidates.length) {
      return textResult(
        `${head}\n\n没查到可核实的内容：这些结果都是官网首页或与查询无关。` +
        `建议把问题说得更具体（加上日期、赛事名、队伍名），或者换一个信息源。`,
        true)
    }
    for (const it of candidates.slice(0, 3)) {
      try {
        const r = await fetchReadable(it.link, bodyChars)
        if (r.total < 200) continue
        return textResult(`${head}\n\n—— 正文（第 1 条：${it.title}）——\n${r.text}`)
      } catch {
        // 换下一条
      }
    }
    return textResult(
      `${head}${hint}\n\n这几条都没能取到正文，只有上面的摘要——可能不是你要的答案，换个更具体的关键词再试。`,
      true)
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
