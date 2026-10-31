import { copyText } from '@/utils/clipboard'
import { notify } from '@/utils/notify'

/** 复制验证码并给出明确反馈。复制失败时把验证码直接显示在提示里，用户仍然可以手动抄。 */
export async function copyCode(code: string): Promise<boolean> {
  const ok = await copyText(code)
  if (ok) notify.success(`已复制验证码 ${code}`)
  else notify.warning(`浏览器不允许自动复制，验证码是 ${code}`)
  return ok
}
