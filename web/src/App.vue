<script setup lang="ts">
import { computed } from 'vue'
import { NConfigProvider, NDialogProvider, NMessageProvider, darkTheme, dateZhCN, zhCN } from 'naive-ui'
import type { GlobalThemeOverrides } from 'naive-ui'
import { useThemeStore } from '@/stores/theme'
import ProviderBridge from '@/components/ProviderBridge.vue'

const theme = useThemeStore()

const overrides = computed<GlobalThemeOverrides>(() => ({
  common: {
    primaryColor: '#2f7cf6',
    primaryColorHover: '#4a8ff8',
    primaryColorPressed: '#2468d6',
    primaryColorSuppl: '#4a8ff8',
    borderRadius: '8px',
    fontFamily:
      'system-ui, -apple-system, "Segoe UI", Roboto, "PingFang SC", "Hiragino Sans GB", "Microsoft YaHei", "Noto Sans CJK SC", sans-serif',
  },
}))
</script>

<template>
  <n-config-provider :theme="theme.isDark ? darkTheme : null" :theme-overrides="overrides" :locale="zhCN" :date-locale="dateZhCN" abstract>
    <n-message-provider placement="top" :max="4">
      <n-dialog-provider>
        <provider-bridge />
        <router-view />
      </n-dialog-provider>
    </n-message-provider>
  </n-config-provider>
</template>
