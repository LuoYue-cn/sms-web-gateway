import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { ApiError, authApi, triggerUnauthorized } from '@/api'
import type {
  EvConversationUpdate,
  EvDeviceUpdate,
  EvMessageNew,
  EvMessageStatus,
  EvSettingsUpdate,
  EvSyncProgress,
} from '@/api'
import { API_BASE } from '@/api/client'
import { useConversationsStore } from './conversations'
import { useChatStore } from './chat'
import { useDevicesStore } from './devices'
import { useSettingsStore } from './settings'
import { useAuthStore } from './auth'
import { useCodesStore } from './codes'
import { notify } from '@/utils/notify'
import { displayName } from '@/utils/sms'

export type RealtimeState = 'idle' | 'connecting' | 'live' | 'reconnecting' | 'polling'

/** SSE 连续失败多少次（未能成功 open）后退化为轮询 */
const SSE_FAIL_LIMIT = 3
const POLL_INTERVAL_MS = 5000
/** 轮询模式下多久尝试恢复一次 SSE */
const SSE_RETRY_MS = 30_000

export const useRealtimeStore = defineStore('realtime', () => {
  const state = ref<RealtimeState>('idle')
  const lastEventAt = ref<number | null>(null)

  const label = computed(() => {
    switch (state.value) {
      case 'live':
        return '实时连接'
      case 'connecting':
        return '连接中…'
      case 'reconnecting':
        return '重连中…'
      case 'polling':
        return '轮询模式（5 秒）'
      default:
        return '未连接'
    }
  })

  let es: EventSource | null = null
  let started = false
  let failCount = 0
  let reconnectTimer: ReturnType<typeof setTimeout> | null = null
  let pollTimer: ReturnType<typeof setInterval> | null = null
  let sseRetryTimer: ReturnType<typeof setTimeout> | null = null
  let polling = false
  let probing = false

  // ---------- 事件处理 ----------
  function parse<T>(ev: MessageEvent): T | null {
    lastEventAt.value = Date.now()
    try {
      return JSON.parse(ev.data as string) as T
    } catch {
      return null
    }
  }

  function onMessageNew(ev: MessageEvent): void {
    const d = parse<EvMessageNew>(ev)
    if (!d) return
    const convs = useConversationsStore()
    const chat = useChatStore()
    convs.upsert(d.conversation)
    // 刚到的验证码（5 分钟内）：弹出带复制按钮的提示；历史同步补进来的旧短信只更新数据，不打扰
    const fresh = d.message.code && Date.now() - d.message.timestamp < 5 * 60_000
    const entry = useCodesStore().addFromMessage(d.message, d.conversation.device_name)
    if (entry && fresh) {
      notify.code(entry.source, entry.code)
    } else if (d.message.direction === 'incoming' && chat.activeId !== d.conversation.id) {
      notify.info(`新短信：${displayName(d.conversation)}`)
    }
    chat.upsert(d.message)
  }

  function onMessageStatus(ev: MessageEvent): void {
    const d = parse<EvMessageStatus>(ev)
    if (!d) return
    useChatStore().applyStatus(d)
    if (d.status === 'failed') useConversationsStore().patchLocal(d.conversation_id, { has_failed: true })
  }

  function onConversationUpdate(ev: MessageEvent): void {
    const d = parse<EvConversationUpdate>(ev)
    if (!d) return
    const convs = useConversationsStore()
    if ('deleted' in d && d.deleted) {
      convs.removeLocal(d.id)
      useChatStore().onConversationDeleted(d.id)
    } else if ('conversation' in d) {
      convs.upsert(d.conversation)
    }
  }

  function onDeviceUpdate(ev: MessageEvent): void {
    const d = parse<EvDeviceUpdate>(ev)
    if (d?.device) useDevicesStore().upsert(d.device)
  }

  function onSyncProgress(ev: MessageEvent): void {
    const d = parse<EvSyncProgress>(ev)
    if (d) useDevicesStore().applySync(d)
  }

  function onSettingsUpdate(ev: MessageEvent): void {
    const d = parse<EvSettingsUpdate>(ev)
    if (d) useSettingsStore().patch(d)
  }

  // ---------- 数据补偿（轮询 / 重连后） ----------
  async function refreshAll(): Promise<void> {
    const chat = useChatStore()
    const tasks: Promise<unknown>[] = [
      useConversationsStore().refreshHead(),
      useDevicesStore().load(),
      useSettingsStore().load(),
      useCodesStore().load(),
    ]
    if (chat.activeId != null) tasks.push(chat.refresh())
    const results = await Promise.allSettled(tasks)
    for (const r of results) {
      if (r.status === 'rejected' && r.reason instanceof ApiError && r.reason.status === 401) {
        triggerUnauthorized()
        return
      }
    }
  }

  // ---------- SSE ----------
  function clearReconnect(): void {
    if (reconnectTimer) clearTimeout(reconnectTimer)
    reconnectTimer = null
  }

  function closeEs(): void {
    if (es) {
      es.onopen = null
      es.onerror = null
      es.close()
      es = null
    }
  }

  function connect(): void {
    if (!started) return
    clearReconnect()
    closeEs()
    if (typeof EventSource === 'undefined') {
      startPolling()
      return
    }
    if (state.value !== 'polling') state.value = failCount > 0 ? 'reconnecting' : 'connecting'
    const source = new EventSource(`${API_BASE}/events`, { withCredentials: true })
    es = source
    let opened = false

    source.onopen = () => {
      const wasDown = failCount > 0 || state.value === 'polling'
      opened = true
      failCount = 0
      stopPolling()
      state.value = 'live'
      // 断线期间可能错过事件：Last-Event-ID 只能补回服务端保留的部分，这里再静默刷新一次兜底
      if (wasDown) void refreshAll()
    }
    source.addEventListener('message.new', onMessageNew as EventListener)
    source.addEventListener('message.status', onMessageStatus as EventListener)
    source.addEventListener('conversation.update', onConversationUpdate as EventListener)
    source.addEventListener('device.update', onDeviceUpdate as EventListener)
    source.addEventListener('sync.progress', onSyncProgress as EventListener)
    source.addEventListener('settings.update', onSettingsUpdate as EventListener)

    source.onerror = () => {
      // 服务端每 ~55 秒正常结束连接，浏览器会自动重连（readyState === CONNECTING），属于正常情况。
      if (source.readyState === EventSource.CONNECTING) {
        if (opened) {
          // 曾经成功过的连接被正常结束：不计失败，浏览器自带重连
          opened = false
          return
        }
        failCount++
      } else {
        failCount++
      }
      void probeSession()
      if (failCount >= SSE_FAIL_LIMIT) {
        closeEs()
        startPolling()
        return
      }
      if (source.readyState === EventSource.CLOSED) {
        // 浏览器不再自动重连（例如服务端返回非 200 / 错误的 Content-Type），手动退避重连
        closeEs()
        reconnectTimer = setTimeout(connect, Math.min(1000 * 2 ** failCount, 10_000))
      } else if (state.value !== 'polling') {
        state.value = 'reconnecting'
      }
    }
  }

  /** SSE 失败时顺带探测会话是否已失效（401 -> 回登录页） */
  async function probeSession(): Promise<void> {
    if (probing) return
    probing = true
    try {
      await authApi.me()
    } catch (e) {
      if (e instanceof ApiError && e.status === 401) {
        useAuthStore().clear()
        triggerUnauthorized()
      }
    } finally {
      probing = false
    }
  }

  // ---------- 轮询兜底 ----------
  function startPolling(): void {
    if (!started || polling) return
    polling = true
    state.value = 'polling'
    void refreshAll()
    pollTimer = setInterval(() => {
      if (document.visibilityState === 'hidden') return
      void refreshAll()
    }, POLL_INTERVAL_MS)
    // 周期性尝试恢复 SSE
    if (typeof EventSource !== 'undefined') {
      sseRetryTimer = setTimeout(() => {
        sseRetryTimer = null
        failCount = SSE_FAIL_LIMIT - 1
        connect()
      }, SSE_RETRY_MS)
    }
  }

  function stopPolling(): void {
    polling = false
    if (pollTimer) clearInterval(pollTimer)
    pollTimer = null
    if (sseRetryTimer) clearTimeout(sseRetryTimer)
    sseRetryTimer = null
  }

  function onVisibility(): void {
    if (document.visibilityState === 'visible' && started) void refreshAll()
  }

  function start(): void {
    if (started) return
    started = true
    failCount = 0
    document.addEventListener('visibilitychange', onVisibility)
    connect()
  }

  function stop(): void {
    started = false
    document.removeEventListener('visibilitychange', onVisibility)
    clearReconnect()
    stopPolling()
    closeEs()
    state.value = 'idle'
  }

  return { state, label, lastEventAt, start, stop, refreshAll }
})
