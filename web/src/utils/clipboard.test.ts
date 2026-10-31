import { afterEach, describe, expect, it, vi } from 'vitest'
import { copyText } from './clipboard'

/** 默认的 node 测试环境没有 DOM，这里手工搭一个刚好够 legacyCopy 用的假 document。 */
function fakeDom(execResult: boolean) {
  const created: { removed: boolean }[] = []
  const exec = vi.fn().mockReturnValue(execResult)
  vi.stubGlobal('document', {
    activeElement: null,
    body: { appendChild: vi.fn() },
    execCommand: exec,
    createElement: () => {
      const rec = { removed: false }
      created.push(rec)
      return {
        value: '',
        style: {},
        setAttribute: vi.fn(),
        focus: vi.fn(),
        select: vi.fn(),
        setSelectionRange: vi.fn(),
        remove: () => (rec.removed = true),
      }
    },
  })
  return { exec, created }
}

afterEach(() => vi.unstubAllGlobals())

describe('copyText', () => {
  it('安全上下文里优先用 navigator.clipboard', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    vi.stubGlobal('window', { isSecureContext: true })
    vi.stubGlobal('navigator', { clipboard: { writeText } })
    const dom = fakeDom(true)
    expect(await copyText('123456')).toBe(true)
    expect(writeText).toHaveBeenCalledWith('123456')
    expect(dom.exec).not.toHaveBeenCalled()
  })

  it('http 局域网（非安全上下文）回退到 execCommand，并清理临时输入框', async () => {
    vi.stubGlobal('window', { isSecureContext: false })
    vi.stubGlobal('navigator', {})
    const dom = fakeDom(true)
    expect(await copyText('654321')).toBe(true)
    expect(dom.exec).toHaveBeenCalledWith('copy')
    expect(dom.created).toHaveLength(1)
    expect(dom.created[0].removed).toBe(true)
  })

  it('clipboard 被拒绝时也回退', async () => {
    vi.stubGlobal('window', { isSecureContext: true })
    vi.stubGlobal('navigator', { clipboard: { writeText: vi.fn().mockRejectedValue(new Error('denied')) } })
    const dom = fakeDom(true)
    expect(await copyText('1')).toBe(true)
    expect(dom.exec).toHaveBeenCalled()
  })

  it('两条路都失败时返回 false，由界面把验证码直接显示出来', async () => {
    vi.stubGlobal('window', { isSecureContext: false })
    vi.stubGlobal('navigator', {})
    fakeDom(false)
    expect(await copyText('1')).toBe(false)
  })
})
