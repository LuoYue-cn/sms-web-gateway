/**
 * 类型化 HTTP 客户端：统一信封解包、CSRF 头、401 处理与错误转换。
 */
import type { ApiEnvelope, ApiErrorBody } from './types'

export const API_BASE: string = import.meta.env.VITE_API_BASE || '/api/v1'

export class ApiError extends Error {
  readonly status: number
  readonly code: string
  readonly details: Record<string, unknown>

  constructor(status: number, code: string, message: string, details?: Record<string, unknown>) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
    this.details = details ?? {}
  }

  /** 429 时的 retry_after（秒） */
  get retryAfter(): number | null {
    const v = this.details.retry_after
    return typeof v === 'number' ? v : null
  }

  /** 400 时 details.fields（字段 → 原因） */
  get fields(): Record<string, string> {
    const f = this.details.fields
    return f && typeof f === 'object' ? (f as Record<string, string>) : {}
  }

  /** 面向用户的完整提示，429 附带 retry_after */
  get displayMessage(): string {
    if (this.status === 429 && this.retryAfter != null) {
      return `${this.message}（请 ${this.retryAfter} 秒后重试）`
    }
    return this.message
  }
}

type Query = Record<string, string | number | boolean | null | undefined>

interface RequestOptions {
  query?: Query
  body?: unknown
  /** 为 true 时 401 不触发全局跳转登录（用于 auth/me、login 等） */
  skipUnauthorizedHook?: boolean
}

let csrfToken = ''
let unauthorizedHandler: (() => void) | null = null
let csrfRefresher: (() => Promise<void>) | null = null

export function setCsrfToken(token: string): void {
  csrfToken = token
}
export function getCsrfToken(): string {
  return csrfToken
}
/** 注册 401 全局处理（由 auth store 设置：清状态并跳转登录页） */
export function onUnauthorized(fn: () => void): void {
  unauthorizedHandler = fn
}
/** 手动触发 401 处理（例如 SSE 断线后探测到登录已失效） */
export function triggerUnauthorized(): void {
  unauthorizedHandler?.()
}
/** 注册 CSRF 令牌刷新（403 csrf_failed 时重新拉取 auth/me 并自动重试一次） */
export function onCsrfRefresh(fn: () => Promise<void>): void {
  csrfRefresher = fn
}

function buildUrl(path: string, query?: Query): string {
  const url = API_BASE + path
  if (!query) return url
  const sp = new URLSearchParams()
  for (const [k, v] of Object.entries(query)) {
    if (v === undefined || v === null || v === '') continue
    sp.set(k, String(v))
  }
  const qs = sp.toString()
  return qs ? `${url}?${qs}` : url
}

export async function request<T>(method: string, path: string, opts: RequestOptions = {}, retried = false): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json' }
  const init: RequestInit = { method, headers, credentials: 'same-origin' }
  if (method !== 'GET' && method !== 'HEAD') {
    if (csrfToken) headers['X-CSRF-Token'] = csrfToken
    if (opts.body !== undefined) {
      headers['Content-Type'] = 'application/json'
      init.body = JSON.stringify(opts.body)
    }
  }

  let res: Response
  try {
    res = await fetch(buildUrl(path, opts.query), init)
  } catch {
    throw new ApiError(0, 'network_error', '网络连接失败，请检查网络后重试')
  }

  let envelope: ApiEnvelope<T> | null = null
  const text = await res.text()
  if (text) {
    try {
      envelope = JSON.parse(text) as ApiEnvelope<T>
    } catch {
      envelope = null
    }
  }

  if (res.ok && envelope && envelope.ok) {
    return (envelope.data ?? ({} as T)) as T
  }

  const err: ApiErrorBody | undefined = envelope?.error
  const apiErr = new ApiError(
    res.status,
    err?.code ?? (res.status === 503 ? 'database_busy' : 'http_error'),
    err?.message ?? `请求失败（HTTP ${res.status}）`,
    err?.details,
  )
  if (res.status === 403 && apiErr.code === 'csrf_failed' && !retried && csrfRefresher) {
    try {
      await csrfRefresher()
    } catch {
      throw apiErr
    }
    return request<T>(method, path, opts, true)
  }
  if (res.status === 401 && !opts.skipUnauthorizedHook && unauthorizedHandler) {
    unauthorizedHandler()
  }
  throw apiErr
}

export const http = {
  get: <T>(path: string, query?: Query, o?: Pick<RequestOptions, 'skipUnauthorizedHook'>) =>
    request<T>('GET', path, { query, ...o }),
  post: <T>(path: string, body?: unknown, o?: Pick<RequestOptions, 'skipUnauthorizedHook'>) =>
    request<T>('POST', path, { body: body ?? {}, ...o }),
  put: <T>(path: string, body?: unknown) => request<T>('PUT', path, { body: body ?? {} }),
  patch: <T>(path: string, body?: unknown) => request<T>('PATCH', path, { body: body ?? {} }),
  delete: <T>(path: string, query?: Query) => request<T>('DELETE', path, { query }),
}

/** 下载（导出）链接：GET + Session Cookie，浏览器直接导航即可 */
export function downloadUrl(path: string, query?: Query): string {
  return buildUrl(path, query)
}

/** 带 Session Cookie 的文件下载（导出）。失败时按统一信封抛出 ApiError，便于界面展示 error.message。 */
export async function downloadFile(path: string, query?: Query, fallbackName = 'download'): Promise<{ blob: Blob; filename: string }> {
  let res: Response
  try {
    res = await fetch(buildUrl(path, query), { credentials: 'same-origin' })
  } catch {
    throw new ApiError(0, 'network_error', '网络连接失败，请检查网络后重试')
  }
  if (!res.ok) {
    let err: ApiErrorBody | undefined
    try {
      err = ((await res.json()) as ApiEnvelope<unknown>).error
    } catch {
      err = undefined
    }
    if (res.status === 401 && unauthorizedHandler) unauthorizedHandler()
    throw new ApiError(res.status, err?.code ?? 'http_error', err?.message ?? `导出失败（HTTP ${res.status}）`, err?.details)
  }
  const cd = res.headers.get('Content-Disposition') ?? ''
  const m = /filename\*?=(?:UTF-8'')?"?([^";]+)"?/i.exec(cd)
  let filename = fallbackName
  if (m) {
    try {
      filename = decodeURIComponent(m[1])
    } catch {
      filename = m[1]
    }
  }
  return { blob: await res.blob(), filename }
}
