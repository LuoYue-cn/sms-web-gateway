/**
 * 把 Naive UI 的 message API 暴露给 store / composable 使用（由 AppShell 注册）。
 */
import type { MessageApi } from 'naive-ui'
import { ApiError } from '@/api/client'

let api: MessageApi | null = null

export function setMessageApi(m: MessageApi): void {
  api = m
}

export function errorText(e: unknown, fallback = '操作失败'): string {
  if (e instanceof ApiError) return e.displayMessage
  if (e instanceof Error && e.message) return e.message
  return fallback
}

export const notify = {
  success: (msg: string) => api?.success(msg),
  info: (msg: string) => api?.info(msg),
  warning: (msg: string) => api?.warning(msg),
  error: (msg: string) => api?.error(msg, { duration: 5000 }),
  /** 统一错误展示：error.message；429 附带 retry_after。未登录(401)由全局跳转处理，这里不重复提示 */
  fromError(e: unknown, fallback = '操作失败'): void {
    if (e instanceof ApiError && e.status === 401 && e.code === 'unauthenticated') return
    api?.error(errorText(e, fallback), { duration: 5000 })
  },
}
