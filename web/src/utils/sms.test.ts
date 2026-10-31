import { describe, expect, it } from 'vitest'
import { checkRecipient, excerptAround, normalizePhone, senderTag, splitCode, splitHighlight, simLabel } from './sms'
import { formatAgo, formatCountdown, formatDayDivider, formatRelative, formatDateTime, dayKey } from './time'

describe('号码校验', () => {
  it('规范化去掉空格、横线、括号、点', () => {
    expect(normalizePhone('+81 90-1234.5678')).toBe('+819012345678')
    expect(normalizePhone('(010) 8888 6666')).toBe('01088886666')
  })
  it('支持国际号码，不强制大陆格式', () => {
    expect(checkRecipient('+819012345678')).toEqual({ ok: true, value: '+819012345678' })
    expect(checkRecipient('10086')).toEqual({ ok: true, value: '10086' })
  })
  it('拒绝多个收件人与非法字符', () => {
    expect(checkRecipient('13800138000,13900139000').ok).toBe(false)
    expect(checkRecipient('138;139').ok).toBe(false)
    expect(checkRecipient('abc').ok).toBe(false)
    expect(checkRecipient('').ok).toBe(false)
    expect(checkRecipient('12').ok).toBe(false)
  })
})

describe('高亮', () => {
  it('按大小写不敏感切片', () => {
    expect(splitHighlight('Hello hello', 'HELLO')).toEqual([
      { text: 'Hello', hit: true },
      { text: ' ', hit: false },
      { text: 'hello', hit: true },
    ])
  })
  it('空关键字原样返回', () => {
    expect(splitHighlight('abc', '')).toEqual([{ text: 'abc', hit: false }])
  })
  it('摘要包含命中处', () => {
    const t = 'x'.repeat(300) + 'NEEDLE' + 'y'.repeat(300)
    expect(excerptAround(t, 'needle')).toContain('NEEDLE')
  })
})

describe('时间', () => {
  // 2026-10-21T00:30:00Z = 上海 08:30，纽约(EDT) 20 日 20:30
  const ts = Date.UTC(2026, 9, 21, 0, 30, 0)
  it('按时区格式化', () => {
    expect(formatDateTime(ts, 'Asia/Shanghai')).toBe('2026-10-21 08:30:00')
    expect(formatDateTime(ts, 'America/New_York')).toBe('2026-10-20 20:30:00')
    expect(formatDateTime(ts, 'UTC')).toBe('2026-10-21 00:30:00')
  })
  it('非法时区回退浏览器本地，不抛异常', () => {
    expect(() => formatDateTime(ts, 'Not/AZone')).not.toThrow()
  })
  it('相对时间', () => {
    const now = Date.UTC(2026, 9, 21, 6, 0, 0) // 上海 14:00
    expect(formatRelative(now - 30_000, 'Asia/Shanghai', now)).toBe('刚刚')
    expect(formatRelative(now - 5 * 60_000, 'Asia/Shanghai', now)).toBe('5 分钟前')
    expect(formatRelative(ts, 'Asia/Shanghai', now)).toBe('08:30')
    expect(formatRelative(ts - 86_400_000, 'Asia/Shanghai', now)).toBe('昨天')
    expect(formatRelative(ts - 40 * 86_400_000, 'Asia/Shanghai', now)).toBe('9月11日')
    expect(formatRelative(ts - 400 * 86_400_000, 'Asia/Shanghai', now)).toMatch(/^2025\//)
  })
  it('日期分隔条与日期键', () => {
    const now = Date.UTC(2026, 9, 21, 6, 0, 0)
    expect(formatDayDivider(ts, 'Asia/Shanghai', now)).toBe('今天')
    expect(formatDayDivider(ts - 86_400_000, 'Asia/Shanghai', now)).toBe('昨天')
    expect(dayKey(ts, 'Asia/Shanghai')).not.toBe(dayKey(ts, 'America/New_York'))
  })
  it('设备"N 秒前"与倒计时', () => {
    const now = 1_000_000_000_000
    expect(formatAgo(now - 12_000, now)).toBe('12 秒前')
    expect(formatAgo(null, now)).toBe('从未')
    expect(formatCountdown(299_100)).toBe('05:00')
    expect(formatCountdown(-5)).toBe('00:00')
  })
  it('SIM 标识', () => {
    expect(simLabel(0)).toBe('SIM 1')
    expect(simLabel(null)).toBe('')
  })
})

describe('验证码高亮', () => {
  it('把验证码从正文里切出来', () => {
    expect(splitCode('【京东】验证码 835261，5分钟内有效', '835261')).toEqual(['【京东】验证码 ', '835261', '，5分钟内有效'])
  })
  it('不会命中更长数字串的一部分', () => {
    expect(splitCode('订单 18352610 验证码 835261', '835261')).toEqual(['订单 18352610 验证码 ', '835261', ''])
    expect(splitCode('只有 18352610', '835261')).toBeNull()
  })
  it('认分组写法 123 456 / 123-456', () => {
    expect(splitCode('Your code is 394 028.', '394028')).toEqual(['Your code is ', '394 028', '.'])
    expect(splitCode('code 394-028', '394028')?.[1]).toBe('394-028')
  })
  it('空验证码、找不到时返回 null（不高亮）', () => {
    expect(splitCode('hello', '')).toBeNull()
    expect(splitCode('hello', '1234')).toBeNull()
  })
})

describe('短信签名', () => {
  it('提取【】与[]里的签名', () => {
    expect(senderTag('【京东】验证码 1')).toBe('京东')
    expect(senderTag('[Apple] code')).toBe('Apple')
    expect(senderTag('没有签名')).toBe('')
  })
})
