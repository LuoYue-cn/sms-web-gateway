<script setup lang="ts">
import { onBeforeUnmount, onMounted } from 'vue'
import { NAlert } from 'naive-ui'
import AppHeader from './AppHeader.vue'
import ConnectionBadge from './ConnectionBadge.vue'
import { useDevicesStore } from '@/stores/devices'
import { useSettingsStore } from '@/stores/settings'
import { useRealtimeStore } from '@/stores/realtime'
import { notify } from '@/utils/notify'

const devices = useDevicesStore()
const settings = useSettingsStore()
const realtime = useRealtimeStore()
let tick: ReturnType<typeof setInterval> | null = null

onMounted(async () => {
  // 先拉取基础数据，再建立实时连接；基础数据失败只提示，不阻断使用
  const results = await Promise.allSettled([devices.load(), settings.load()])
  for (const r of results) if (r.status === 'rejected') notify.fromError(r.reason, '加载基础数据失败')
  realtime.start()
  // 在线状态按阈值本地推算，避免设备掉线后界面一直显示在线
  tick = setInterval(() => devices.recomputeOnline(settings.onlineThreshold), 10_000)
})

onBeforeUnmount(() => {
  realtime.stop()
  if (tick) clearInterval(tick)
})
</script>

<template>
  <div class="shell">
    <app-header />
    <transition name="fade">
      <n-alert v-if="settings.sendPaused" type="error" :bordered="false" class="pause-banner" :show-icon="true">
        已紧急暂停发送：所有设备都不会再收到发送任务。
        <router-link to="/settings">前往设置恢复</router-link>
      </n-alert>
    </transition>
    <main class="shell-main">
      <!-- 路由切换刻意不用 out-in 过渡：离场过渡依赖 requestAnimationFrame，
           页面在后台或渲染被暂停时会卡在中间状态，导致新页面迟迟不挂载 -->
      <router-view />
    </main>
    <connection-badge />
  </div>
</template>

<style scoped>
.shell {
  display: flex;
  flex-direction: column;
  height: 100%;
  height: 100dvh;
  background: var(--bg);
}
.shell-main {
  flex: 1;
  min-height: 0;
  position: relative;
}
.pause-banner {
  border-radius: 0;
  font-weight: 500;
}
</style>
