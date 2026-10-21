<script setup lang="ts">
import { computed } from 'vue'
import { NButton, NDropdown, NIcon } from 'naive-ui'
import { ContrastOutline, MoonOutline, SunnyOutline } from '@vicons/ionicons5'
import { useThemeStore } from '@/stores/theme'
import type { ThemeMode } from '@/stores/theme'

const theme = useThemeStore()
const icon = computed(() => (theme.mode === 'system' ? ContrastOutline : theme.isDark ? MoonOutline : SunnyOutline))
const options = [
  { label: '跟随系统', key: 'system' },
  { label: '浅色', key: 'light' },
  { label: '深色', key: 'dark' },
]
</script>

<template>
  <!-- 外层 span 承接父组件传入的 class/style（n-dropdown 不会把属性透传给触发按钮） -->
  <span class="theme-toggle">
  <n-dropdown trigger="click" :options="options" @select="(k: ThemeMode) => theme.setMode(k)">
    <n-button quaternary circle aria-label="切换主题">
      <template #icon><n-icon :component="icon" /></template>
    </n-button>
  </n-dropdown>
  </span>
</template>

<style scoped>
.theme-toggle {
  display: inline-flex;
}
</style>
