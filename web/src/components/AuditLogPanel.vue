<script setup lang="ts">
import { computed, h, onMounted, ref } from 'vue'
import { NDataTable, NSelect, NTag } from 'naive-ui'
import type { DataTableColumns } from 'naive-ui'
import { auditApi } from '@/api'
import type { AuditLog } from '@/api'
import { useDevicesStore } from '@/stores/devices'
import { useSettingsStore } from '@/stores/settings'
import { formatDateTime } from '@/utils/time'
import { notify } from '@/utils/notify'

const devices = useDevicesStore()
const settings = useSettingsStore()

const ACTIONS: Record<string, string> = {
  login: '登录',
  login_failed: '登录失败',
  logout: '退出登录',
  password_change: '修改密码',
  send_message: '发送短信',
  resolve_task: '处理未知任务',
  device_pair: '设备绑定',
  device_revoke: '设备吊销',
  device_token_rotate: '令牌轮换',
  emergency_pause: '紧急暂停',
  settings_update: '更新设置',
  root_op: 'Root 操作（设备上报）',
}
const ACTOR: Record<string, string> = { admin: '管理员', device: '设备', system: '系统' }

const items = ref<AuditLog[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(20)
const loading = ref(false)
const action = ref<string | null>(null)
const deviceId = ref<number | null>(null)
let seq = 0

const actionOptions = computed(() => [
  { label: '全部操作', value: '' },
  ...Object.entries(ACTIONS).map(([value, label]) => ({ label: `${label}（${value}）`, value })),
])
const deviceOptions = computed(() => [{ label: '全部设备', value: 0 }, ...devices.items.map((d) => ({ label: d.device_name, value: d.id }))])

async function load(): Promise<void> {
  const my = ++seq
  loading.value = true
  try {
    const res = await auditApi.list({
      page: page.value,
      page_size: pageSize.value,
      action: action.value || undefined,
      device_id: deviceId.value ?? undefined,
    })
    if (my !== seq) return
    items.value = res.items
    total.value = res.total
  } catch (e) {
    if (my === seq) notify.fromError(e, '加载审计日志失败')
  } finally {
    if (my === seq) loading.value = false
  }
}

onMounted(load)

const columns = computed<DataTableColumns<AuditLog>>(() => [
  { title: '时间', key: 'ts', width: 170, render: (r) => formatDateTime(r.ts, settings.timezone) },
  {
    title: '操作者',
    key: 'actor',
    width: 140,
    render: (r) => h('span', {}, [h(NTag, { size: 'tiny', bordered: false, round: true }, { default: () => ACTOR[r.actor_type] ?? r.actor_type }), ` ${r.actor ?? ''}`]),
  },
  { title: '操作', key: 'action', width: 170, render: (r) => ACTIONS[r.action] ?? r.action },
  { title: '对象', key: 'target', width: 120, render: (r) => r.target ?? '—' },
  { title: '详情', key: 'detail', minWidth: 220, ellipsis: { tooltip: true }, render: (r) => r.detail ?? '' },
  { title: 'IP', key: 'ip', width: 130, render: (r) => r.ip ?? '—' },
])

function onAction(v: string): void {
  action.value = v || null
  page.value = 1
  void load()
}
function onDevice(v: number): void {
  deviceId.value = v || null
  page.value = 1
  void load()
}
function onPage(p: number): void {
  page.value = p
  void load()
}
function onSize(s: number): void {
  pageSize.value = s
  page.value = 1
  void load()
}
</script>

<template>
  <div>
    <div class="filters">
      <n-select :value="action ?? ''" :options="actionOptions" size="small" style="width: 240px" @update:value="onAction" />
      <n-select v-if="devices.items.length" :value="deviceId ?? 0" :options="deviceOptions" size="small" style="width: 160px" @update:value="onDevice" />
    </div>
    <n-data-table
      remote
      size="small"
      :columns="columns"
      :data="items"
      :loading="loading"
      :row-key="(r: AuditLog) => r.id"
      :scroll-x="900"
      :pagination="{ page, pageSize, itemCount: total, showSizePicker: true, pageSizes: [20, 50, 100], onUpdatePage: onPage, onUpdatePageSize: onSize }"
    />
  </div>
</template>

<style scoped>
.filters {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 10px;
}
</style>
