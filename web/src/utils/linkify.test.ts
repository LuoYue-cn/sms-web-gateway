import { describe, expect, it } from 'vitest'
import { linkify, richSegments } from './linkify'

const links = (t: string): string[] => linkify(t).filter((s) => s.href).map((s) => s.text)
const hrefs = (t: string): string[] => linkify(t).flatMap((s) => (s.href ? [s.href] : []))

describe('linkify', () => {
  it('识别带 http(s):// 的链接，中文标点是链接的结束位置', () => {
    expect(links('详情请访问https://t.cn/A6xYz9，谢谢')).toEqual(['https://t.cn/A6xYz9'])
    expect(links('点击 http://m.jd.com/abc?x=1&y=2。')).toEqual(['http://m.jd.com/abc?x=1&y=2'])
  })

  it('识别没写 http:// 的短链和 www 域名，补成 https', () => {
    expect(hrefs('取件请点 t.cn/AbC123 查看')).toEqual(['https://t.cn/AbC123'])
    expect(hrefs('官网 www.example.com 欢迎')).toEqual(['https://www.example.com/'])
    expect(hrefs('请登录 c.tb.cn/h.abcd 领取')).toEqual(['https://c.tb.cn/h.abcd'])
  })

  it('句子末尾的标点、不成对的括号不算链接', () => {
    expect(links('see https://a.com/x.')).toEqual(['https://a.com/x'])
    expect(links('(https://a.com/x)')).toEqual(['https://a.com/x'])
    expect(links('wiki https://a.com/f_(b) ok')).toEqual(['https://a.com/f_(b)'])
    expect(links('really? https://a.com/x?!')).toEqual(['https://a.com/x'])
  })

  it('不会把邮箱、小数、版本号、日期、普通文字当成链接', () => {
    expect(links('联系 foo@bar.com 即可')).toEqual([])
    expect(links('折扣 3.5折，余额 1234.56 元')).toEqual([])
    expect(links('升级到 v1.2.3 版本')).toEqual([])
    expect(links('2026-10-25 在哈啰')).toEqual([])
    expect(links('域名:qayland即将过期')).toEqual([])
    expect(links('文件 readme.md')).toEqual([])
  })

  it('永远不产生 javascript: / data: 等危险协议', () => {
    for (const t of ['javascript:alert(1)', 'data:text/html;base64,AAAA', 'vbscript:x', 'JaVaScRiPt://a.com/%0Aalert(1)']) {
      for (const h of hrefs(t)) expect(h).toMatch(/^https?:\/\//)
    }
    expect(links('javascript:alert(1)')).toEqual([])
    expect(links('data:text/html;base64,AAAA')).toEqual([])
  })

  it('所有片段拼回去与原文完全一致（不丢字、不改字）', () => {
    const samples = [
      '【京东】验证码 835261，详见 https://t.cn/A6xYz9，请勿泄露。',
      '前缀www.a.com/路径后缀 and t.cn/x). end',
      '纯文本没有链接\n第二行 3.5折',
      'a b https://x.y/z?(1)(2))))',
    ]
    for (const s of samples) expect(linkify(s).map((x) => x.text).join('')).toBe(s)
  })

  it('超长链接不处理，避免页面被撑坏', () => {
    const long = 'https://a.com/' + 'x'.repeat(600)
    expect(links(long)).toEqual([])
  })

  it('空串与纯文本', () => {
    expect(linkify('')).toEqual([{ text: '' }])
    expect(linkify('hello')).toEqual([{ text: 'hello' }])
  })
})

describe('richSegments', () => {
  it('同时高亮验证码并保留链接', () => {
    const segs = richSegments('验证码 835261，链接 https://t.cn/Abc 有效', '835261')
    expect(segs.map((s) => [s.text, !!s.code, !!s.href])).toEqual([
      ['验证码 ', false, false],
      ['835261', true, false],
      ['，链接 ', false, false],
      ['https://t.cn/Abc', false, true],
      [' 有效', false, false],
    ])
  })

  it('链接里的数字不会被当成验证码高亮', () => {
    const segs = richSegments('点 https://a.com/835261 验证码 835261', '835261')
    const marked = segs.filter((s) => s.code)
    expect(marked).toHaveLength(1)
    expect(segs.find((s) => s.href)?.text).toBe('https://a.com/835261')
    expect(segs.findIndex((s) => s.code)).toBeGreaterThan(segs.findIndex((s) => s.href))
  })

  it('没有验证码时等同于 linkify；找不到验证码时不高亮', () => {
    expect(richSegments('hi t.cn/x', null)).toEqual(linkify('hi t.cn/x'))
    expect(richSegments('hello', '123456').some((s) => s.code)).toBe(false)
  })
})
