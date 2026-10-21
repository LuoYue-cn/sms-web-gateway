import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import { router } from './router'
import { onCsrfRefresh, onUnauthorized } from './api'
import { useAuthStore } from './stores/auth'
import { useRealtimeStore } from './stores/realtime'
import './styles/global.css'

const app = createApp(App)
const pinia = createPinia()
app.use(pinia)
app.use(router)

// 401：清除登录态、停止实时连接并回到登录页（保留当前位置以便登录后返回）
onUnauthorized(() => {
  useAuthStore().clear()
  useRealtimeStore().stop()
  const cur = router.currentRoute.value
  if (cur.name === 'login' || cur.name === 'install') return
  void router.replace({ name: 'login', query: cur.fullPath !== '/' ? { redirect: cur.fullPath } : {} })
})

// 403 csrf_failed：重新读取 auth/me 取得新令牌，客户端会自动重试一次
onCsrfRefresh(async () => {
  await useAuthStore().fetchMe()
})

app.mount('#app')
