<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { NAlert, NButton, NForm, NFormItem, NInput, NModal, NSelect } from 'naive-ui'
import { useDevicesStore } from '@/stores/devices'
import { useSettingsStore } from '@/stores/settings'
import { useSendMessage } from '@/composables/useSendMessage'
import { checkRecipient } from '@/utils/sms'

const props = defineProps<{ show: boolean }>()
const emit = defineEmits<{ (e: 'update:show', v: boolean): void }>()

const router = useRouter()
const devices = useDevicesStore()
const settings = useSettingsStore()
const { send, sending } = useSendMessage()

const deviceId = ref<number | null>(null)
const recipient = ref('')
const content = ref('')
const sim = ref<number>(-1)
const touched = ref(false)

const deviceOptions = computed(() =>
  devices.items.map((d) => ({ label: `${d.device_name}${d.online ? '' : '（离线）'}`, value: d.id })),
)
const device = computed(() => devices.byId.get(deviceId.value ?? -1) ?? null)
const simOptions = computed(() => [
  { label: '默认 SIM', value: -1 },
  ...(device.value?.sims ?? []).map((s) => ({ label: `SIM ${s.slot + 1}${s.operator ? `（${s.operator}）` : ''}`, value: s.slot })),
])
const check = computed(() => checkRecipient(recipient.value))
const recipientError = computed(() => (touched.value && !check.value.ok ? check.value.reason : ''))
const max = computed(() => settings.maxContentLength)
const contentError = computed(() =>
  touched.value && !content.value.trim() ? '请输入短信内容' : content.value.length > max.value ? `内容不能超过 ${max.value} 个字符` : '',
)
const canSubmit = computed(() => deviceId.value != null && check.value.ok && content.value.trim().length > 0 && content.value.length <= max.value)

watch(
  () => props.show,
  (v) => {
    if (!v) return
    touched.value = false
    recipient.value = ''
    content.value = ''
    sim.value = -1
    const first = devices.items.find((d) => d.online) ?? devices.items[0]
    deviceId.value = first?.id ?? null
  },
)
watch(deviceId, () => (sim.value = -1))

async function submit(): Promise<void> {
  touched.value = true
  const chk = check.value
  if (!canSubmit.value || !chk.ok || deviceId.value == null) return
  const res = await send({
    device_id: deviceId.value,
    recipient: chk.value,
    content: content.value,
    sim_slot: sim.value >= 0 ? sim.value : undefined,
  })
  if (res) {
    emit('update:show', false)
    await router.push({ name: 'chat', params: { id: res.conversation.id } })
  }
}
</script>

<template>
  <n-modal :show="show" preset="card" title="新建短信" style="max-width: 520px" :mask-closable="!sending" @update:show="emit('update:show', $event)">
    <n-alert v-if="!devices.items.length" type="warning" :bordered="false">还没有绑定设备，请先到「设备管理」添加设备。</n-alert>
    <n-alert v-else-if="settings.sendPaused" type="error" :bordered="false" style="margin-bottom: 12px">已紧急暂停发送，暂时无法发送短信。</n-alert>
    <n-form v-if="devices.items.length" label-placement="top" @submit.prevent="submit">
      <n-form-item label="发送设备">
        <n-select v-model:value="deviceId" :options="deviceOptions" placeholder="选择设备" />
      </n-form-item>
      <n-form-item label="收件人号码" :validation-status="recipientError ? 'error' : undefined" :feedback="recipientError || undefined">
        <n-input v-model:value="recipient" placeholder="例如 13800138000 或 +819012345678" inputmode="tel" @blur="touched = true" />
      </n-form-item>
      <n-form-item v-if="(device?.sims.length ?? 0) > 1" label="SIM 卡（可选）">
        <n-select v-model:value="sim" :options="simOptions" />
      </n-form-item>
      <n-form-item label="短信内容" :validation-status="contentError ? 'error' : undefined" :feedback="contentError || undefined">
        <n-input v-model:value="content" type="textarea" :autosize="{ minRows: 3, maxRows: 8 }" placeholder="输入短信内容" />
      </n-form-item>
      <div class="foot">
        <span class="muted">{{ content.length }} / {{ max }}　一次只能发送给一个号码</span>
        <div>
          <n-button :disabled="sending" @click="emit('update:show', false)">取消</n-button>
          <n-button type="primary" :loading="sending" :disabled="!canSubmit" style="margin-left: 8px" @click="submit">发送</n-button>
        </div>
      </div>
    </n-form>
  </n-modal>
</template>

<style scoped>
.foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  font-size: 12px;
}
</style>
