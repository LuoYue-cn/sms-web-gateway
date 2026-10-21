import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { settingsApi } from '@/api'
import type { Settings } from '@/api'
import { useAuthStore } from './auth'
import { browserTimeZone, resolveTimeZone } from '@/utils/time'

export const useSettingsStore = defineStore('settings', () => {
  const data = ref<Settings | null>(null)
  const loading = ref(false)

  const sendPaused = computed(() => data.value?.send_paused ?? false)
  const onlineThreshold = computed(() => data.value?.online_threshold_seconds ?? 60)
  const maxContentLength = computed(() => data.value?.max_content_length ?? 1000)
  /** 生效时区：设置里的 IANA 时区；无效时用浏览器本地时区 */
  const timezone = computed(() => resolveTimeZone(data.value?.timezone || useAuthStore().timezone) ?? browserTimeZone())

  function sync(): void {
    const s = data.value
    if (s) useAuthStore().setSite(s.site_name, s.timezone)
  }

  async function load(): Promise<void> {
    loading.value = true
    try {
      data.value = await settingsApi.get()
      sync()
    } finally {
      loading.value = false
    }
  }

  /** 保存设置（send_paused 只能走 emergency-pause 接口，这里忽略） */
  async function save(patch: Partial<Settings>): Promise<void> {
    const { send_paused: _ignored, ...rest } = patch
    void _ignored
    const res = await settingsApi.update(rest)
    if (res && typeof res === 'object' && 'site_name' in res) {
      data.value = res
      sync()
    } else {
      await load()
    }
  }

  async function setPaused(paused: boolean): Promise<void> {
    const res = await settingsApi.emergencyPause(paused)
    patch({ ...res, send_paused: paused })
  }

  /** 来自 settings.update 事件或其它接口的局部更新 */
  function patch(p: Partial<Settings>): void {
    if (data.value) data.value = { ...data.value, ...p }
    sync()
  }

  return { data, loading, sendPaused, onlineThreshold, maxContentLength, timezone, load, save, setPaused, patch }
})
