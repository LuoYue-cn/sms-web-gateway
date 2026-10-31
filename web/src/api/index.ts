import { http, downloadFile } from './client'
import type {
  AiConfig,
  AiConfigPatch,
  AiPreview,
  AiTestResult,
  AuditLog,
  AuthInfo,
  Conversation,
  Device,
  HealthReport,
  InstallPayload,
  InstallStatus,
  Message,
  MessagesPage,
  Paged,
  PairingInfo,
  RecentCode,
  Report,
  ResolveAction,
  SearchMessage,
  SendPayload,
  SendResult,
  Settings,
  StatsRange,
  StatsReport,
  Task,
} from './types'

export * from './client'
export * from './types'

export const installApi = {
  status: () => http.get<InstallStatus>('/install/status', undefined, { skipUnauthorizedHook: true }),
  install: (p: InstallPayload) => http.post<{ installed: boolean }>('/install', p, { skipUnauthorizedHook: true }),
}

export const authApi = {
  login: (username: string, password: string) =>
    http.post<AuthInfo>('/auth/login', { username, password }, { skipUnauthorizedHook: true }),
  logout: () => http.post<Record<string, never>>('/auth/logout'),
  me: () => http.get<AuthInfo>('/auth/me', undefined, { skipUnauthorizedHook: true }),
  changePassword: (current_password: string, new_password: string) =>
    http.post<Record<string, never>>('/auth/password', { current_password, new_password }),
}

export const devicesApi = {
  list: () => http.get<{ items: Device[] }>('/devices'),
  createPairing: (server_url?: string) =>
    http.post<PairingInfo>('/devices/pairing', server_url ? { server_url } : {}),
  rename: (id: number, device_name: string) => http.patch<{ device: Device }>(`/devices/${id}`, { device_name }),
  /** 保存手动填写的 SIM 号码；值为空字符串表示清除该卡槽的手动值（回退到手机上报的号码） */
  update: (id: number, patch: { device_name?: string; sim_numbers?: Record<number, string> }) =>
    http.patch<{ device: Device }>(`/devices/${id}`, patch),
  remove: (id: number, purge: boolean) => http.delete<Record<string, never>>(`/devices/${id}`, purge ? { purge: 1 } : undefined),
}

export interface ConversationQuery {
  q?: string
  device_id?: number | null
  page?: number
  page_size?: number
}

export const conversationsApi = {
  list: (q: ConversationQuery) =>
    http.get<Paged<Conversation>>('/conversations', {
      q: q.q,
      device_id: q.device_id ?? undefined,
      page: q.page,
      page_size: q.page_size,
    }),
  messages: (id: number, params: { before_id?: number; limit?: number } = {}) =>
    http.get<MessagesPage>(`/conversations/${id}/messages`, params),
  markRead: (id: number) => http.post<Record<string, never>>(`/conversations/${id}/read`),
  pin: (id: number, pinned: boolean) => http.post<Record<string, never>>(`/conversations/${id}/pin`, { pinned }),
  remove: (id: number) => http.delete<Record<string, never>>(`/conversations/${id}`),
}

export const messagesApi = {
  search: (q: string, params: { device_id?: number | null; page?: number; page_size?: number } = {}) =>
    http.get<Paged<SearchMessage>>('/messages/search', {
      q,
      device_id: params.device_id ?? undefined,
      page: params.page,
      page_size: params.page_size,
    }),
  send: (p: SendPayload) => http.post<SendResult>('/messages/send', p),
  list: (params: { device_id?: number; direction?: string; status?: string; page?: number; page_size?: number }) =>
    http.get<Paged<Message>>('/messages', params),
}

export const tasksApi = {
  resolve: (taskId: string, action: ResolveAction) =>
    http.post<{ task: Task }>(`/tasks/${encodeURIComponent(taskId)}/resolve`, { action }),
}

export const settingsApi = {
  get: () => http.get<Settings>('/settings'),
  update: (patch: Partial<Settings>) => http.put<Settings>('/settings', patch),
  emergencyPause: (paused: boolean) => http.post<Partial<Settings>>('/settings/emergency-pause', { paused }),
}

export const auditApi = {
  list: (params: { page?: number; page_size?: number; action?: string; device_id?: number }) =>
    http.get<Paged<AuditLog>>('/audit-logs', params),
}

export const exportApi = {
  /** 流式导出（CSV / JSON），以 Blob 形式取回后由界面触发保存 */
  download: (format: 'csv' | 'json', device_id?: number | null) =>
    downloadFile('/export/messages', { format, device_id: device_id ?? undefined }, `messages.${format}`),
}

export const codesApi = {
  recent: (minutes = 10, limit = 20) => http.get<{ items: RecentCode[]; minutes: number }>('/codes/recent', { minutes, limit }),
}

export const statsApi = {
  get: (p: { range: StatsRange; from?: string; to?: string; device_id?: number | null }) =>
    http.get<StatsReport>('/stats', { range: p.range, from: p.from, to: p.to, device_id: p.device_id ?? undefined }),
  health: (device_id?: number | null) => http.get<HealthReport>('/stats/health', { device_id: device_id ?? undefined }),
}

export const aiApi = {
  get: () => http.get<AiConfig>('/ai/config'),
  update: (p: AiConfigPatch) => http.put<AiConfig>('/ai/config', p),
  test: () => http.post<AiTestResult>('/ai/test'),
  preview: (date?: string) => http.post<AiPreview>('/ai/preview', date ? { date } : {}),
}

export const reportsApi = {
  list: () => http.get<{ items: Report[] }>('/reports'),
  get: (date: string) => http.get<Report>(`/reports/${date}`),
  generate: (date: string, useAi: boolean) => http.post<Report>('/reports/generate', { date, use_ai: useAi }),
  remove: (date: string) => http.delete<Record<string, never>>(`/reports/${date}`),
}
