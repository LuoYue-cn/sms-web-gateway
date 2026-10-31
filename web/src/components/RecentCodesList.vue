<script setup lang="ts">
import { watch } from 'vue'
import { NButton } from 'naive-ui'
import CopyCodeButton from './CopyCodeButton.vue'
import { RECENT_MINUTES, useCodesStore } from '@/stores/codes'
import { useNow } from '@/composables/useNow'
import { formatCountdown } from '@/utils/time'

const emit = defineEmits<{ (e: 'open', conversationId: number): void }>()
const codes = useCodesStore()
const now = useNow(1000)
// 每秒清理过期条目，角标数字和列表保持一致
watch(now, (n) => codes.prune(n))

function left(ts: number): string {
  return formatCountdown(ts + RECENT_MINUTES * 60_000 - now.value)
}
</script>

<template>
  <div class="head">
    <strong>最近验证码</strong>
    <span class="muted small">最近 {{ RECENT_MINUTES }} 分钟</span>
  </div>
  <div v-if="codes.items.length" class="list">
    <div v-for="c in codes.items" :key="c.message_id" class="row" :class="{ used: codes.copied.has(c.message_id) }">
      <div class="info" role="button" tabindex="0" @click="emit('open', c.conversation_id)" @keydown.enter="emit('open', c.conversation_id)">
        <span class="src">{{ c.source }}</span>
        <span class="muted small">{{ c.device_name }} · 剩余 {{ left(c.timestamp) }}</span>
      </div>
      <copy-code-button :code="c.code" @copied="codes.markCopied(c.message_id)" />
    </div>
  </div>
  <div v-else class="empty muted">
    最近 {{ RECENT_MINUTES }} 分钟没有收到验证码。
    <n-button text type="primary" size="small" @click="codes.load()">刷新</n-button>
  </div>
</template>

<style scoped>
.head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-bottom: 8px;
}
.list {
  display: flex;
  flex-direction: column;
  gap: 4px;
  max-height: min(360px, 55vh);
  overflow-y: auto;
}
.row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 4px;
  border-radius: 8px;
}
.row:hover {
  background: var(--panel-2);
}
.row.used .src {
  color: var(--text-3);
}
.info {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  cursor: pointer;
}
.src {
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.small {
  font-size: 12px;
}
.empty {
  padding: 10px 0 4px;
}
</style>
