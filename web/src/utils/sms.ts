import type { Conversation, MessageStatus, Message } from '@/api/types'

export interface StatusMeta {
  label: string
  /** Naive UI NTag type */
  type: 'default' | 'info' | 'success' | 'warning' | 'error'
  hint?: string
}

export const STATUS_META: Record<MessageStatus, StatusMeta> = {
  received: { label: '已接收', type: 'default' },
  queued: { label: '排队中', type: 'default', hint: '服务器已创建发送任务，等待手机领取' },
  claimed: { label: '已领取', type: 'info', hint: '手机已领取任务，即将发送' },
  sending: { label: '发送中', type: 'info', hint: '手机正在调用系统短信接口发送' },
  sent: { label: '已发送', type: 'success', hint: '手机已成功提交发送，不代表对方已收到' },
  delivered: { label: '已送达', type: 'success', hint: '收到运营商送达回执' },
  failed: { label: '失败', type: 'error' },
  unknown: { label: '结果未知', type: 'warning', hint: '手机在发送过程中中断，无法确定是否已发出，需要管理员确认' },
}

export function statusMeta(s: MessageStatus): StatusMeta {
  return STATUS_META[s] ?? { label: s, type: 'default' }
}

/** 在途状态（需要继续关注的发送状态） */
export function isInFlight(s: MessageStatus): boolean {
  return s === 'queued' || s === 'claimed' || s === 'sending'
}

export function displayName(c: Pick<Conversation, 'contact_name' | 'phone_number'>): string {
  const n = c.contact_name?.trim()
  return n ? n : c.phone_number
}

export function messageDisplayName(m: Pick<Message, 'contact_name' | 'phone_number'>): string {
  return displayName(m)
}

/** SIM 卡槽标识：API 的 slot 从 0 起，界面显示为 SIM 1 / SIM 2 */
export function simLabel(slot: number | null | undefined): string {
  return slot == null || slot < 0 ? '' : `SIM ${slot + 1}`
}

/** 与服务端一致的号码规范化：去掉空格、-、()、.，保留开头 + */
export function normalizePhone(input: string): string {
  return input.replace(/[\s\-().]/g, '')
}

const SEND_PHONE_RE = /^\+?[0-9]{3,20}$/

export type RecipientCheck = { ok: true; value: string } | { ok: false; reason: string }

/**
 * 发送号码的前端预校验（服务端仍为准）。
 * 不强制中国大陆格式，支持 +81… 等国际号码；只允许单个收件人。
 */
export function checkRecipient(input: string): RecipientCheck {
  const raw = input.trim()
  if (!raw) return { ok: false, reason: '请输入收件人号码' }
  if (/[,;，；、]/.test(raw)) return { ok: false, reason: '一次只能发送给一个号码，不支持多个收件人' }
  const v = normalizePhone(raw)
  if (!SEND_PHONE_RE.test(v)) return { ok: false, reason: '号码格式不正确：仅允许数字，可以以 + 开头，长度 3–20 位' }
  return { ok: true, value: v }
}

export const WARNING_TEXT: Record<string, string> = {
  international: '该号码为国际号码，发送可能产生国际短信资费。',
  premium_suspect: '该号码疑似高资费短号 / 服务号码，发送可能产生额外费用。',
}

export function warningText(code: string): string {
  return WARNING_TEXT[code] ?? `需要确认：${code}`
}

/** 头像底色（按名称哈希，固定色板） */
const AVATAR_COLORS = ['#2f7cf6', '#16a34a', '#d97706', '#9333ea', '#db2777', '#0891b2', '#dc2626', '#4f46e5']
export function avatarColor(seed: string): string {
  let h = 0
  for (let i = 0; i < seed.length; i++) h = (h * 31 + seed.charCodeAt(i)) >>> 0
  return AVATAR_COLORS[h % AVATAR_COLORS.length]
}

export function avatarText(name: string): string {
  const t = name.trim()
  if (!t) return '#'
  const ch = Array.from(t)[0]
  return /[0-9+]/.test(ch) ? '#' : ch.toUpperCase()
}

export interface HighlightSegment {
  text: string
  hit: boolean
}

/** 把文本按关键字切成片段，用于搜索结果高亮（全部按文本渲染，不用 v-html） */
export function splitHighlight(text: string, q: string): HighlightSegment[] {
  const needle = q.trim()
  if (!needle) return [{ text, hit: false }]
  const lower = text.toLowerCase()
  const n = needle.toLowerCase()
  const out: HighlightSegment[] = []
  let i = 0
  while (i < text.length) {
    const idx = lower.indexOf(n, i)
    if (idx < 0) {
      out.push({ text: text.slice(i), hit: false })
      break
    }
    if (idx > i) out.push({ text: text.slice(i, idx), hit: false })
    out.push({ text: text.slice(idx, idx + n.length), hit: true })
    i = idx + n.length
  }
  return out.length ? out : [{ text, hit: false }]
}

/** 长文本截取到命中处附近，避免搜索结果过长 */
export function excerptAround(text: string, q: string, radius = 60): string {
  const idx = text.toLowerCase().indexOf(q.trim().toLowerCase())
  if (idx <= radius) return text.length > radius * 3 ? text.slice(0, radius * 3) + '…' : text
  const start = idx - radius
  const end = Math.min(text.length, idx + radius * 2)
  return '…' + text.slice(start, end) + (end < text.length ? '…' : '')
}

/** 短信签名，如「【京东】」里的京东；没有则为空串（与服务端规则一致） */
export function senderTag(content: string): string {
  const m = /[【[]([^】\]\n]{1,16})[】\]]/.exec(content)
  return m ? m[1].trim() : ''
}

const isAlnum = (ch: string | undefined): boolean => ch !== undefined && /[A-Za-z0-9]/.test(ch)

/**
 * 在短信正文里定位验证码，切成 [前, 验证码, 后]，用于高亮。
 * 只匹配两侧不是字母数字的完整串；也认「123 456」「123-456」这种分组写法。找不到返回 null（不高亮）。
 */
export function splitCode(content: string, code: string): [string, string, string] | null {
  if (!code) return null
  const patterns = [code]
  if (/^[0-9]{6}$/.test(code)) patterns.push(`${code.slice(0, 3)} ${code.slice(3)}`, `${code.slice(0, 3)}-${code.slice(3)}`)
  for (const pat of patterns) {
    let from = 0
    for (;;) {
      const i = content.indexOf(pat, from)
      if (i < 0) break
      if (!isAlnum(content[i - 1]) && !isAlnum(content[i + pat.length])) {
        return [content.slice(0, i), content.slice(i, i + pat.length), content.slice(i + pat.length)]
      }
      from = i + 1
    }
  }
  return null
}
