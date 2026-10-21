import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { conversationsApi } from '@/api'
import type { Conversation } from '@/api'

export const PAGE_SIZE = 30

/** 置顶优先，其次最近消息时间倒序（与服务端排序一致） */
function sortConvs(list: Conversation[]): Conversation[] {
  return [...list].sort((a, b) => {
    if (a.pinned !== b.pinned) return a.pinned ? -1 : 1
    if (a.last_message_at !== b.last_message_at) return b.last_message_at - a.last_message_at
    return b.id - a.id
  })
}

function dedupe(list: Conversation[]): Conversation[] {
  const seen = new Set<number>()
  const out: Conversation[] = []
  for (const c of list) {
    if (seen.has(c.id)) continue
    seen.add(c.id)
    out.push(c)
  }
  return out
}

export const useConversationsStore = defineStore('conversations', () => {
  const items = ref<Conversation[]>([])
  const total = ref(0)
  const page = ref(1)
  const q = ref('')
  const deviceId = ref<number | null>(null)
  const loading = ref(false)
  const loadingMore = ref(false)
  const error = ref('')
  let seq = 0

  const hasMore = computed(() => items.value.length < total.value)
  const byId = computed(() => new Map(items.value.map((c) => [c.id, c])))

  async function load(): Promise<void> {
    const my = ++seq
    loading.value = true
    error.value = ''
    try {
      const res = await conversationsApi.list({ q: q.value, device_id: deviceId.value, page: 1, page_size: PAGE_SIZE })
      if (my !== seq) return
      items.value = dedupe(res.items)
      total.value = res.total
      page.value = 1
    } catch (e) {
      if (my !== seq) return
      error.value = e instanceof Error ? e.message : '加载失败'
      throw e
    } finally {
      if (my === seq) loading.value = false
    }
  }

  async function loadMore(): Promise<void> {
    if (loading.value || loadingMore.value || !hasMore.value) return
    const my = seq
    loadingMore.value = true
    try {
      const next = page.value + 1
      const res = await conversationsApi.list({ q: q.value, device_id: deviceId.value, page: next, page_size: PAGE_SIZE })
      if (my !== seq) return
      items.value = dedupe([...items.value, ...res.items])
      total.value = res.total
      page.value = next
    } finally {
      loadingMore.value = false
    }
  }

  /** 轮询 / 重连后的静默刷新：只替换首页，保留已经翻页加载的后续项 */
  async function refreshHead(): Promise<void> {
    if (loading.value) return
    const my = seq
    const res = await conversationsApi.list({ q: q.value, device_id: deviceId.value, page: 1, page_size: PAGE_SIZE })
    if (my !== seq) return
    const tail = items.value.slice(PAGE_SIZE).filter((c) => !res.items.some((f) => f.id === c.id))
    items.value = dedupe([...res.items, ...tail])
    total.value = res.total
  }

  /**
   * 从搜索结果等入口直接打开会话时，该会话可能不在已加载的列表里：
   * 按号码向服务端查一次并插入（不影响当前列表筛选条件）。
   */
  async function ensureLoaded(id: number, phone: string): Promise<void> {
    if (byId.value.has(id)) return
    const res = await conversationsApi.list({ q: phone, page: 1, page_size: 50 })
    const hit = res.items.find((c) => c.id === id)
    if (hit && !byId.value.has(id)) items.value = sortConvs([...items.value, hit])
  }

  function setFilter(next: { q?: string; deviceId?: number | null }): void {
    if (next.q !== undefined) q.value = next.q
    if (next.deviceId !== undefined) deviceId.value = next.deviceId
  }

  /** 实时事件：更新已有会话或插入新会话（有搜索词时不主动插入，避免与结果不符） */
  function upsert(c: Conversation): void {
    const i = items.value.findIndex((x) => x.id === c.id)
    if (i >= 0) {
      const next = [...items.value]
      next.splice(i, 1, c)
      items.value = sortConvs(next)
      return
    }
    if (q.value.trim()) return
    if (deviceId.value != null && c.device_id !== deviceId.value) return
    items.value = sortConvs([c, ...items.value])
    total.value += 1
  }

  function removeLocal(id: number): void {
    const before = items.value.length
    items.value = items.value.filter((c) => c.id !== id)
    if (items.value.length < before) total.value = Math.max(0, total.value - 1)
  }

  function patchLocal(id: number, p: Partial<Conversation>): void {
    const c = items.value.find((x) => x.id === id)
    if (c) Object.assign(c, p)
  }

  async function markRead(id: number): Promise<void> {
    await conversationsApi.markRead(id)
    patchLocal(id, { unread_count: 0 })
  }

  async function setPinned(id: number, pinned: boolean): Promise<void> {
    await conversationsApi.pin(id, pinned)
    patchLocal(id, { pinned })
    items.value = sortConvs(items.value)
  }

  async function remove(id: number): Promise<void> {
    await conversationsApi.remove(id)
    removeLocal(id)
  }

  return {
    items,
    total,
    q,
    deviceId,
    loading,
    loadingMore,
    error,
    hasMore,
    byId,
    load,
    loadMore,
    ensureLoaded,
    refreshHead,
    setFilter,
    upsert,
    removeLocal,
    patchLocal,
    markRead,
    setPinned,
    remove,
  }
})
