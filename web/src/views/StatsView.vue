<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { NButton, NDatePicker, NEmpty, NRadioButton, NRadioGroup, NSelect, NSpin } from 'naive-ui'
import DailyBars from '@/components/charts/DailyBars.vue'
import Heatmap from '@/components/charts/Heatmap.vue'
import Donut from '@/components/charts/Donut.vue'
import HBars from '@/components/charts/HBars.vue'
import HealthLines from '@/components/charts/HealthLines.vue'
import { statsApi } from '@/api'
import type { HealthReport, SmsCategory, StatsReport, StatsRange, TaskStatus } from '@/api'
import { useDevicesStore } from '@/stores/devices'
import { useSettingsStore } from '@/stores/settings'
import { notify } from '@/utils/notify'
import { simLabel } from '@/utils/sms'

const devices = useDevicesStore()
const settings = useSettingsStore()

const range = ref<StatsRange>('7d')
const custom = ref<[number, number] | null>(null)
const deviceId = ref<number>(0)
const data = ref<StatsReport | null>(null)
const health = ref<HealthReport | null>(null)
const loading = ref(false)
let seq = 0

const rangeOptions = [
  { label: '今天', value: 'today' },
  { label: '7 天', value: '7d' },
  { label: '30 天', value: '30d' },
  { label: '90 天', value: '90d' },
  { label: '自定义', value: 'custom' },
]
const deviceOptions = computed(() => [{ label: '全部设备', value: 0 }, ...devices.items.map((d) => ({ label: d.device_name, value: d.id }))])

