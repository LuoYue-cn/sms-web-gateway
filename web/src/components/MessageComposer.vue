<script setup lang="ts">
import { computed, ref } from 'vue'
import { NButton, NIcon, NInput, NSelect } from 'naive-ui'
import { SendSharp } from '@vicons/ionicons5'
import type { Device } from '@/api'
import { useSettingsStore } from '@/stores/settings'

const props = defineProps<{
  sending: boolean
  disabled?: boolean
  disabledReason?: string
  device?: Device | null
}>()
const emit = defineEmits<{ (e: 'send', content: string, simSlot: number | undefined): void }>()

const settings = useSettingsStore()
const text = ref('')
const sim = ref<number | null>(null)

const max = computed(() => settings.maxContentLength)
const over = computed(() => text.value.length > max.value)
const canSend = computed(() => !props.disabled && !props.sending && text.value.trim().length > 0 && !over.value)
const simOptions = computed(() => [
  { label: '默认 SIM', value: -1 },
  ...(props.device?.sims ?? []).map((s) => ({
    label: `SIM ${s.slot + 1}${s.operator ? `（${s.operator}）` : ''}${s.state && s.state !== 'ready' ? ` · ${s.state}` : ''}`,
    value: s.slot,
  })),
])
const showSim = computed(() => (props.device?.sims?.length ?? 0) > 1)

function submit(): void {
  if (!canSend.value) return
  emit('send', text.value, sim.value ?? undefined)
}

function clear(): void {
  text.value = ''
}
defineExpose({ clear })

function onKey(e: KeyboardEvent): void {
  if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) {
    e.preventDefault()
    submit()
  }
}
</script>

<template>
  <div class="composer">
    <div v-if="disabled" class="disabled-tip">{{ disabledReason }}</div>
    <div class="inner">
      <n-input
        v-model:value="text"
        type="textarea"
        :autosize="{ minRows: 1, maxRows: 5 }"
        :disabled="disabled"
        placeholder="输入短信内容（Ctrl+Enter 发送）"
        :status="over ? 'error' : undefined"
        @keydown="onKey"
      />
      <n-button type="primary" :disabled="!canSend" :loading="sending" class="send" @click="submit">
        <template #icon><n-icon :component="SendSharp" /></template>
        发送
      </n-button>
    </div>
    <div class="foot">
      <n-select v-if="showSim" :value="sim ?? -1" :options="simOptions" size="tiny" class="sim" @update:value="(v: number) => (sim = v < 0 ? null : v)" />
      <span v-else class="hint">Ctrl+Enter 发送</span>
      <span class="count" :class="{ bad: over }">{{ text.length }} / {{ max }}</span>
    </div>
  </div>
</template>

<style scoped>
.composer {
  flex: none;
  padding: 10px 14px calc(8px + env(safe-area-inset-bottom));
  background: var(--panel);
  border-top: 1px solid var(--border);
}
.inner {
  display: flex;
  align-items: flex-end;
  gap: 8px;
}
.send {
  flex: none;
}
.foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-top: 4px;
  font-size: 12px;
  color: var(--text-3);
}
.sim {
  width: 190px;
}
.count.bad {
  color: var(--danger);
}
.disabled-tip {
  margin-bottom: 6px;
  font-size: 12px;
  color: var(--warn);
}
@media (max-width: 820px) {
  .composer {
    padding: 8px 10px calc(6px + env(safe-area-inset-bottom));
  }
  /* 手机上发送按钮只留图标，给输入框腾出宽度 */
  .send :deep(.n-button__content) {
    display: none;
  }
  .send :deep(.n-button__icon) {
    margin: 0;
  }
  .send {
    width: 40px;
    padding: 0;
  }
  .foot {
    justify-content: space-between;
  }
  .hint {
    display: none;
  }
  .sim {
    width: 150px;
  }
}
</style>
