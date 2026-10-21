/**
 * 时间格式化。服务端给的是 Unix 毫秒（UTC），这里按指定 IANA 时区格式化；
 * 时区为空 / 'local' / 'browser' / 非法值时回退到浏览器本地时区。
 */

const fmtCache = new Map<string, Intl.DateTimeFormat>()

export function resolveTimeZone(tz?: string | null): string | undefined {
  if (!tz || tz === 'local' || tz === 'browser') return undefined
  try {
    new Intl.DateTimeFormat('zh-CN', { timeZone: tz })
    return tz
  } catch {
    return undefined
  }
}

export function browserTimeZone(): string {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC'
  } catch {
    return 'UTC'
  }
}

function formatter(tz: string | undefined): Intl.DateTimeFormat {
  const key = tz ?? '__local__'
  let f = fmtCache.get(key)
  if (!f) {
    f = new Intl.DateTimeFormat('zh-CN', {
      timeZone: tz,
      year: 'numeric',
      month: 'numeric',
      day: 'numeric',
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
      weekday: 'short',
      hourCycle: 'h23',
    })
    fmtCache.set(key, f)
  }
  return f
}

export interface DateParts {
  y: number
  m: number
  d: number
  hh: number
  mm: number
  ss: number
  weekday: string
}

export function dateParts(ts: number, tz?: string | null): DateParts {
  const parts = formatter(resolveTimeZone(tz)).formatToParts(new Date(ts))
  const get = (t: string): string => parts.find((p) => p.type === t)?.value ?? ''
  return {
    y: Number(get('year')),
    m: Number(get('month')),
    d: Number(get('day')),
    hh: Number(get('hour')) % 24,
    mm: Number(get('minute')),
    ss: Number(get('second')),
    weekday: get('weekday'),
  }
}

const p2 = (n: number): string => String(n).padStart(2, '0')

/** 自 1970-01-01 起的"本地日"序号，用于计算日期差 */
function dayNumber(p: DateParts): number {
  return Math.floor(Date.UTC(p.y, p.m - 1, p.d) / 86400000)
}

export function dayKey(ts: number, tz?: string | null): string {
  const p = dateParts(ts, tz)
  return `${p.y}-${p.m}-${p.d}`
}

/** 2026-10-21 13:05:09 */
export function formatDateTime(ts: number | null | undefined, tz?: string | null): string {
  if (ts == null) return '—'
  const p = dateParts(ts, tz)
  return `${p.y}-${p2(p.m)}-${p2(p.d)} ${p2(p.hh)}:${p2(p.mm)}:${p2(p.ss)}`
}

/** 13:05 */
export function formatClock(ts: number, tz?: string | null): string {
  const p = dateParts(ts, tz)
  return `${p2(p.hh)}:${p2(p.mm)}`
}

/** 会话列表用的相对时间 */
export function formatRelative(ts: number | null | undefined, tz?: string | null, now = Date.now()): string {
  if (ts == null) return ''
  const diff = now - ts
  if (diff >= 0 && diff < 60_000) return '刚刚'
  if (diff >= 0 && diff < 3_600_000) return `${Math.floor(diff / 60_000)} 分钟前`
  const p = dateParts(ts, tz)
  const n = dateParts(now, tz)
  const days = dayNumber(n) - dayNumber(p)
  if (days === 0) return `${p2(p.hh)}:${p2(p.mm)}`
  if (days === 1) return '昨天'
  if (days > 1 && days < 7) return p.weekday
  if (p.y === n.y) return `${p.m}月${p.d}日`
  return `${p.y}/${p.m}/${p.d}`
}

/** 聊天里的日期分隔条 */
export function formatDayDivider(ts: number, tz?: string | null, now = Date.now()): string {
  const p = dateParts(ts, tz)
  const n = dateParts(now, tz)
  const days = dayNumber(n) - dayNumber(p)
  if (days === 0) return '今天'
  if (days === 1) return '昨天'
  const base = p.y === n.y ? `${p.m}月${p.d}日` : `${p.y}年${p.m}月${p.d}日`
  return `${base} ${p.weekday}`
}

/** 设备卡片上的"N 秒前" */
export function formatAgo(ts: number | null | undefined, now = Date.now(), never = '从未'): string {
  if (ts == null || ts <= 0) return never
  const s = Math.max(0, Math.floor((now - ts) / 1000))
  if (s < 5) return '刚刚'
  if (s < 60) return `${s} 秒前`
  if (s < 3600) return `${Math.floor(s / 60)} 分钟前`
  if (s < 86400) return `${Math.floor(s / 3600)} 小时前`
  return `${Math.floor(s / 86400)} 天前`
}

/** 倒计时 mm:ss */
export function formatCountdown(ms: number): string {
  const total = Math.max(0, Math.ceil(ms / 1000))
  return `${p2(Math.floor(total / 60))}:${p2(total % 60)}`
}

/** 常用时区 + 运行环境支持的全部 IANA 时区 */
export function listTimeZones(): string[] {
  const common = ['Asia/Shanghai', 'Asia/Hong_Kong', 'Asia/Taipei', 'Asia/Tokyo', 'Asia/Seoul', 'Asia/Singapore', 'UTC']
  let all: string[] = []
  try {
    all = Intl.supportedValuesOf('timeZone')
  } catch {
    all = []
  }
  const set = new Set<string>([browserTimeZone(), ...common, ...all])
  return [...set]
}
