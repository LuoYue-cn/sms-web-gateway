<script setup lang="ts">
// 把 Naive UI 的 message API 注册给 store / 工具函数使用（它们不在组件上下文里）
import { useMessage } from 'naive-ui'
import { setMessageApi } from '@/utils/notify'

setMessageApi(useMessage())
</script>

<template>
  <span style="display: none" />
</template>
