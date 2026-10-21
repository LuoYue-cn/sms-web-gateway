<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { NAlert, NButton, NForm, NFormItem, NIcon, NInput, NInputNumber, NSelect, NSwitch, useDialog } from 'naive-ui'
import { DownloadOutline, WarningOutline } from '@vicons/ionicons5'
import AuditLogPanel from '@/components/AuditLogPanel.vue'
import { ApiError, authApi, exportApi } from '@/api'
import type { Settings } from '@/api'
import { useSettingsStore } from '@/stores/settings'
import { useDevicesStore } from '@/stores/devices'
import { notify } from '@/utils/notify'
import { listTimeZones } from '@/utils/time'

const route = useRoute()
const settings = useSettingsStore()
const devices = useDevicesStore()
const dialog = useDialog()

// ---------- 基本设置 ----------
type Editable = Omit<Settings, 'send_paused'>
const form = reactive<Editable>({
  site_name: '',
  timezone: '',
  online_threshold_seconds: 60,
  send_rate_per_minute: 10,
  max_content_length: 1000,
  home_country_code: '',
  heartbeat_interval_s: 30,
  poll_wait_s: 20,
})
const saving = ref(false)
const fieldErr = ref<Record<string, string>>({})
const tzOptions = computed(() => {
  const set = new Set(listTimeZones())
  if (form.timezone) set.add(form.timezone)
  return [...set].map((z) => ({ label: z, value: z }))
})

function fillForm(): void {
  const s = settings.data
  if (!s) return
  form.site_name = s.site_name
  form.timezone = s.timezone
  form.online_threshold_seconds = s.online_threshold_seconds
  form.send_rate_per_minute = s.send_rate_per_minute
  form.max_content_length = s.max_content_length
  form.home_country_code = s.home_country_code
  form.heartbeat_interval_s = s.heartbeat_interval_s
  form.poll_wait_s = s.poll_wait_s
}

const dirty = computed(() => {
  const s = settings.data
  if (!s) return false
  return (Object.keys(form) as (keyof Editable)[]).some((k) => form[k] !== s[k])
})

onMounted(async () => {
  try {
    await settings.load()
  } catch (e) {
    notify.fromError(e, '加载设置失败')
  }
  fillForm()
  if (route.hash === '#password') {
    await nextTick()
    document.getElementById('password')?.scrollIntoView({ behavior: 'smooth' })
  }
})
// 其它端修改设置（settings.update 事件）且本地未编辑时同步
watch(
  () => settings.data,
  () => {
    if (!dirty.value) fillForm()
  },
)

async function save(): Promise<void> {
  const err: Record<string, string> = {}
  if (!form.site_name.trim()) err.site_name = '请输入站点名称'
  if (!(form.online_threshold_seconds >= 10)) err.online_threshold_seconds = '至少 10 秒'
  if (!(form.send_rate_per_minute >= 1)) err.send_rate_per_minute = '至少为 1'
  if (!(form.max_content_length >= 1)) err.max_content_length = '至少为 1'
  if (form.home_country_code && !/^\+?[0-9]{1,4}$/.test(form.home_country_code.trim())) err.home_country_code = '例如 +86'
  fieldErr.value = err
  if (Object.keys(err).length) return
  saving.value = true
  try {
    await settings.save({ ...form, site_name: form.site_name.trim(), home_country_code: form.home_country_code.trim() })
    fillForm()
    notify.success('设置已保存')
  } catch (e) {
    if (e instanceof ApiError && e.status === 400) fieldErr.value = e.fields
    notify.fromError(e, '保存失败')
  } finally {
    saving.value = false
  }
}

// ---------- 紧急暂停 ----------
const pausing = ref(false)
async function setPaused(paused: boolean): Promise<void> {
  pausing.value = true
  try {
    await settings.setPaused(paused)
    notify[paused ? 'warning' : 'success'](paused ? '已紧急暂停发送' : '已恢复发送')
  } catch (e) {
    notify.fromError(e, '操作失败')
  } finally {
    pausing.value = false
  }
}
function togglePause(): void {
  if (!settings.sendPaused) {
    void setPaused(true)
    return
  }
  dialog.warning({
    title: '恢复发送',
    content: '恢复后，排队中的任务会再次被手机领取并发送。确认恢复吗？',
    positiveText: '恢复发送',
    negativeText: '取消',
    onPositiveClick: () => setPaused(false),
  })
}

