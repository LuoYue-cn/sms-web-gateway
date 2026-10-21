import { computed, ref, watch } from 'vue'
import { defineStore } from 'pinia'

export type ThemeMode = 'system' | 'light' | 'dark'
const KEY = 'smsgate.theme'

function readStored(): ThemeMode {
  try {
    const v = localStorage.getItem(KEY)
    if (v === 'light' || v === 'dark' || v === 'system') return v
  } catch {
    /* localStorage 不可用时忽略 */
  }
  return 'system'
}

export const useThemeStore = defineStore('theme', () => {
  const mode = ref<ThemeMode>(readStored())
  const mq = typeof window !== 'undefined' && window.matchMedia ? window.matchMedia('(prefers-color-scheme: dark)') : null
  const osDark = ref(mq?.matches ?? false)
  mq?.addEventListener?.('change', (e) => {
    osDark.value = e.matches
  })

  const isDark = computed(() => (mode.value === 'system' ? osDark.value : mode.value === 'dark'))

  function apply(): void {
    document.documentElement.dataset.theme = isDark.value ? 'dark' : 'light'
  }
  watch(isDark, apply, { immediate: true })

  function setMode(m: ThemeMode): void {
    mode.value = m
    try {
      localStorage.setItem(KEY, m)
    } catch {
      /* 忽略 */
    }
  }

  return { mode, isDark, setMode }
})
