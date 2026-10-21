// 仅用于前端自测的 mock 服务端（不是交付物主体）。按 docs/API.md 的契约实现主要接口。
// 用法：node mock/server.mjs [port]   （默认 8080；同时托管 web/dist，便于直接浏览器验证 history 路由回退）
import http from 'node:http'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { randomUUID } from 'node:crypto'

const PORT = Number(process.argv[2] || process.env.PORT || 8080)
const DIST = path.join(path.dirname(fileURLToPath(import.meta.url)), '..', 'dist')
const now = () => Date.now()
const CSRF = 'mock-csrf-token'

const state = {
  installed: process.env.MOCK_INSTALLED !== '0',
  sessions: new Set(),
  settings: {
    site_name: 'SMS Web Gateway', timezone: 'Asia/Shanghai', online_threshold_seconds: 60, send_paused: false,
    send_rate_per_minute: 10, max_content_length: 1000, home_country_code: '+86', heartbeat_interval_s: 30, poll_wait_s: 20,
  },
  devices: [
    { id: 1, device_name: 'Redmi K40', android_version: '15', app_version: '1.0.0', battery_level: 98, charging: true, network_type: 'wifi', sim_count: 2, sim_operator: '中国电信',
      sims: [{ slot: 0, operator: '中国电信', state: 'ready' }, { slot: 1, operator: '', state: 'absent' }], last_seen: now(), online: true, sync_status: 'running', sync_total: 12000, sync_synced: 3400,
      pending_tasks: 1, keepalive_mode: 'root_enhanced', watchdog_status: 'running', root_granted: true, battery_whitelisted: true, last_sync_at: now() - 8000, last_recovery_at: now() - 3600000, recovery_count: 2, protection_state: 'none', created_at: now() - 86400000 },
    { id: 2, device_name: 'Backup Pixel', android_version: '15', app_version: '1.0.0', battery_level: 15, charging: false, network_type: 'mobile', sim_count: 1, sim_operator: 'NTT docomo',
      sims: [{ slot: 0, operator: 'NTT docomo', state: 'ready' }], last_seen: now() - 600000, online: false, sync_status: 'done', sync_total: 500, sync_synced: 500,
      pending_tasks: 0, keepalive_mode: 'normal', watchdog_status: 'not_installed', root_granted: false, battery_whitelisted: false, last_sync_at: now() - 700000, last_recovery_at: null, recovery_count: 0, protection_state: 'cooldown', created_at: now() - 3 * 86400000 },
  ],
  conversations: [],
  messages: [],
  tasks: {},
  audit: [],
  nextMsg: 1, nextConv: 1,
  events: [], nextEvent: 1, clients: new Set(),
  sendTimestamps: [],
}

function conv(device_id, phone, name) {
  const c = { id: state.nextConv++, device_id, device_name: state.devices.find((d) => d.id === device_id)?.device_name ?? '', phone_number: phone, contact_name: name,
    last_message: '', last_message_at: 0, last_direction: 'incoming', unread_count: 0, has_failed: false, pinned: false }
  state.conversations.push(c)
  return c
}
function addMsg(c, over) {
  const m = { id: state.nextMsg++, device_id: c.device_id, conversation_id: c.id, phone_number: c.phone_number, contact_name: c.contact_name, content: '', direction: 'incoming',
    timestamp: now(), status: 'received', sim_slot: 0, task_id: null, error_message: null, read: true, ...over }
  state.messages.push(m)
  if (m.timestamp >= c.last_message_at) { c.last_message = m.content; c.last_message_at = m.timestamp; c.last_direction = m.direction }
  if (m.direction === 'incoming' && !m.read) c.unread_count++
  return m
}
// 种子数据
const c1 = conv(1, '13800138000', '张三')
for (let i = 0; i < 120; i++) addMsg(c1, { content: `历史消息 ${i + 1} ` + (i % 7 === 0 ? '<img src=x onerror=alert(1)> 验证码 123456' : ''), direction: i % 3 === 0 ? 'outgoing' : 'incoming', status: i % 3 === 0 ? 'sent' : 'received', timestamp: now() - (130 - i) * 3600_000 * 2 })
const t1 = randomUUID()
state.tasks[t1] = { task_id: t1, device_id: 1, recipient: '13800138000', content: '这条的结果未知', sim_slot: 0, status: 'unknown', error_code: null, error_message: '设备在发送过程中重启', created_at: now() - 600000, claimed_at: now() - 590000, completed_at: null, resolved_action: null }
addMsg(c1, { content: '这条的结果未知', direction: 'outgoing', status: 'unknown', task_id: t1, error_message: '设备在发送过程中重启', timestamp: now() - 600000 })
const c2 = conv(1, '10086', '中国移动'); addMsg(c2, { content: '【中国移动】您的话费余额为 50 元', read: false, timestamp: now() - 120000 })
const c3 = conv(2, '+819012345678', null); addMsg(c3, { content: 'こんにちは', direction: 'incoming', read: false, timestamp: now() - 86400000 })
const c4 = conv(1, 'ICBC', '工商银行'); addMsg(c4, { content: '您尾号1234的账户收入100元', timestamp: now() - 3 * 86400000 }); c4.pinned = true
for (let i = 0; i < 45; i++) { const c = conv(2, `1390000${String(i).padStart(4, '0')}`, null); addMsg(c, { content: `批量会话 ${i}`, timestamp: now() - (i + 5) * 600000 }) }

