<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { NAlert, NButton, NInput, NModal, NSpin } from 'naive-ui'
import QRCode from 'qrcode'
import { devicesApi } from '@/api'
import type { PairingInfo } from '@/api'
import { notify } from '@/utils/notify'
import { formatCountdown } from '@/utils/time'

const props = defineProps<{ show: boolean }>()
const emit = defineEmits<{ (e: 'update:show', v: boolean): void; (e: 'paired'): void }>()

const info = ref<PairingInfo | null>(null)
const qrUrl = ref('')
const loading = ref(false)
const err = ref('')
const left = ref(0)
const serverUrl = ref('')
let timer: ReturnType<typeof setInterval> | null = null

const expired = computed(() => info.value != null && left.value <= 0)
const insecure = computed(() => (info.value?.server_url ?? '').startsWith('http://'))

function stopTimer(): void {
  if (timer) clearInterval(timer)
  timer = null
}

function startTimer(): void {
  stopTimer()
  const tick = (): void => {
    if (!info.value) return
    left.value = info.value.expires_at - Date.now()
    if (left.value <= 0) {
      left.value = 0
      stopTimer()
    }
  }
  tick()
  timer = setInterval(tick, 500)
}

async function generate(): Promise<void> {
  loading.value = true
  err.value = ''
  qrUrl.value = ''
  try {
    const custom = serverUrl.value.trim()
    const res = await devicesApi.createPairing(custom || undefined)
    info.value = res
    // 二维码完全在本地渲染（qrcode 库），qr_payload 原样编码，不经任何外部服务
    qrUrl.value = await QRCode.toDataURL(res.qr_payload, { width: 280, margin: 2, errorCorrectionLevel: 'M' })
    if (!serverUrl.value) serverUrl.value = res.server_url
    startTimer()
    emit('paired')
  } catch (e) {
    err.value = e instanceof Error ? e.message : '生成失败'
    notify.fromError(e, '生成配对令牌失败')
  } finally {
    loading.value = false
  }
}

watch(
  () => props.show,
  (v) => {
    if (v) {
      info.value = null
      serverUrl.value = ''
      void generate()
    } else {
      stopTimer()
      info.value = null
      qrUrl.value = ''
    }
  },
)
onBeforeUnmount(stopTimer)

async function copy(text: string): Promise<void> {
  try {
    await navigator.clipboard.writeText(text)
    notify.success('已复制')
  } catch {
    notify.warning('复制失败，请手动选择复制')
  }
}
</script>

<template>
  <n-modal :show="show" preset="card" title="添加设备" style="max-width: 440px" @update:show="emit('update:show', $event)">
    <p class="muted" style="margin-top: 0">在手机 APK 中点击「扫描二维码」，扫描下方二维码完成绑定。令牌一次性有效，5 分钟内过期。</p>

    <div class="qr-box">
      <n-spin v-if="loading" />
      <template v-else-if="info && qrUrl">
        <div class="qr" :class="{ expired }">
          <img :src="qrUrl" alt="设备配对二维码" width="240" height="240" />
          <div v-if="expired" class="mask">已过期</div>
        </div>
        <div class="cd" :class="{ bad: expired || left < 60_000 }">
          {{ expired ? '二维码已过期，请重新生成' : `剩余有效时间 ${formatCountdown(left)}` }}
        </div>
      </template>
      <n-alert v-else-if="err" type="error" :bordered="false">{{ err }}</n-alert>
    </div>

    <n-alert v-if="insecure" type="warning" :bordered="false" style="margin: 8px 0">
      当前服务器地址为 HTTP。仅限本地测试；正式绑定手机、同步和发送短信必须使用 HTTPS。
    </n-alert>

    <div class="field">
      <label class="muted small">服务器地址（二维码中的 server_url，手机需能访问；可修改后重新生成）</label>
      <n-input v-model:value="serverUrl" placeholder="https://sms.example.com" />
    </div>

    <div v-if="info && !expired" class="field">
      <label class="muted small">手动输入用的配对令牌</label>
      <div class="tok">
        <code>{{ info.pairing_token }}</code>
        <n-button size="tiny" @click="copy(info.pairing_token)">复制</n-button>
      </div>
    </div>

    <div class="foot">
      <n-button @click="emit('update:show', false)">关闭</n-button>
      <n-button type="primary" :loading="loading" @click="generate">{{ expired || !info ? '重新生成' : '重新生成二维码' }}</n-button>
    </div>
  </n-modal>
</template>

<style scoped>
.qr-box {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  min-height: 280px;
  gap: 8px;
}
.qr {
  position: relative;
  padding: 8px;
  background: #fff;
  border-radius: 12px;
  border: 1px solid var(--border);
}
.qr img {
  display: block;
}
.qr.expired img {
  filter: blur(4px) grayscale(1);
  opacity: 0.4;
}
.mask {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: 700;
  font-size: 20px;
  color: #d03050;
}
.cd {
  font-variant-numeric: tabular-nums;
  font-weight: 600;
}
.cd.bad {
  color: var(--danger);
}
.field {
  margin-top: 12px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.small {
  font-size: 12px;
}
.tok {
  display: flex;
  align-items: center;
  gap: 8px;
}
.tok code {
  flex: 1;
  min-width: 0;
  padding: 4px 8px;
  background: var(--panel-2);
  border-radius: 6px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 12px;
}
.foot {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 16px;
}
</style>
