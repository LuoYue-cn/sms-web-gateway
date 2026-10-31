import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { codesApi } from '@/api'
import type { Message, RecentCode } from '@/api'
import { senderTag } from '@/utils/sms'

/** 「最近验证码」的有效窗口：超过这个时间的验证码基本已经失效，不再放进快捷面板 */
export const RECENT_MINUTES = 10
const MAX_ITEMS = 20

export const useCodesStore = defineStore('codes', () => {
  const items = ref<RecentCode[]>([])
  const loading = ref(false)
  /** 本次登录期间用户已经复制过的验证码（按消息 id），面板里淡化显示 */
  const copied = ref<Set<number>>(new Set())

  const count = computed(() => items.value.length)

  async function load(): Promise<void> {
    loading.value = true
    try {
      items.value = (await codesApi.recent(RECENT_MINUTES, MAX_ITEMS)).items
    } finally {
      loading.value = false
    }
  }

  /** 实时收到带验证码的新短信：直接插到最前面，不必再请求一次 */
  function addFromMessage(m: Message, deviceName: string): RecentCode | null {
    if (!m.code || m.direction !== 'incoming') return null
    if (items.value.some((x) => x.message_id === m.id)) return null
    const name = (m.contact_name ?? '').trim()
    const entry: RecentCode = {
      message_id: m.id,
      conversation_id: m.conversation_id,
      code: m.code,
      timestamp: m.timestamp,
      source: senderTag(m.content) || name || m.phone_number,
      device_name: deviceName,
    }
    items.value = [entry, ...items.value].slice(0, MAX_ITEMS)
    return entry
  }

  function markCopied(id: number): void {
    copied.value = new Set(copied.value).add(id)
  }

  /** 去掉已过期的条目（由面板的计时器调用） */
  function prune(now: number): void {
    const cutoff = now - RECENT_MINUTES * 60_000
    if (items.value.some((x) => x.timestamp < cutoff)) items.value = items.value.filter((x) => x.timestamp >= cutoff)
  }

  return { items, loading, copied, count, load, addFromMessage, markCopied, prune }
})