// ---------- 修改密码 ----------
const pw = reactive({ current: '', next: '', confirm: '' })
const pwErr = ref<Record<string, string>>({})
const pwBusy = ref(false)
async function changePassword(): Promise<void> {
  const err: Record<string, string> = {}
  if (!pw.current) err.current = '请输入当前密码'
  if (pw.next.length < 10) err.next = '新密码至少 10 位'
  if (pw.confirm !== pw.next) err.confirm = '两次输入的新密码不一致'
  pwErr.value = err
  if (Object.keys(err).length) return
  pwBusy.value = true
  try {
    await authApi.changePassword(pw.current, pw.next)
    pw.current = pw.next = pw.confirm = ''
    notify.success('密码已修改，其他已登录的会话已失效')
  } catch (e) {
    if (e instanceof ApiError && e.status === 400) pwErr.value = { ...e.fields }
    notify.fromError(e, '修改密码失败')
  } finally {
    pwBusy.value = false
  }
}

// ---------- 导出 ----------
const exportDevice = ref<number>(0)
const exporting = ref<'csv' | 'json' | ''>('')
const exportDeviceOptions = computed(() => [{ label: '全部设备', value: 0 }, ...devices.items.map((d) => ({ label: d.device_name, value: d.id }))])
async function doExport(format: 'csv' | 'json'): Promise<void> {
  exporting.value = format
  try {
    const { blob, filename } = await exportApi.download(format, exportDevice.value || null)
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = filename
    document.body.appendChild(a)
    a.click()
    a.remove()
    setTimeout(() => URL.revokeObjectURL(url), 10_000)
    notify.success(`已导出 ${filename}`)
  } catch (e) {
    notify.fromError(e, '导出失败')
  } finally {
    exporting.value = ''
  }
}
</script>

