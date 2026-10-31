import { splitCode } from './sms'

/**
 * 把短信正文切成「普通文本 / 链接 / 验证码」片段，由模板逐段渲染。
 * 刻意不生成 HTML 字符串（不用 v-html）：短信是外人发来的，任何内容都只能作为文本节点或 <a> 的属性值出现。
 */
export interface Seg {
  text: string
  /** 只会是 http(s) 地址 */
  href?: string
  code?: boolean
}

const TLDS =
  'com|cn|net|org|top|cc|xyz|vip|me|co|io|link|ly|tv|info|biz|club|site|online|shop|app|gov|edu|hk|tw|jp|tech|store|live|fun|icu|pro|work|wang|mobi|gl'
/** URL 里允许出现的字符。只含 ASCII，所以中文标点、中文字符天然就是链接的结束位置 */
const URL_CHARS = "[A-Za-z0-9\\-._~:/?#\\[\\]@!$&'()*+,;=%]"

const LINK_RE = new RegExp(
  '(?<![A-Za-z0-9@._%-])(?:' +
    `(?:https?://|www\\.)${URL_CHARS}+` +
    '|' +
    // 没写 http:// 的域名（短信里很常见，如 t.cn/AbC123）：必须以常见后缀结尾，且前面不能是 @（排除邮箱）
    `(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+(?:${TLDS})(?![A-Za-z0-9-])(?:[/?#]${URL_CHARS}*)?` +
    ')',
  'gi',
)

const MAX_URL = 500

/** 去掉紧跟在链接后面、其实是句子标点的字符；括号只在不成对时才去掉 */
function trimTrailing(u: string): string {
  let s = u
  for (;;) {
    const last = s[s.length - 1]
    if (last && '.,;:!?\'"'.includes(last)) {
      s = s.slice(0, -1)
    } else if (last === ')' && count(s, ')') > count(s, '(')) {
      s = s.slice(0, -1)
    } else if (last === ']' && count(s, ']') > count(s, '[')) {
      s = s.slice(0, -1)
    } else {
      return s
    }
  }
}
function count(s: string, ch: string): number {
  let n = 0
  for (const c of s) if (c === ch) n++
  return n
}

function toHref(raw: string): string | null {
  const href = /^https?:\/\//i.test(raw) ? raw : `https://${raw}`
  try {
    const u = new URL(href)
    if (u.protocol !== 'http:' && u.protocol !== 'https:') return null
    if (!u.hostname.includes('.')) return null
    return u.href
  } catch {
    return null
  }
}

export function linkify(text: string): Seg[] {
  const out: Seg[] = []
  let last = 0
  LINK_RE.lastIndex = 0
  for (let m = LINK_RE.exec(text); m !== null; m = LINK_RE.exec(text)) {
    const raw = trimTrailing(m[0])
    const href = raw.length > 0 && raw.length <= MAX_URL ? toHref(raw) : null
    if (href === null) continue
    if (m.index > last) out.push({ text: text.slice(last, m.index) })
    out.push({ text: raw, href })
    last = m.index + raw.length
    // trimTrailing 去掉的字符仍属于后面的普通文本
    LINK_RE.lastIndex = last
  }
  if (last < text.length) out.push({ text: text.slice(last) })
  return out.length ? out : [{ text }]
}

/** 链接 + 验证码高亮。验证码只在普通文本里找，不会把链接里的数字当成验证码。 */
export function richSegments(content: string, code?: string | null): Seg[] {
  const base = linkify(content)
  if (!code) return base
  const out: Seg[] = []
  let done = false
  for (const s of base) {
    if (done || s.href) {
      out.push(s)
      continue
    }
    const parts = splitCode(s.text, code)
    if (!parts) {
      out.push(s)
      continue
    }
    done = true
    if (parts[0]) out.push({ text: parts[0] })
    out.push({ text: parts[1], code: true })
    if (parts[2]) out.push({ text: parts[2] })
  }
  return out
}
