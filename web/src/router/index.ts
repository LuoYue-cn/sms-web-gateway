import { createRouter, createWebHistory } from 'vue-router'
import type { RouteRecordRaw } from 'vue-router'
import { installApi } from '@/api'
import { useAuthStore } from '@/stores/auth'

declare module 'vue-router' {
  interface RouteMeta {
    public?: boolean
    title?: string
  }
}

const routes: RouteRecordRaw[] = [
  { path: '/install', name: 'install', component: () => import('@/views/InstallView.vue'), meta: { public: true, title: '安装向导' } },
  { path: '/login', name: 'login', component: () => import('@/views/LoginView.vue'), meta: { public: true, title: '登录' } },
  {
    path: '/',
    component: () => import('@/components/AppShell.vue'),
    children: [
      { path: '', name: 'home', component: () => import('@/views/MessagesView.vue'), meta: { title: '短信' } },
      { path: 'c/:id(\\d+)', name: 'chat', component: () => import('@/views/MessagesView.vue'), meta: { title: '短信' } },
      { path: 'devices', name: 'devices', component: () => import('@/views/DevicesView.vue'), meta: { title: '设备管理' } },
      { path: 'settings', name: 'settings', component: () => import('@/views/SettingsView.vue'), meta: { title: '设置' } },
      { path: 'stats', name: 'stats', component: () => import('@/views/StatsView.vue'), meta: { title: '统计' } },
      { path: 'reports', name: 'reports', component: () => import('@/views/ReportsView.vue'), meta: { title: 'AI 日报' } },
      { path: 'search', name: 'search', component: () => import('@/views/SearchView.vue'), meta: { title: '搜索' } },
    ],
  },
  { path: '/:pathMatch(.*)*', redirect: '/' },
]

export const router = createRouter({
  history: createWebHistory(),
  routes,
})

let cachedInstalled: boolean | null = null

export function setInstalledCached(val: boolean): void {
  cachedInstalled = val
}

async function checkInstalled(): Promise<boolean> {
  if (cachedInstalled === true) return true
  try {
    const s = await installApi.status()
    cachedInstalled = !!s.installed
    return cachedInstalled
  } catch {
    return cachedInstalled ?? false
  }
}

router.beforeEach(async (to) => {
  const auth = useAuthStore()

  // 1. 检查安装状态
  const installed = await checkInstalled()

  // 未安装：全站任何页面一律强制重定向到 /install
  if (!installed) {
    if (to.name === 'install') return true
    return { name: 'install' }
  }

  // 已安装：安装向导页面自动禁用，直接重定向到登录页
  if (to.name === 'install') {
    return { name: 'login' }
  }

  // 2. 检查登录状态
  if (!auth.isAuthed) {
    let authed = false
    try {
      authed = await auth.fetchMe()
    } catch {
      if (to.name === 'login') return true
      return { name: 'login', query: { err: 'network' } }
    }
    if (!authed) {
      if (to.name === 'login') return true
      return { name: 'login', query: to.fullPath !== '/' ? { redirect: to.fullPath } : {} }
    }
  }

  if (to.name === 'login') {
    const r = typeof to.query.redirect === 'string' && to.query.redirect.startsWith('/') ? to.query.redirect : '/'
    return r
  }
  return true
})

router.afterEach((to) => {
  const site = useAuthStore().siteName
  document.title = to.meta.title ? `${to.meta.title} - ${site}` : site
})

/**
 * 发版后旧页面引用的分包文件（带哈希）已不存在，路由懒加载会失败。
 * 此时整页刷新到目标地址以取得新版本；用 sessionStorage 标记避免真正的网络故障导致无限刷新。
 */
router.onError((err, to) => {
  const msg = String((err as Error)?.message ?? err)
  const isChunkError = /Failed to fetch dynamically imported module|Importing a module script failed|error loading dynamically imported module/i.test(msg)
  if (!isChunkError) return
  const key = `smsgate.reload:${to.fullPath}`
  try {
    if (sessionStorage.getItem(key)) {
      sessionStorage.removeItem(key)
      return
    }
    sessionStorage.setItem(key, '1')
  } catch {
    /* sessionStorage 不可用时仍尝试刷新一次 */
  }
  window.location.assign(to.fullPath)
})
