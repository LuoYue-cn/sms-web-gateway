<script setup lang="ts">
import { onMounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import { NBadge, NButton, NIcon, NPopover } from 'naive-ui'
import { KeyOutline } from '@vicons/ionicons5'
import CopyCodeButton from './CopyCodeButton.vue'
import { RECENT_MINUTES, useCodesStore } from '@/stores/codes'
import { useNow } from '@/composables/useNow'
import { formatCountdown } from '@/utils/time'

const codes = useCodesStore()
const router = useRouter()
const now = useNow(1000)

onMounted(() => {
  void codes.load().catch(() => undefined)
})
// 每秒清理过期条目，角标数字和列表保持一致
watch(now, (n) => codes.prune(n))

function left(ts: number): string {
  return formatCountdown(ts + RECENT_MINUTES * 60_000 - now.value)
}
function open(conversationId: number): void {
  void router.push({ name: 'chat', params: { id: conversationId } })
}
</script>

<template>
  <n-popover trigger="click" placement="bottom-end" :show-arrow="false" style="width: 320px; max-width: calc(100vw - 20px)">
    <template #trigger>
      <button class="chip" type="button" aria-label="最近验证码">
        <n-badge :value="codes.count" :max="9" :show="codes.count > 0" :offset="[4, -2]" type="warning">
          <n-icon :component="KeyOutline" :size="18" />
        </n-badge>
      </button>
    </template>
    <div class="head">
      <strong>最近验证码</strong>
      <span class="muted small">最近 {{ RECENT_MINUTES }} 分钟</span>
    </div>
    <div v-if="codes.items.length" class="list">
      <div v-for="c in codes.items" :key="c.message_id" class="row" :class="{ used: codes.copied.has(c.message_id) }">
        <div class="info" role="button" tabindex="0" @click="open(c.conversation_id)" @keydown.enter="open(c.conversation_id)">
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
  </n-popover>
</template>

<style scoped>
.chip {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 30px;
  border: none;
  border-radius: 999px;
  background: transparent;
  color: var(--text-2);
  cursor: pointer;
}
.chip:hover {
  background: var(--panel-2);
  color: var(--text);
}
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
  max-height: 360px;
  overflow-y: auto;
}
.row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 6px 4px;
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
