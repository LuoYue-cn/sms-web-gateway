<script setup lang="ts">
import { ref } from 'vue'
import { NIcon } from 'naive-ui'
import { CheckmarkOutline, CopyOutline } from '@vicons/ionicons5'
import { copyCode } from '@/composables/useCopyCode'

const props = defineProps<{ code: string; compact?: boolean }>()
const emit = defineEmits<{ (e: 'copied'): void }>()
const done = ref(false)
let timer: ReturnType<typeof setTimeout> | null = null

async function onClick(ev: Event): Promise<void> {
  ev.stopPropagation()
  const ok = await copyCode(props.code)
  if (!ok) return
  done.value = true
  emit('copied')
  if (timer) clearTimeout(timer)
  timer = setTimeout(() => (done.value = false), 1600)
}
</script>

<template>
  <button
    type="button"
    class="copy"
    :class="{ done, compact }"
    :aria-label="`复制验证码 ${code}`"
    @click="onClick"
    @keydown.enter.stop
  >
    <n-icon :component="done ? CheckmarkOutline : CopyOutline" :size="compact ? 13 : 15" />
    <span>{{ done ? '已复制' : compact ? code : `复制 ${code}` }}</span>
  </button>
</template>

<style scoped>
.copy {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  height: 26px;
  padding: 0 10px;
  border: 1px solid var(--accent);
  border-radius: 999px;
  background: var(--accent-soft);
  color: var(--accent);
  font: inherit;
  font-size: 13px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  letter-spacing: 0.5px;
  cursor: pointer;
  white-space: nowrap;
  transition: background-color 0.15s, color 0.15s;
}
.copy:hover,
.copy:focus-visible {
  background: var(--accent);
  color: #fff;
  outline: none;
}
.copy.done {
  border-color: var(--ok);
  background: rgba(24, 160, 88, 0.12);
  color: var(--ok);
}
.copy.compact {
  height: 20px;
  padding: 0 7px;
  font-size: 12px;
}
</style>
