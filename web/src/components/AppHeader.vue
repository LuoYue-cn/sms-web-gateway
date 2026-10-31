<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { NButton, NDropdown, NIcon, NInput, NPopover } from 'naive-ui'
import {
  ChatbubblesOutline,
  ContrastOutline,
  EllipsisHorizontal,
  MoonOutline,
  PersonCircleOutline,
  PhonePortraitOutline,
  BarChartOutline,
  DocumentTextOutline,
  SearchOutline,
  SettingsOutline,
  SunnyOutline,
} from '@vicons/ionicons5'
import RecentCodes from './RecentCodes.vue'
import { useAuthStore } from '@/stores/auth'
import { useDevicesStore } from '@/stores/devices'
import { useThemeStore } from '@/stores/theme'
import type { ThemeMode } from '@/stores/theme'
import { useMedia, MOBILE_QUERY } from '@/composables/useMedia'
import { notify } from '@/utils/notify'
import { formatAgo } from '@/utils/time'
import { useNow } from '@/composables/useNow'

const auth = useAuthStore()
const devices = useDevicesStore()
const theme = useThemeStore()
const route = useRoute()
const router = useRouter()
const isMobile = useMedia(MOBILE_QUERY)
const now = useNow(5000)

const q = ref(typeof route.query.q === 'string' ? route.query.q : '')
const showMobileSearch = ref(false)
watch(
  () => route.query.q,
  (v) => {
    if (route.name === 'search') q.value = typeof v === 'string' ? v : ''
  },
)

function submitSearch(): void {
  const text = q.value.trim()
  if (!text) return
  showMobileSearch.value = false
  void router.push({ name: 'search', query: { q: text } })
}

function closeMobileSearch(): void {
  showMobileSearch.value = false
  q.value = route.name === 'search' && typeof route.query.q === 'string' ? route.query.q : ''
}

const overview = computed(() => {
  const total = devices.items.length
  if (!total) return { text: '无设备', tone: 'off' }
  const on = devices.onlineCount
  return { text: `${on}/${total} 在线`, tone: on === total ? 'ok' : on > 0 ? 'warn' : 'bad' }
})

const themeIcon = computed(() => (theme.mode === 'system' ? ContrastOutline : theme.isDark ? MoonOutline : SunnyOutline))
const themeOptions = [
  { label: '跟随系统', key: 'system' },
  { label: '浅色', key: 'light' },
  { label: '深色', key: 'dark' },
]
const userOptions = [
  { label: '修改密码', key: 'password' },
  { label: '设置', key: 'settings' },
  { type: 'divider', key: 'd' },
  { label: '退出登录', key: 'logout' },
]

/** 手机顶栏只保留最常用的入口，其余收进「更多」菜单 */
const moreOptions = computed(() => [
  { label: '设备管理', key: 'devices' },
  { label: '统计', key: 'stats' },
  { label: 'AI 日报', key: 'reports' },
  { label: '设置', key: 'settings' },
  {
    label: `主题：${theme.mode === 'system' ? '跟随系统' : theme.mode === 'dark' ? '深色' : '浅色'}`,
    key: 'theme',
    children: themeOptions.map((o) => ({ label: o.label, key: `theme:${o.key}` })),
  },
  { label: '修改密码', key: 'password' },
  { type: 'divider', key: 'd' },
  { label: `退出登录（${auth.user?.username ?? ''}）`, key: 'logout' },
])

function onMore(key: string): void {
  if (key.startsWith('theme:')) theme.setMode(key.slice(6) as ThemeMode)
  else if (key === 'devices' || key === 'stats' || key === 'reports') void router.push({ name: key })
  else void onUser(key)
}

async function onUser(key: string): Promise<void> {
  if (key === 'logout') {
    try {
      await auth.logout()
    } catch (e) {
      notify.fromError(e, '退出失败')
    }
    await router.replace({ name: 'login' })
  } else if (key === 'password' || key === 'settings') {
    await router.push({ name: 'settings', hash: key === 'password' ? '#password' : '' })
  }
}
</script>

