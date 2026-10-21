<script setup lang="ts">
import { computed } from 'vue'
import { useRealtimeStore } from '@/stores/realtime'

const realtime = useRealtimeStore()
const tone = computed(() => {
  switch (realtime.state) {
    case 'live':
      return 'ok'
    case 'polling':
      return 'warn'
    case 'idle':
      return 'off'
    default:
      return 'wait'
  }
})
</script>

<template>
  <div class="conn" :class="tone" :title="realtime.state === 'polling' ? '实时通道不可用，已退化为每 5 秒轮询' : '实时连接状态'">
    <span class="dot" />
    <span>{{ realtime.label }}</span>
  </div>
</template>

<style scoped>
.conn {
  position: fixed;
  right: 12px;
  bottom: 10px;
  z-index: 50;
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 3px 10px;
  font-size: 12px;
  color: var(--text-2);
  background: var(--panel);
  border: 1px solid var(--border);
  border-radius: 999px;
  box-shadow: var(--shadow);
  pointer-events: none;
  opacity: 0.85;
}
.dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--text-3);
}
.ok .dot {
  background: var(--ok);
}
.warn .dot {
  background: var(--warn);
}
.wait .dot {
  background: var(--warn);
  animation: blink 1s ease-in-out infinite;
}
@keyframes blink {
  50% {
    opacity: 0.25;
  }
}
@media (max-width: 820px) {
  /* 手机端：只显示一个小圆点，不再遮挡标题栏下方的内容；文字放进 title 提示 */
  .conn {
    bottom: auto;
    top: 40px;
    right: 6px;
    padding: 3px;
    opacity: 0.9;
  }
  .conn span:not(.dot) {
    display: none;
  }
}
</style>