/** 日期选择器给的是浏览器本地零点的毫秒数，用本地年月日拼成 YYYY-MM-DD（不能用 toISOString，会差一天） */
function ymd(ms: number): string {
  const d = new Date(ms)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

async function load(): Promise<void> {
  if (range.value === 'custom' && !custom.value) return
  const mine = ++seq
  loading.value = true
  try {
    const dev = deviceId.value || null
    const [s, h] = await Promise.all([
      statsApi.get({
        range: range.value,
        from: custom.value ? ymd(custom.value[0]) : undefined,
        to: custom.value ? ymd(custom.value[1]) : undefined,
        device_id: dev,
      }),
      statsApi.health(dev),
    ])
    if (mine !== seq) return
    data.value = s
    health.value = h
  } catch (e) {
    if (mine === seq) notify.fromError(e, '加载统计失败')
  } finally {
    if (mine === seq) loading.value = false
  }
}
onMounted(load)
watch([range, deviceId], load)
watch(custom, () => range.value === 'custom' && load())

const CAT: Record<SmsCategory, { label: string; color: string }> = {
  verification: { label: '验证码', color: '#2f7cf6' },
  bank: { label: '银行/支付', color: '#18a058' },
  logistics: { label: '快递物流', color: '#f0a020' },
  carrier: { label: '运营商', color: '#8a63d2' },
  marketing: { label: '营销推广', color: '#d03050' },
  other: { label: '其它通知', color: '#8b94a7' },
}
const STATUS: Record<TaskStatus, { label: string; color: string }> = {
  queued: { label: '排队中', color: '#8b94a7' },
  claimed: { label: '已领取', color: '#6aa6ff' },
  sending: { label: '发送中', color: '#4a8ff8' },
  sent: { label: '已发送', color: '#18a058' },
  delivered: { label: '已送达', color: '#0e7a43' },
  failed: { label: '失败', color: '#d03050' },
  unknown: { label: '结果未知', color: '#f0a020' },
}

const catSlices = computed(() =>
  data.value ? (Object.keys(CAT) as SmsCategory[]).map((k) => ({ key: k, label: CAT[k].label, color: CAT[k].color, value: data.value!.categories[k] ?? 0 })) : [],
)
const sendSlices = computed(() =>
  data.value ? (Object.keys(STATUS) as TaskStatus[]).map((k) => ({ key: k, label: STATUS[k].label, color: STATUS[k].color, value: data.value!.send_quality.statuses[k] ?? 0 })) : [],
)
const senderBars = computed(() => (data.value?.top_senders ?? []).map((s) => ({ label: s.label, value: s.count, sub: s.codes ? `其中验证码 ${s.codes}` : '' })))
const deviceBars = computed(() =>
  (data.value?.devices ?? []).map((d) => ({
    label: d.device_name + (d.sim_slot != null ? ` · ${simLabel(d.sim_slot)}` : ''),
    value: d.incoming + d.outgoing,
    sub: `收 ${d.incoming} / 发 ${d.outgoing}`,
  })),
)
const errorBars = computed(() => (data.value?.send_quality.errors ?? []).map((e) => ({ label: `${e.code}（${e.status === 'unknown' ? '结果未知' : '失败'}）`, value: e.count })))

interface Card {
  label: string
  value: number
  prev: number
  hint?: string
}
const cards = computed<Card[]>(() => {
  if (!data.value) return []
  const c = data.value.overview.current
  const p = data.value.overview.previous
  return [
    { label: '收到短信', value: c.incoming, prev: p.incoming },
    { label: '发出短信', value: c.outgoing, prev: p.outgoing },
    { label: '验证码', value: c.codes, prev: p.codes },
    { label: '不同发件方', value: c.senders, prev: p.senders },
  ]
})
function delta(c: Card): { text: string; tone: string } {
  if (c.prev === 0) return { text: c.value === 0 ? '与上一周期持平' : '上一周期无记录', tone: 'flat' }
  const pct = Math.round(((c.value - c.prev) / c.prev) * 100)
  if (pct === 0) return { text: '与上一周期持平', tone: 'flat' }
  return { text: `${pct > 0 ? '↑' : '↓'} ${Math.abs(pct)}% 较上一周期`, tone: pct > 0 ? 'up' : 'down' }
}

const rangeText = computed(() => {
  const r = data.value?.range
  if (!r) return ''
  return r.from === r.to ? r.from : `${r.from} ~ ${r.to}`
})
const totalMsgs = computed(() => (data.value ? data.value.overview.current.incoming + data.value.overview.current.outgoing : 0))
const q = computed(() => data.value?.send_quality)
const tz = computed(() => settings.timezone)
const healthDevices = computed(() => health.value?.devices ?? [])
const disabledDate = (ts: number): boolean => ts > Date.now()
</script>

<template>
  <div class="page-scroll">
    <div class="page-inner">
      <h2 class="page-title">
        <span>统计</span>
        <span class="muted sub">{{ rangeText }}<template v-if="data"> · 时区 {{ data.range.timezone }}</template></span>
      </h2>

      <section class="filters card">
        <n-radio-group v-model:value="range" size="small">
          <n-radio-button v-for="o in rangeOptions" :key="o.value" :value="o.value" :label="o.label" />
        </n-radio-group>
        <n-date-picker v-if="range === 'custom'" v-model:value="custom" type="daterange" size="small" clearable :is-date-disabled="disabledDate" />
        <n-select v-model:value="deviceId" :options="deviceOptions" size="small" class="dev-select" />
        <n-button size="small" :loading="loading" @click="load">刷新</n-button>
      </section>

      <n-spin :show="loading && !data">
        <n-empty v-if="range === 'custom' && !custom" description="请选择日期范围" class="empty" />
        <template v-else-if="data">
          <div class="cards">
            <div v-for="c in cards" :key="c.label" class="card stat">
              <div class="muted lbl">{{ c.label }}</div>
              <div class="val">{{ c.value }}</div>
              <div class="delta" :class="delta(c).tone">{{ delta(c).text }}</div>
            </div>
          </div>

          <section class="card box">
            <h3>每日收发</h3>
            <n-empty v-if="totalMsgs === 0" description="这段时间没有短信" size="small" />
            <daily-bars v-else :days="data.daily" />
          </section>

          <div class="two">
            <section class="card box">
              <h3>收到的短信类别 <small class="muted">（按内容规则自动归类）</small></h3>
              <donut :slices="catSlices" center-label="收到" />
            </section>
            <section class="card box">
              <h3>发件方 Top 10 <small class="muted">（有短信签名用签名，否则号码中间打码）</small></h3>
              <h-bars :items="senderBars" empty="这段时间没有收到短信" />
            </section>
          </div>

          <section class="card box">
            <h3>收信时段 <small class="muted">（星期 × 小时，颜色越深越多）</small></h3>
            <heatmap :grid="data.heatmap" />
          </section>

          <div class="two">
            <section class="card box">
              <h3>设备 / SIM 卡</h3>
              <h-bars :items="deviceBars" empty="这段时间没有短信" />
            </section>
            <section class="card box">
              <h3>
                发送质量
                <small v-if="q?.success_rate != null" class="rate">成功率 {{ q.success_rate }}%</small>
              </h3>
              <donut v-if="q && q.total > 0" :slices="sendSlices" center-label="发送任务" />
              <div v-else class="muted">这段时间没有从网页发出的短信。</div>
              <template v-if="errorBars.length">
                <h4>失败 / 结果未知的原因</h4>
                <h-bars :items="errorBars" />
              </template>
              <div v-if="q && q.statuses.unknown > 0" class="note">“结果未知”表示手机在发送途中中断，无法确定是否发出。系统不会自动重发，请在聊天页确认后手动处理。</div>
            </section>
          </div>

          <section class="card box">
            <h3>设备健康 <small class="muted">（最近 48 小时；心跳只保留 7 天。实线=电量%，虚线=在线率%）</small></h3>
            <n-empty v-if="!healthDevices.length" description="没有设备" size="small" />
            <div v-for="d in healthDevices" :key="d.device_id" class="hdev">
              <div class="hhead">
                <strong>{{ d.device_name }}</strong>
                <span class="muted">近 24 小时在线率 {{ d.uptime_24h == null ? '—' : d.uptime_24h + '%' }}</span>
              </div>
              <health-lines :points="d.points" :tz="tz" />
            </div>
          </section>
        </template>
      </n-spin>
    </div>
  </div>
</template>

<style scoped>
.sub {
  font-size: 13px;
  font-weight: 400;
}
.filters {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  padding: 12px 14px;
  margin-bottom: 14px;
}
.dev-select {
  width: 170px;
}
.cards {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 12px;
  margin-bottom: 14px;
}
.stat {
  padding: 14px 16px;
}
.lbl {
  font-size: 13px;
}
.val {
  font-size: 28px;
  font-weight: 700;
  line-height: 1.2;
  font-variant-numeric: tabular-nums;
}
.delta {
  font-size: 12px;
  color: var(--text-3);
}
.delta.up {
  color: var(--ok);
}
.delta.down {
  color: var(--danger);
}
.box {
  padding: 16px 18px;
  margin-bottom: 14px;
  min-width: 0;
}
.box h3 {
  margin: 0 0 12px;
  font-size: 15px;
}
.box h3 small {
  font-weight: 400;
  font-size: 12px;
}
.box h4 {
  margin: 16px 0 8px;
  font-size: 13px;
}
.rate {
  margin-left: 8px;
  padding: 1px 8px;
  border-radius: 999px;
  background: rgba(24, 160, 88, 0.12);
  color: var(--ok);
}
.two {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 14px;
}
.note {
  margin-top: 12px;
  padding: 8px 10px;
  border-radius: 8px;
  background: rgba(240, 160, 32, 0.12);
  font-size: 12px;
  color: var(--text-2);
}
.hdev + .hdev {
  margin-top: 14px;
}
.hhead {
  display: flex;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 2px;
  font-size: 13px;
}
.empty {
  padding: 60px 0;
}
@media (max-width: 820px) {
  .cards {
    grid-template-columns: 1fr 1fr;
  }
  .two {
    grid-template-columns: 1fr;
  }
  .dev-select {
    width: 100%;
  }
  .val {
    font-size: 24px;
  }
}
</style>
