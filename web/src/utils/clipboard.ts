/**
 * 复制文本。局域网里用 http 访问时浏览器不算「安全上下文」，navigator.clipboard 不可用，
 * 所以失败时回退到 execCommand('copy')。两者都必须在用户点击的同一次事件里调用。
 */
export async function copyText(text: string): Promise<boolean> {
  if (typeof navigator !== 'undefined' && navigator.clipboard && window.isSecureContext) {
    try {
      await navigator.clipboard.writeText(text)
      return true
    } catch {
      /* 权限被拒等情况，继续回退 */
    }
  }
  return legacyCopy(text)
}

function legacyCopy(text: string): boolean {
  const prev = document.activeElement as HTMLElement | null
  const ta = document.createElement('textarea')
  ta.value = text
  ta.setAttribute('readonly', '')
  ta.setAttribute('aria-hidden', 'true')
  ta.style.cssText = 'position:fixed;top:0;left:-9999px;opacity:0;font-size:16px'
  document.body.appendChild(ta)
  ta.focus({ preventScroll: true })
  ta.select()
  ta.setSelectionRange(0, text.length)
  let ok = false
  try {
    ok = document.execCommand('copy')
  } catch {
    ok = false
  }
  ta.remove()
  prev?.focus?.({ preventScroll: true })
  return ok
}
