import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { conversationsApi } from '@/api'
import type { EvMessageStatus, Message } from '@/api'
import { useConversationsStore } from './conversations'

const PAGE = 50

function sortMsgs(list: Message[]): Message[] {
  return [...list].sort((a, b) => (a.timestamp !== b.timestamp ? a.timestamp - b.timestamp : a.id - b.id))
}

export const useChatStore = defineStore('chat', () => {
  const activeId = ref<number | null>(null)
  const messages = ref<Message[]>([])
  const hasMore = ref(false)
  const loading = ref(false)
  const loadingOlder = ref(false)
  const error = ref('')
  let seq = 0

  const conversation = computed(() => (activeId.value == null ? null : useConversationsStore().byId.get(activeId.value) ?? null))

  async function open(id: number): Promise<void> {
    if (activeId.value === id && messages.value.length) return
    const my = ++seq
    activeId.value = id
    messages.value = []
    hasMore.value = false
    error.value = ''
    loading.value = true
    try {
      const res = await conversationsApi.messages(id, { limit: PAGE })
      if (my !== seq) return
      messages.value = sortMsgs(res.items)
      hasMore.value = !!res.has_more
    } catch (e) {
      if (my !== seq) return
      error.value = e instanceof Error ? e.message : '加载失败'
      throw e
    } finally {
      if (my === seq) loading.value = false
    }
  }

  function close(): void {
    seq++
    activeId.value = null
    messages.value = []
    hasMore.value = false
    loading.value = false
    loadingOlder.value = false
    error.value = ''
  }

  /** 向上加载更早的消息（before_id），返回新增条数 */
  async function loadOlder(): Promise<number> {
    const id = activeId.value
    if (id == null || loadingOlder.value || !hasMore.value || !messages.value.length) return 0
    const my = seq
    loadingOlder.value = true
    try {
      // 服务端按 (timestamp, id) 取"比该消息更早"的消息，所以游标必须是时间上最早的那条；
      // 历史同步入库的 id 与时间顺序不一致，不能用最小 id。messages 已按 (timestamp, id) 升序。
      const oldestId = messages.value[0].id
      const res = await conversationsApi.messages(id, { before_id: oldestId, limit: PAGE })
      if (my !== seq) return 0
      const exist = new Set(messages.value.map((m) => m.id))
      const fresh = res.items.filter((m) => !exist.has(m.id))
      messages.value = sortMsgs([...fresh, ...messages.value])
      hasMore.value = !!res.has_more
      return fresh.length
    } finally {
      loadingOlder.value = false
    }
  }

  /** 轮询 / 重连后的静默刷新：拉取最新一页并合并 */
  async function refresh(): Promise<void> {
    const id = activeId.value
    if (id == null || loading.value) return
    const my = seq
    const res = await conversationsApi.messages(id, { limit: PAGE })
    if (my !== seq) return
    mergeMany(res.items)
  }

  function mergeMany(list: Message[]): void {
    const map = new Map(messages.value.map((m) => [m.id, m]))
    for (const m of list) map.set(m.id, m)
    messages.value = sortMsgs([...map.values()])
  }

  /** 新增或覆盖一条消息（仅当属于当前会话） */
  function upsert(m: Message): void {
    if (m.conversation_id !== activeId.value) return
    mergeMany([m])
  }

  function applyStatus(ev: EvMessageStatus): void {
    if (ev.conversation_id !== activeId.value) return
    const m = messages.value.find((x) => x.id === ev.message_id || (ev.task_id && x.task_id === ev.task_id))
    if (!m) return
    m.status = ev.status
    m.error_message = ev.error_message ?? null
  }

  /** 会话被删除 / 切换时清空 */
  function onConversationDeleted(id: number): void {
    if (activeId.value === id) close()
  }

  return { activeId, messages, hasMore, loading, loadingOlder, error, conversation, open, close, loadOlder, refresh, upsert, applyStatus, onConversationDeleted }
})