<template>
  <header class="hdr">
    <router-link to="/" class="brand">
      <span class="logo"><n-icon :component="ChatbubblesOutline" :size="20" /></span>
      <span v-if="!(isMobile && showMobileSearch)" class="name">{{ auth.siteName }}</span>
    </router-link>

    <div v-if="!isMobile" class="search">
      <n-input v-model:value="q" placeholder="搜索全部短信内容 / 号码" clearable round size="small" @keyup.enter="submitSearch">
        <template #prefix><n-icon :component="SearchOutline" /></template>
      </n-input>
      <n-button type="primary" size="small" round :disabled="!q.trim()" class="search-btn" @click="submitSearch">搜索</n-button>
    </div>
    <div v-else-if="showMobileSearch" class="search mobile-search">
      <n-input v-model:value="q" placeholder="搜索短信内容 / 号码" clearable round size="small" autofocus @keyup.enter="submitSearch" />
      <n-button type="primary" size="small" round :disabled="!q.trim()" class="search-btn" @click="submitSearch">搜索</n-button>
      <n-button quaternary size="small" class="search-cancel" @click="closeMobileSearch">取消</n-button>
    </div>

    <div v-if="!(isMobile && showMobileSearch)" class="spacer" />

    <template v-if="!(isMobile && showMobileSearch)">
    <n-button v-if="isMobile" quaternary circle size="small" aria-label="搜索" @click="showMobileSearch = true">
      <template #icon><n-icon :component="SearchOutline" /></template>
    </n-button>

    <recent-codes />

    <n-popover trigger="click" placement="bottom-end" :show-arrow="false" style="min-width: 240px">
      <template #trigger>
        <button class="dev-chip" :class="overview.tone" type="button" aria-label="设备状态概览">
          <n-icon :component="PhonePortraitOutline" :size="16" />
          <span class="dot" />
          <span v-if="!isMobile">{{ overview.text }}</span>
        </button>
      </template>
      <div v-if="devices.items.length" class="dev-list">
        <div v-for="d in devices.items" :key="d.id" class="dev-row">
          <span class="dot" :class="d.online ? 'ok' : 'off'" />
          <span class="dn">{{ d.device_name }}</span>
          <span class="muted small">{{ d.online ? '在线' : formatAgo(d.last_seen, now, '从未上线') }}</span>
        </div>
        <n-button text type="primary" size="small" @click="router.push({ name: 'devices' })">管理设备</n-button>
      </div>
      <div v-else class="dev-list">
        <span class="muted">还没有绑定设备</span>
        <n-button text type="primary" size="small" @click="router.push({ name: 'devices' })">去添加设备</n-button>
      </div>
    </n-popover>

    <template v-if="!isMobile">
      <n-dropdown trigger="click" :options="themeOptions" @select="(k: ThemeMode) => theme.setMode(k)">
        <n-button quaternary circle size="small" aria-label="切换主题">
          <template #icon><n-icon :component="themeIcon" /></template>
        </n-button>
      </n-dropdown>

      <n-button quaternary circle size="small" aria-label="统计" title="统计" @click="router.push({ name: 'stats' })">
        <template #icon><n-icon :component="BarChartOutline" /></template>
      </n-button>
      <n-button quaternary circle size="small" aria-label="AI 日报" title="AI 日报" @click="router.push({ name: 'reports' })">
        <template #icon><n-icon :component="DocumentTextOutline" /></template>
      </n-button>
      <n-button quaternary circle size="small" aria-label="设备管理" @click="router.push({ name: 'devices' })">
        <template #icon><n-icon :component="PhonePortraitOutline" /></template>
      </n-button>
      <n-button quaternary circle size="small" aria-label="设置" @click="router.push({ name: 'settings' })">
        <template #icon><n-icon :component="SettingsOutline" /></template>
      </n-button>

      <n-dropdown trigger="click" :options="userOptions" @select="onUser">
        <n-button quaternary size="small" class="user-btn">
          <template #icon><n-icon :component="PersonCircleOutline" :size="20" /></template>
          <span>{{ auth.user?.username }}</span>
        </n-button>
      </n-dropdown>
    </template>
    <n-dropdown v-else trigger="click" :options="moreOptions" placement="bottom-end" @select="onMore">
      <n-button quaternary circle size="small" aria-label="更多">
        <template #icon><n-icon :component="EllipsisHorizontal" :size="20" /></template>
      </n-button>
    </n-dropdown>
    </template>
  </header>
</template>

<style scoped>
.hdr {
  height: var(--header-h);
  flex: none;
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 0 14px;
  background: var(--panel);
  border-bottom: 1px solid var(--border);
  transition: background-color 0.25s ease, border-color 0.25s ease;
}
.brand {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--text);
  font-weight: 600;
  font-size: 16px;
  min-width: 0;
}
.logo {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border-radius: 9px;
  color: #fff;
  background: linear-gradient(135deg, #2f7cf6, #5b9bff);
  flex: none;
}
.name {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 220px;
}
.search {
  width: 380px;
  margin-left: 20px;
  display: flex;
  align-items: center;
  gap: 8px;
}
.search :deep(.n-input) {
  flex: 1;
  min-width: 0;
}
.search-btn {
  flex: none;
}
.mobile-search {
  width: auto;
  flex: 1;
  min-width: 0;
  margin-left: 4px;
  gap: 6px;
}
.search-cancel {
  flex: none;
  padding: 0 6px;
}
.spacer {
  flex: 1;
}
.dev-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 30px;
  padding: 0 10px;
  border: 1px solid var(--border);
  border-radius: 999px;
  background: var(--panel-2);
  color: var(--text);
  font-size: 13px;
  cursor: pointer;
  transition: border-color 0.2s;
}
.dev-chip:hover {
  border-color: var(--accent);
}
.dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--text-3);
  flex: none;
}
.dev-chip.ok .dot,
.dot.ok {
  background: var(--ok);
}
.dev-chip.warn .dot {
  background: var(--warn);
}
.dev-chip.bad .dot {
  background: var(--danger);
}
.dev-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.dev-row {
  display: flex;
  align-items: center;
  gap: 8px;
}
.dev-row .dn {
  flex: 1;
  font-weight: 500;
}
.small {
  font-size: 12px;
}
@media (max-width: 820px) {
  .hdr {
    height: 52px;
    padding: 0 10px;
    gap: 4px;
  }
  .logo {
    width: 30px;
    height: 30px;
  }
  .name {
    max-width: 38vw;
    font-size: 15px;
  }
}
</style>