<template>
  <div class="page-scroll">
    <div class="page-inner">
      <h2 class="page-title">设置</h2>

      <!-- 紧急暂停：置于最上方，状态醒目 -->
      <section class="card pause" :class="{ on: settings.sendPaused }">
        <n-icon :component="WarningOutline" :size="30" class="pi" />
        <div class="pt">
          <div class="ph">紧急暂停发送</div>
          <div class="muted">
            {{ settings.sendPaused ? '当前已暂停：所有设备都不会收到新的发送任务，网页也无法创建发送任务。已被手机领取但未发送的任务会保持原状，手机在发送前会再次检查。' : '一键暂停所有设备的短信发送。出现误发、刷屏或被滥用时使用。' }}
          </div>
        </div>
        <n-switch :value="settings.sendPaused" :loading="pausing" size="large" @update:value="togglePause">
          <template #checked>已暂停</template>
          <template #unchecked>发送中</template>
        </n-switch>
      </section>

      <section class="card sec">
        <h3>基本设置</h3>
        <n-form label-placement="top" class="grid" @submit.prevent="save">
          <n-form-item label="站点名称" :validation-status="fieldErr.site_name ? 'error' : undefined" :feedback="fieldErr.site_name">
            <n-input v-model:value="form.site_name" maxlength="64" />
          </n-form-item>
          <n-form-item label="时区" :validation-status="fieldErr.timezone ? 'error' : undefined" :feedback="fieldErr.timezone">
            <n-select v-model:value="form.timezone" :options="tzOptions" filterable />
          </n-form-item>
          <n-form-item label="设备在线阈值（秒）" :validation-status="fieldErr.online_threshold_seconds ? 'error' : undefined" :feedback="fieldErr.online_threshold_seconds || '超过该时间未收到心跳即显示离线'">
            <n-input-number v-model:value="form.online_threshold_seconds as number" :min="10" :max="3600" style="width: 100%" />
          </n-form-item>
          <n-form-item label="发送速率（条/分钟/设备）" :validation-status="fieldErr.send_rate_per_minute ? 'error' : undefined" :feedback="fieldErr.send_rate_per_minute">
            <n-input-number v-model:value="form.send_rate_per_minute as number" :min="1" :max="600" style="width: 100%" />
          </n-form-item>
          <n-form-item label="单条短信最大长度（字符）" :validation-status="fieldErr.max_content_length ? 'error' : undefined" :feedback="fieldErr.max_content_length">
            <n-input-number v-model:value="form.max_content_length as number" :min="1" :max="5000" style="width: 100%" />
          </n-form-item>
          <n-form-item label="本国区号" :validation-status="fieldErr.home_country_code ? 'error' : undefined" :feedback="fieldErr.home_country_code || '不以该区号开头的 + 号码会被视为国际号码并要求确认，例如 +86'">
            <n-input v-model:value="form.home_country_code" placeholder="+86" maxlength="5" />
          </n-form-item>
        </n-form>
        <div class="actions">
          <n-button :disabled="!dirty" @click="fillForm">还原</n-button>
          <n-button type="primary" :loading="saving" :disabled="!dirty" @click="save">保存设置</n-button>
        </div>
      </section>

      <section id="password" class="card sec">
        <h3>修改管理员密码</h3>
        <n-form label-placement="top" class="grid" @submit.prevent="changePassword">
          <n-form-item label="当前密码" :validation-status="pwErr.current || pwErr.current_password ? 'error' : undefined" :feedback="pwErr.current || pwErr.current_password">
            <n-input v-model:value="pw.current" type="password" show-password-on="click" autocomplete="current-password" />
          </n-form-item>
          <span class="spacer" />
          <n-form-item label="新密码（至少 10 位）" :validation-status="pwErr.next || pwErr.new_password ? 'error' : undefined" :feedback="pwErr.next || pwErr.new_password">
            <n-input v-model:value="pw.next" type="password" show-password-on="click" autocomplete="new-password" />
          </n-form-item>
          <n-form-item label="确认新密码" :validation-status="pwErr.confirm ? 'error' : undefined" :feedback="pwErr.confirm">
            <n-input v-model:value="pw.confirm" type="password" show-password-on="click" autocomplete="new-password" />
          </n-form-item>
        </n-form>
        <div class="actions">
          <n-button type="primary" :loading="pwBusy" @click="changePassword">修改密码</n-button>
        </div>
        <div class="muted small">修改成功后，该账号在其他浏览器上的登录会失效。</div>
      </section>

      <section class="card sec">
        <h3>数据导出</h3>
        <n-alert type="info" :bordered="false" style="margin-bottom: 12px">
          导出的是服务端保存的短信记录，包含私人内容，请妥善保管导出文件。
        </n-alert>
        <div class="export">
          <n-select v-model:value="exportDevice" :options="exportDeviceOptions" style="width: 200px" />
          <n-button :loading="exporting === 'csv'" :disabled="!!exporting" @click="doExport('csv')">
            <template #icon><n-icon :component="DownloadOutline" /></template>
            导出 CSV
          </n-button>
          <n-button :loading="exporting === 'json'" :disabled="!!exporting" @click="doExport('json')">
            <template #icon><n-icon :component="DownloadOutline" /></template>
            导出 JSON
          </n-button>
        </div>
      </section>

      <section class="card sec">
        <h3>审计日志</h3>
        <audit-log-panel />
      </section>
    </div>
  </div>
</template>

<style scoped>
.sec {
  padding: 18px 20px;
  margin-bottom: 16px;
}
.sec h3 {
  margin: 0 0 12px;
  font-size: 16px;
}
.grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0 20px;
}
.actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
.small {
  font-size: 12px;
  margin-top: 8px;
}
.export {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.pause {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 18px 20px;
  margin-bottom: 16px;
  border-width: 2px;
  border-color: var(--border);
}
.pause .pi {
  color: var(--warn);
  flex: none;
}
.pause .pt {
  flex: 1;
  min-width: 0;
}
.pause .ph {
  font-size: 17px;
  font-weight: 700;
  margin-bottom: 2px;
}
.pause.on {
  border-color: var(--danger);
  background: rgba(208, 48, 80, 0.08);
}
.pause.on .pi,
.pause.on .ph {
  color: var(--danger);
}
@media (max-width: 720px) {
  .grid {
    grid-template-columns: 1fr;
  }
  .spacer {
    display: none;
  }
  .pause {
    flex-wrap: wrap;
  }
}
</style>