function emit(event, data) {
  const id = state.nextEvent++
  state.events.push({ id, event, data })
  if (state.events.length > 200) state.events.shift()
  for (const res of state.clients) res.write(`id: ${id}\nevent: ${event}\ndata: ${JSON.stringify(data)}\n\n`)
}
const audit = (action, target, detail) => state.audit.unshift({ id: state.audit.length + 1, ts: now(), actor_type: 'admin', actor: 'admin', action, target, detail, ip: '127.0.0.1' })

const send = (res, status, body, headers = {}) => { res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', ...headers }); res.end(JSON.stringify(body)) }
const ok = (res, data = {}, status = 200) => send(res, status, { ok: true, data })
const fail = (res, status, code, message, details) => send(res, status, { ok: false, error: { code, message, ...(details ? { details } : {}) } })

function readBody(req) {
  return new Promise((resolve) => { let b = ''; req.on('data', (c) => (b += c)); req.on('end', () => { try { resolve(b ? JSON.parse(b) : {}) } catch { resolve(null) } }) })
}
const normPhone = (s) => String(s).replace(/[\s\-().]/g, '')
const paginate = (arr, q) => { const page = Math.max(1, +q.get('page') || 1), size = Math.min(100, +q.get('page_size') || 30); return { items: arr.slice((page - 1) * size, page * size), total: arr.length, page, page_size: size } }
const sortedConvs = () => [...state.conversations].sort((a, b) => (b.pinned - a.pinned) || (b.last_message_at - a.last_message_at))

const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.json': 'application/json' }

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://x')
  const p = url.pathname
  if (!p.startsWith('/api/v1/')) {
    // 静态文件 + history 回退
    let f = path.join(DIST, p === '/' ? 'index.html' : p)
    if (!f.startsWith(DIST) || !fs.existsSync(f) || fs.statSync(f).isDirectory()) f = path.join(DIST, 'index.html')
    if (!fs.existsSync(f)) { res.writeHead(404); return res.end('run npm run build first') }
    res.writeHead(200, { 'Content-Type': MIME[path.extname(f)] || 'application/octet-stream' })
    return fs.createReadStream(f).pipe(res)
  }
  const route = p.slice('/api/v1'.length)
  const method = req.method
  const cookie = req.headers.cookie || ''
  const sid = /sms_session=([^;]+)/.exec(cookie)?.[1]
  const authed = !!sid && state.sessions.has(sid)

  try {
    if (route === '/install/status' && method === 'GET') {
      return ok(res, state.installed ? { installed: true } : { installed: false, checks: [
        { key: 'php', label: 'PHP 版本 ≥ 8.1', ok: true, detail: '8.2.0' }, { key: 'pdo_sqlite', label: 'PDO SQLite', ok: true, detail: '' },
        { key: 'data_dir', label: '数据目录可写', ok: true, detail: '/data' }, { key: 'openssl', label: 'OpenSSL', ok: true, detail: '' } ] })
    }
    if (route === '/install' && method === 'POST') {
      if (state.installed) return fail(res, 409, 'already_installed', '系统已安装')
      const b = await readBody(req)
      if (!b || String(b.password || '').length < 10) return fail(res, 400, 'invalid_request', '参数错误', { fields: { password: '密码至少 10 位' } })
      state.installed = true; return ok(res, { installed: true }, 201)
    }
    if (route === '/auth/login' && method === 'POST') {
      const b = await readBody(req)
      if (b?.username === 'locked') return fail(res, 429, 'too_many_attempts', '登录失败次数过多', { retry_after: 600 })
      if (b?.username !== 'admin' || b?.password !== 'password123') return fail(res, 401, 'unauthenticated', '用户名或密码错误')
      const id = randomUUID(); state.sessions.add(id)
      audit('login', 'admin', '')
      return send(res, 200, { ok: true, data: { user: { id: 1, username: 'admin' }, csrf_token: CSRF, site_name: state.settings.site_name, timezone: state.settings.timezone } }, { 'Set-Cookie': `sms_session=${id}; Path=/; HttpOnly; SameSite=Lax` })
    }
    if (route === '/auth/me' && method === 'GET') {
      if (!authed) return fail(res, 401, 'unauthenticated', '未登录')
      return ok(res, { user: { id: 1, username: 'admin' }, csrf_token: CSRF, site_name: state.settings.site_name, timezone: state.settings.timezone })
    }
    if (!authed) return fail(res, 401, 'unauthenticated', '未登录')
    if (method !== 'GET' && req.headers['x-csrf-token'] !== CSRF) return fail(res, 403, 'csrf_failed', 'CSRF 校验失败')

    if (route === '/auth/logout' && method === 'POST') { state.sessions.delete(sid); return ok(res) }
    if (route === '/auth/password' && method === 'POST') {
      const b = await readBody(req)
      if (b.current_password !== 'password123') return fail(res, 400, 'invalid_request', '当前密码不正确', { fields: { current_password: '当前密码不正确' } })
      audit('password_change', 'admin', ''); return ok(res)
    }
    if (route === '/devices' && method === 'GET') return ok(res, { items: state.devices })
    if (route === '/devices/pairing' && method === 'POST') {
      const b = await readBody(req); const server_url = b?.server_url || `http://${req.headers.host}`
      const tok = randomUUID().replace(/-/g, '')
      return ok(res, { pairing_token: tok, expires_at: now() + 5 * 60_000, server_url, qr_payload: JSON.stringify({ server_url, pairing_token: tok }) }, 201)
    }
    let m
    if ((m = /^\/devices\/(\d+)$/.exec(route))) {
      const d = state.devices.find((x) => x.id === +m[1]); if (!d) return fail(res, 404, 'not_found', '设备不存在')
      if (method === 'PATCH') { const b = await readBody(req); d.device_name = b.device_name; emit('device.update', { device: d }); return ok(res) }
      if (method === 'DELETE') {
        state.devices = state.devices.filter((x) => x !== d)
        if (url.searchParams.get('purge') === '1') { state.conversations = state.conversations.filter((c) => c.device_id !== d.id); state.messages = state.messages.filter((x) => x.device_id !== d.id) }
        audit('device_revoke', d.device_name, ''); return ok(res)
      }
    }
    if (route === '/conversations' && method === 'GET') {
      const q = (url.searchParams.get('q') || '').toLowerCase(); const dev = +url.searchParams.get('device_id') || 0
      let list = sortedConvs().filter((c) => (!dev || c.device_id === dev) && (!q || c.phone_number.toLowerCase().includes(q) || (c.contact_name || '').toLowerCase().includes(q) || c.last_message.toLowerCase().includes(q)))
      return ok(res, paginate(list, url.searchParams))
    }
    if ((m = /^\/conversations\/(\d+)\/messages$/.exec(route)) && method === 'GET') {
      const c = state.conversations.find((x) => x.id === +m[1]); if (!c) return fail(res, 404, 'not_found', '会话不存在')
      const before = +url.searchParams.get('before_id') || Infinity; const limit = Math.min(200, +url.searchParams.get('limit') || 50)
      const all = state.messages.filter((x) => x.conversation_id === c.id && x.id < before).sort((a, b) => a.id - b.id)
      const items = all.slice(-limit)
      return ok(res, { items: items.sort((a, b) => a.timestamp - b.timestamp), has_more: all.length > limit })
    }
    if ((m = /^\/conversations\/(\d+)\/(read|pin)$/.exec(route)) && method === 'POST') {
      const c = state.conversations.find((x) => x.id === +m[1]); if (!c) return fail(res, 404, 'not_found', '会话不存在')
      if (m[2] === 'read') { c.unread_count = 0 } else { c.pinned = !!(await readBody(req)).pinned }
      emit('conversation.update', { conversation: c }); return ok(res)
    }
    if ((m = /^\/conversations\/(\d+)$/.exec(route)) && method === 'DELETE') {
      const c = state.conversations.find((x) => x.id === +m[1]); if (!c) return fail(res, 404, 'not_found', '会话不存在')
      state.conversations = state.conversations.filter((x) => x !== c); state.messages = state.messages.filter((x) => x.conversation_id !== c.id)
      emit('conversation.update', { deleted: true, id: c.id }); return ok(res)
    }
    if (route === '/messages/search' && method === 'GET') {
      const q = (url.searchParams.get('q') || '').toLowerCase(); if (!q) return fail(res, 400, 'invalid_request', 'q 必填', { fields: { q: '必填' } })
      const items = state.messages.filter((x) => x.content.toLowerCase().includes(q) || x.phone_number.includes(q)).sort((a, b) => b.timestamp - a.timestamp)
        .map((x) => ({ ...x, device_name: state.devices.find((d) => d.id === x.device_id)?.device_name }))
      return ok(res, paginate(items, url.searchParams))
    }
    if (route === '/messages/send' && method === 'POST') {
      const b = await readBody(req)
      if (state.settings.send_paused) return fail(res, 423, 'send_paused', '已紧急暂停发送')
      const rcpt = normPhone(b.recipient || '')
      if (!/^\+?[0-9]{3,20}$/.test(rcpt)) return fail(res, 400, 'invalid_request', '收件人格式不正确', { fields: { recipient: '格式不正确' } })
      if (!b.content || b.content.length > state.settings.max_content_length) return fail(res, 400, 'invalid_request', '内容长度不合法', { fields: { content: '长度不合法' } })
      const warnings = []
      if (rcpt.startsWith('+') && !rcpt.startsWith(state.settings.home_country_code)) warnings.push('international')
      if (/^[0-9]{4,6}$/.test(rcpt) || (/^(400|900|106)/.test(rcpt) && rcpt.length >= 7)) warnings.push('premium_suspect')
      if (warnings.length && !b.confirm_warnings) return fail(res, 422, 'confirmation_required', '该号码需要确认', { warnings })
      state.sendTimestamps = state.sendTimestamps.filter((t) => now() - t < 60000)
      if (state.sendTimestamps.length >= state.settings.send_rate_per_minute) return fail(res, 429, 'rate_limited', '发送过于频繁', { retry_after: 17 })
      state.sendTimestamps.push(now())
      let c = state.conversations.find((x) => x.device_id === b.device_id && x.phone_number === rcpt)
      if (!c) c = conv(b.device_id, rcpt, null)
      const task_id = randomUUID()
      const msg = addMsg(c, { content: b.content, direction: 'outgoing', status: 'queued', task_id, sim_slot: b.sim_slot ?? null })
      const task = { task_id, device_id: b.device_id, recipient: rcpt, content: b.content, sim_slot: b.sim_slot ?? null, status: 'queued', error_code: null, error_message: null, created_at: now(), claimed_at: null, completed_at: null, resolved_action: null }
      state.tasks[task_id] = task; audit('send_message', rcpt, '')
      emit('conversation.update', { conversation: c })
      // 模拟设备依次推进状态
      const steps = rcpt.endsWith('0000') ? ['claimed', 'sending', 'failed'] : ['claimed', 'sending', 'sent', 'delivered']
      steps.forEach((s, i) => setTimeout(() => {
        msg.status = s; task.status = s; if (s === 'failed') { msg.error_message = '运营商拒绝'; c.has_failed = true }
        emit('message.status', { message_id: msg.id, task_id, conversation_id: c.id, status: s, error_message: msg.error_message })
      }, 1000 * (i + 1)))
      return ok(res, { task, message: msg, conversation: c }, 201)
    }
    if ((m = /^\/tasks\/([^/]+)\/resolve$/.exec(route)) && method === 'POST') {
      const t = state.tasks[m[1]]; if (!t) return fail(res, 404, 'not_found', '任务不存在')
      if (t.status !== 'unknown') return fail(res, 409, 'not_resolvable', '任务不是未知状态')
      const b = await readBody(req); const msg = state.messages.find((x) => x.task_id === t.task_id)
      t.resolved_action = b.action
      if (b.action === 'mark_sent') { t.status = 'sent'; msg.status = 'sent' } else { t.status = 'failed'; msg.status = 'failed'; msg.error_message = b.action === 'resend' ? '已由管理员确认重发' : '管理员标记失败' }
      emit('message.status', { message_id: msg.id, task_id: t.task_id, conversation_id: msg.conversation_id, status: msg.status, error_message: msg.error_message })
      audit('resolve_task', t.task_id, b.action); return ok(res, { task: t })
    }
    if (route === '/settings' && method === 'GET') return ok(res, state.settings)
    if (route === '/settings' && method === 'PUT') {
      const b = await readBody(req); Object.assign(state.settings, b); audit('settings_update', '', Object.keys(b).join(','))
      emit('settings.update', state.settings); return ok(res, state.settings)
    }
    if (route === '/settings/emergency-pause' && method === 'POST') {
      const b = await readBody(req); state.settings.send_paused = !!b.paused; audit('emergency_pause', '', String(b.paused))
      emit('settings.update', { send_paused: state.settings.send_paused }); return ok(res, { send_paused: state.settings.send_paused })
    }
    if (route === '/audit-logs' && method === 'GET') {
      const a = url.searchParams.get('action'); return ok(res, paginate(state.audit.filter((x) => !a || x.action === a), url.searchParams))
    }
    if (route === '/export/messages' && method === 'GET') {
      const fmt = url.searchParams.get('format') === 'json' ? 'json' : 'csv'
      res.writeHead(200, { 'Content-Type': fmt === 'json' ? 'application/json' : 'text/csv; charset=utf-8', 'Content-Disposition': `attachment; filename="messages.${fmt}"` })
      return res.end(fmt === 'json' ? JSON.stringify(state.messages) : 'id,phone,content\n' + state.messages.map((x) => `${x.id},${x.phone_number},"${x.content.replace(/"/g, '""')}"`).join('\n'))
    }
    if (route === '/events' && method === 'GET') {
      if (process.env.MOCK_NO_SSE === '1') return fail(res, 503, 'database_busy', 'SSE 不可用（mock）')
      res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache', Connection: 'keep-alive' })
      const last = +req.headers['last-event-id'] || 0
      res.write(': hello\n\n')
      for (const e of state.events) if (e.id > last) res.write(`id: ${e.id}\nevent: ${e.event}\ndata: ${JSON.stringify(e.data)}\n\n`)
      state.clients.add(res)
      const ping = setInterval(() => res.write(': ping\n\n'), 15000)
      // 与真实服务端一致：约 55 秒后正常结束，浏览器自动重连（mock 里可用 MOCK_SSE_TTL 缩短）
      const ttl = setTimeout(() => res.end(), Number(process.env.MOCK_SSE_TTL || 55000))
      req.on('close', () => { clearInterval(ping); clearTimeout(ttl); state.clients.delete(res) })
      return
    }
    return fail(res, 404, 'not_found', '接口不存在')
  } catch (e) {
    return fail(res, 500, 'internal_error', String(e))
  }
})

// 调试入口：模拟手机收到新短信 / 设备心跳（POST /__mock/incoming）
server.on('request', () => {})
setInterval(() => {
  const d = state.devices[0]; if (!d) return
  d.last_seen = now(); d.online = true
  if (d.sync_status === 'running' && d.sync_synced < d.sync_total) { d.sync_synced = Math.min(d.sync_total, d.sync_synced + 100); emit('sync.progress', { device_id: d.id, sync_status: d.sync_status, sync_total: d.sync_total, sync_synced: d.sync_synced }) }
}, 3000)
globalThis.__mockIncoming = (cid, text) => { const c = state.conversations.find((x) => x.id === cid); const m = addMsg(c, { content: text, read: false }); emit('message.new', { message: m, conversation: c }) }
setInterval(() => { if (process.env.MOCK_INCOMING === '1') globalThis.__mockIncoming(1, '实时新短信 ' + new Date().toLocaleTimeString()) }, 8000)

server.listen(PORT, '127.0.0.1', () => console.log(`mock listening on http://127.0.0.1:${PORT} (dist: ${DIST})`))
