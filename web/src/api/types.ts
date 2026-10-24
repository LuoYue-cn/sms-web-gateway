/**
 * 与 docs/API.md 一一对应的类型定义。所有时间戳均为 Unix 毫秒（UTC）。
 */

export interface ApiErrorBody {
  code: string
  message: string
  details?: Record<string, unknown>
}

export interface ApiEnvelope<T> {
  ok: boolean
  data?: T
  error?: ApiErrorBody
}

// ---------- 安装 / 认证 ----------
export interface InstallCheck {
  key: string
  label: string
  ok: boolean
  detail?: string
}
export interface InstallStatus {
  installed: boolean
  checks?: InstallCheck[]
}
export interface InstallPayload {
  username: string
  password: string
  site_name: string
  timezone: string
}

export interface AuthUser {
  id: number
  username: string
}
export interface AuthInfo {
  user: AuthUser
  csrf_token: string
  site_name: string
  timezone: string
}

// ---------- 设备 ----------
export type SyncStatus = 'idle' | 'running' | 'done' | 'error' | 'never'
export type KeepaliveMode = 'normal' | 'root_enhanced'
export type WatchdogStatus = 'running' | 'stopped' | 'not_installed'
export type ProtectionState = 'none' | 'cooldown' | 'protected'
export type SimState = 'ready' | 'absent' | 'locked' | 'unknown'

export interface SimInfo {
  slot: number
  operator: string
  state: SimState | string
  /** SIM 号码：手动填写优先，其次是手机上报；两者都没有则为 null（很多运营商不往 SIM 里写号码） */
  number?: string | null
  number_source?: 'manual' | 'device' | null
}

export interface Device {
  id: number
  device_name: string
  android_version: string
  app_version: string
  battery_level: number | null
  charging: boolean
  network_type: string
  sim_count: number
  sim_operator: string
  sims: SimInfo[]
  last_seen: number | null
  online: boolean
  sync_status: SyncStatus
  sync_total: number
  sync_synced: number
  pending_tasks: number
  keepalive_mode: KeepaliveMode
  watchdog_status: WatchdogStatus
  root_granted: boolean
  battery_whitelisted: boolean
  last_sync_at: number | null
  last_recovery_at: number | null
  recovery_count: number
  protection_state: ProtectionState
  created_at: number
}

export interface PairingInfo {
  pairing_token: string
  expires_at: number
  server_url: string
  qr_payload: string
}

// ---------- 会话 / 消息 ----------
export type MessageDirection = 'incoming' | 'outgoing'
export type MessageStatus =
  | 'received'
  | 'queued'
  | 'claimed'
  | 'sending'
  | 'sent'
  | 'delivered'
  | 'failed'
  | 'unknown'

export interface Conversation {
  id: number
  device_id: number
  device_name: string
  phone_number: string
  contact_name: string | null
  last_message: string
  last_message_at: number
  last_direction: MessageDirection
  unread_count: number
  has_failed: boolean
  pinned: boolean
}

export interface Message {
  id: number
  device_id: number
  conversation_id: number
  phone_number: string
  contact_name: string | null
  content: string
  direction: MessageDirection
  timestamp: number
  status: MessageStatus
  sim_slot: number | null
  task_id: string | null
  error_message: string | null
  read: boolean
}

/** /messages/search 的结果：Message + conversation 信息（具体字段 API.md 未列出，全部可选） */
export interface SearchMessage extends Message {
  conversation?: Partial<Conversation> | null
  device_name?: string
}

export interface Paged<T> {
  items: T[]
  total: number
  page?: number
  page_size?: number
}

export interface MessagesPage {
  items: Message[]
  has_more: boolean
}

export interface SendPayload {
  device_id: number
  recipient: string
  content: string
  sim_slot?: number
  confirm_warnings?: boolean
}

export type TaskStatus = 'queued' | 'claimed' | 'sending' | 'sent' | 'delivered' | 'failed' | 'unknown'
export type ResolveAction = 'resend' | 'mark_sent' | 'mark_failed'

export interface Task {
  task_id: string
  device_id: number
  recipient: string
  content: string
  sim_slot: number | null
  status: TaskStatus
  error_code: string | null
  error_message: string | null
  created_at: number
  claimed_at: number | null
  completed_at: number | null
  resolved_action: string | null
}

export interface SendResult {
  task: Task
  message: Message
  conversation: Conversation
}

// ---------- 设置 / 审计 ----------
export interface Settings {
  site_name: string
  timezone: string
  online_threshold_seconds: number
  send_paused: boolean
  send_rate_per_minute: number
  max_content_length: number
  home_country_code: string
  heartbeat_interval_s: number
  poll_wait_s: number
}

export interface AuditLog {
  id: number
  ts: number
  actor_type: 'admin' | 'device' | 'system'
  actor: string
  action: string
  target: string | null
  detail: string | null
  ip: string | null
}

// ---------- SSE 事件 ----------
export interface EvMessageNew {
  message: Message
  conversation: Conversation
}
export interface EvMessageStatus {
  message_id: number
  task_id: string | null
  conversation_id: number
  status: MessageStatus
  error_message: string | null
}
export type EvConversationUpdate = { conversation: Conversation } | { deleted: true; id: number }
export interface EvDeviceUpdate {
  device: Device
}
export interface EvSyncProgress {
  device_id: number
  sync_status: SyncStatus
  sync_total: number
  sync_synced: number
}
export type EvSettingsUpdate = Partial<Settings>
