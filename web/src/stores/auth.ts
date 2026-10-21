import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { ApiError, authApi, setCsrfToken } from '@/api'
import type { AuthInfo, AuthUser } from '@/api'

export const useAuthStore = defineStore('auth', () => {
  const user = ref<AuthUser | null>(null)
  const siteName = ref('SMS Web Gateway')
  const timezone = ref('')
  /** 已向服务端确认过登录态（避免路由守卫重复请求） */
  const checked = ref(false)

  const isAuthed = computed(() => user.value != null)

  function apply(info: AuthInfo): void {
    user.value = info.user
    setCsrfToken(info.csrf_token)
    if (info.site_name) siteName.value = info.site_name
    timezone.value = info.timezone || ''
    checked.value = true
    document.title = siteName.value
  }

  function clear(): void {
    user.value = null
    setCsrfToken('')
    checked.value = true
  }

  /** 检查登录态。401 -> false；其它错误（网络/500）向上抛出 */
  async function fetchMe(): Promise<boolean> {
    try {
      apply(await authApi.me())
      return true
    } catch (e) {
      if (e instanceof ApiError && e.status === 401) {
        clear()
        return false
      }
      throw e
    }
  }

  async function login(username: string, password: string): Promise<void> {
    apply(await authApi.login(username, password))
  }

  async function logout(): Promise<void> {
    try {
      await authApi.logout()
    } finally {
      clear()
    }
  }

  function setSite(name: string, tz: string): void {
    if (name) {
      siteName.value = name
      document.title = name
    }
    timezone.value = tz
  }

  return { user, siteName, timezone, checked, isAuthed, fetchMe, login, logout, clear, setSite }
})
