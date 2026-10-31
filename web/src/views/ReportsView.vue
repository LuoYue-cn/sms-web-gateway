<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { NAlert, NButton, NDatePicker, NEmpty, NIcon, NPopconfirm, NSpin, NTag } from 'naive-ui'
import { CopyOutline, RefreshOutline, SettingsOutline, TrashOutline } from '@vicons/ionicons5'
import Donut from '@/components/charts/Donut.vue'
import { aiApi, reportsApi } from '@/api'
import type { AiConfig, Report, SmsCategory } from '@/api'
import { copyText } from '@/utils/clipboard'
import { notify } from '@/utils/notify'
import { formatDateTime } from '@/utils/time'
import { useSettingsStore } from '@/stores/settings'

const settings = useSettingsStore()
const reports = ref<Report[]>([])
const current = ref<Report | null>(null)
const selected = ref<string>('')
const cfg = ref<AiConfig | null>(null)
const loading = ref(false)
const busy = ref(false)
const pick = ref<number | null>(null)
let poll: ReturnType<typeof setInterval> | null = null

const aiReady = computed(() => !!cfg.value && cfg.value.ai_enabled && !!cfg.value.ai_base_url && !!cfg.value.ai_model)
const running = computed(() => current.value?.status === 'running')
const tz = computed(() => settings.timezone)

function ymd(ms: number): string {
  const d = new Date(ms)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

async function refreshList(): Promise<void> {
  reports.value = (await reportsApi.list()).items
}

async function open(date: string): Promise<void> {
  selected.value = date
  try {
    current.value = await reportsApi.get(date)
  } catch (e) {
    current.value = null
    notify.fromError(e, '加载报告失败')
  }
  syncPolling()
}

function syncPolling(): void {
  if (running.value && !poll) {
    poll = setInterval(async () => {
      if (!selected.value) return
      try {
        current.value = await reportsApi.get(selected.value)
        if (current.value.status !== 'running') {
          stopPolling()
          await refreshList()
        }
      } catch {
        stopPolling()
      }
    }, 1500)
  } else if (!running.value) {
    stopPolling()
  }
}
function stopPolling(): void {
  if (poll) clearInterval(poll)
  poll = null
}

async function generate(date: string, useAi: boolean): Promise<void> {
  busy.value = true
  try {
    current.value = await reportsApi.generate(date, useAi)
    selected.value = date
    syncPolling()
    await refreshList()
  } catch (e) {
    notify.fromError(e, '生成失败')
  } finally {
    busy.value = false
  }
}

function yesterday(): string {
  const d = new Date()
  d.setDate(d.getDate() - 1)
  return ymd(d.getTime())
}
async function generatePicked(): Promise<void> {
  if (pick.value == null) return
  await generate(ymd(pick.value), aiReady.value)
}

async function remove(date: string): Promise<void> {
  try {
    await reportsApi.remove(date)
    if (selected.value === date) {
      selected.value = ''
      current.value = null
    }
    await refreshList()
    notify.success('已删除')
  } catch (e) {
    notify.fromError(e, '删除失败')
  }
}

function asText(r: Report): string {
  const c = r.content
  const lines = [`${r.date} 短信日报`, '', c.summary ?? '']
  const sect = (t: string, a?: string[]): void => {
    if (a?.length) lines.push('', t, ...a.map((x) => `- ${x}`))
  }
  sect('要点', c.highlights)
  sect('需要关注', c.anomalies)
  sect('建议', c.suggestions)
  return lines.join('\n')
}
async function copyReport(): Promise<void> {
  if (!current.value) return
  if (await copyText(asText(current.value))) notify.success('已复制报告文字')
  else notify.warning('浏览器不允许自动复制')
}

onMounted(async () => {
  loading.value = true
  try {
    const [, c] = await Promise.all([refreshList(), aiApi.get().catch(() => null)])
    cfg.value = c
    if (reports.value.length) await open(reports.value[0].date)
  } catch (e) {
    notify.fromError(e, '加载失败')
  } finally {
    loading.value = false
  }
})
onBeforeUnmount(stopPolling)

const CAT: Record<SmsCategory, { label: string; color: string }> = {
  verification: { label: '验证码', color: '#2f7cf6' },
  bank: { label: '银行/支付', color: '#18a058' },
  logistics: { label: '快递物流', color: '#f0a020' },
  carrier: { label: '运营商', color: '#8a63d2' },
  marketing: { label: '营销推广', color: '#d03050' },
  other: { label: '其它通知', color: '#8b94a7' },
}
const catSlices = computed(() => {
  const f = current.value?.content.facts
  return f ? (Object.keys(CAT) as SmsCategory[]).map((k) => ({ key: k, label: CAT[k].label, color: CAT[k].color, value: f.categories[k] ?? 0 })) : []
})
const hourly = computed(() => current.value?.content.facts?.hourly ?? [])
const hourMax = computed(() => Math.max(1, ...hourly.value))
const LEVEL = ['只发聚合数字', '聚合数字 + 发件来源与类别', '再加脱敏后的正文摘要']
const disabledDate = (ts: number): boolean => ts > Date.now()
</script>

<template>
  <div class="page-scroll">
    <div class="page-inner">
      <h2 class="page-title">
        <span>AI 日报</span>
        <router-link to="/settings#ai" class="cfg">
          <n-icon :component="SettingsOutline" :size="15" /> AI 服务设置
        </router-link>
      </h2>

      <n-alert v-if="cfg && !aiReady" type="info" :bordered="false" class="tip">
        还没有启用 AI 服务，现在生成的是不依赖 AI 的<strong>规则模板日报</strong>（只用统计数字拼出摘要）。
        <router-link to="/settings#ai">去配置 AI 服务</router-link>
      </n-alert>

      <section class="card bar">
        <n-button type="primary" size="small" :loading="busy" :disabled="running" @click="generate(yesterday(), aiReady)">
          <template #icon><n-icon :component="RefreshOutline" /></template>
          生成昨天的日报
        </n-button>
        <n-date-picker v-model:value="pick" type="date" size="small" clearable placeholder="选择其它日期" :is-date-disabled="disabledDate" />
        <n-button size="small" :disabled="pick == null || busy || running" @click="generatePicked">生成</n-button>
        <span class="muted small">同一天重新生成会覆盖原有报告。</span>
      </section>

      <n-spin :show="loading">
        <div class="layout">
          <aside class="card list">
            <n-empty v-if="!reports.length" description="还没有日报" size="small" class="e" />
            <button v-for="r in reports" :key="r.date" type="button" class="item" :class="{ on: r.date === selected }" @click="open(r.date)">
              <span class="d">{{ r.date }}</span>
              <n-tag v-if="r.status === 'running'" size="tiny" type="info" :bordered="false" round>生成中</n-tag>
              <n-tag v-else-if="r.status === 'failed'" size="tiny" type="error" :bordered="false" round>失败</n-tag>
              <n-tag v-else size="tiny" :type="r.source === 'ai' ? 'success' : 'default'" :bordered="false" round>{{ r.source === 'ai' ? 'AI' : '规则' }}</n-tag>
            </button>
          </aside>

          <section class="card detail">
            <n-empty v-if="!current" description="选择左侧的日期查看，或点击上面的按钮生成" class="e" />
            <template v-else>
              <header class="dh">
                <div>
                  <h3>{{ current.date }} 短信日报</h3>
                  <div class="muted small">
                    <template v-if="current.status === 'done'">
                      {{ current.source === 'ai' ? `AI 生成 · ${current.model}` : '规则模板' }} · 生成于 {{ formatDateTime(current.updated_at, tz) }}
                      <template v-if="current.source === 'ai'"> · 发送内容：{{ LEVEL[current.detail_level] }}</template>
                      <template v-if="current.prompt_tokens"> · {{ current.prompt_tokens }}+{{ current.completion_tokens }} tokens</template>
                    </template>
                  </div>
                </div>
                <div class="acts">
                  <n-button size="small" :disabled="current.status !== 'done'" @click="copyReport">
                    <template #icon><n-icon :component="CopyOutline" /></template>复制文字
                  </n-button>
                  <n-button size="small" :loading="running" :disabled="busy" @click="generate(current.date, aiReady)">重新生成</n-button>
                  <n-popconfirm @positive-click="remove(current.date)">
                    <template #trigger>
                      <n-button size="small" quaternary type="error" aria-label="删除">
                        <template #icon><n-icon :component="TrashOutline" /></template>
                      </n-button>
                    </template>
                    删除这份报告？
                  </n-popconfirm>
                </div>
              </header>

              <div v-if="running" class="running"><n-spin size="small" /> 正在生成，请稍候…（AI 最长等待约 1 分钟）</div>
              <n-alert v-else-if="current.status === 'failed'" type="error" :bordered="false">{{ current.error || '生成失败' }}</n-alert>

              <template v-else>
                <n-alert v-if="current.error" type="warning" :bordered="false" class="warn">{{ current.error }}</n-alert>
                <!-- AI 的输出一律当纯文本渲染，不解析 Markdown/HTML -->
                <p class="summary sms-text">{{ current.content.summary }}</p>

                <div v-if="current.content.highlights?.length" class="sec">
                  <h4>要点</h4>
                  <ul><li v-for="(x, i) in current.content.highlights" :key="i" class="sms-text">{{ x }}</li></ul>
                </div>
                <div v-if="current.content.anomalies?.length" class="sec warnsec">
                  <h4>需要关注</h4>
                  <ul><li v-for="(x, i) in current.content.anomalies" :key="i" class="sms-text">{{ x }}</li></ul>
                </div>
                <div v-if="current.content.suggestions?.length" class="sec">
                  <h4>建议</h4>
                  <ul><li v-for="(x, i) in current.content.suggestions" :key="i" class="sms-text">{{ x }}</li></ul>
                </div>

                <div v-if="current.content.facts" class="facts">
                  <h4>当日数据</h4>
                  <div class="nums">
                    <div><b>{{ current.content.facts.current.incoming }}</b><span>收到</span></div>
                    <div><b>{{ current.content.facts.current.outgoing }}</b><span>发出</span></div>
                    <div><b>{{ current.content.facts.current.codes }}</b><span>验证码</span></div>
                    <div><b>{{ current.content.facts.send.total }}</b><span>发送任务</span></div>
                  </div>
                  <div class="two">
                    <donut :slices="catSlices" center-label="收到" />
                    <div class="hours" role="img" aria-label="每小时收信量">
                      <div v-for="(n, h) in hourly" :key="h" class="hcol" :title="`${String(h).padStart(2, '0')}:00  ${n} 条`">
                        <div class="hbar" :style="{ height: (n / hourMax) * 100 + '%' }" />
                        <span class="hl">{{ h % 6 === 0 ? h : '' }}</span>
                      </div>
                    </div>
                  </div>
                </div>
              </template>
            </template>
          </section>
        </div>
      </n-spin>
    </div>
  </div>
</template>

<style scoped>
.cfg {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 13px;
  font-weight: 400;
}
.tip {
  margin-bottom: 12px;
}
.bar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  padding: 12px 14px;
  margin-bottom: 14px;
}
.small {
  font-size: 12px;
}
.layout {
  display: grid;
  grid-template-columns: 200px 1fr;
  gap: 14px;
  align-items: start;
}
.list {
  padding: 6px;
  display: flex;
  flex-direction: column;
  gap: 2px;
  max-height: 70vh;
  overflow-y: auto;
}
.item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 8px 10px;
  border: none;
  border-radius: 8px;
  background: transparent;
  color: var(--text);
  font: inherit;
  cursor: pointer;
  text-align: left;
}
.item:hover {
  background: var(--panel-2);
}
.item.on {
  background: var(--accent-soft);
  color: var(--accent);
  font-weight: 600;
}
.d {
  font-variant-numeric: tabular-nums;
}
.detail {
  padding: 18px 20px;
  min-width: 0;
}
.e {
  padding: 40px 0;
}
.dh {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 12px;
  flex-wrap: wrap;
  margin-bottom: 12px;
}
.dh h3 {
  margin: 0 0 2px;
  font-size: 17px;
}
.acts {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}
.running {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 24px 0;
  color: var(--text-2);
}
.warn {
  margin-bottom: 12px;
}
.summary {
  margin: 0 0 14px;
  font-size: 15px;
  line-height: 1.7;
}
.sec h4,
.facts h4 {
  margin: 14px 0 6px;
  font-size: 14px;
}
.sec ul {
  margin: 0;
  padding-left: 20px;
  line-height: 1.7;
}
.warnsec {
  padding: 4px 12px 8px;
  border-radius: 8px;
  background: rgba(240, 160, 32, 0.1);
}
.facts {
  margin-top: 18px;
  padding-top: 6px;
  border-top: 1px solid var(--border);
}
.nums {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 10px;
  margin-bottom: 14px;
}
.nums div {
  display: flex;
  flex-direction: column;
  padding: 10px 12px;
  border-radius: 10px;
  background: var(--panel-2);
}
.nums b {
  font-size: 22px;
  font-variant-numeric: tabular-nums;
}
.nums span {
  font-size: 12px;
  color: var(--text-2);
}
.two {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 18px;
  align-items: center;
}
.hours {
  display: flex;
  align-items: flex-end;
  gap: 2px;
  height: 120px;
  padding-bottom: 14px;
}
.hcol {
  flex: 1;
  height: 100%;
  display: flex;
  flex-direction: column;
  justify-content: flex-end;
  align-items: center;
  position: relative;
}
.hbar {
  width: 100%;
  min-height: 2px;
  border-radius: 2px 2px 0 0;
  background: var(--accent);
}
.hl {
  position: absolute;
  bottom: -14px;
  font-size: 10px;
  color: var(--text-3);
}
@media (max-width: 820px) {
  .layout {
    grid-template-columns: 1fr;
  }
  .list {
    flex-direction: row;
    overflow-x: auto;
    max-height: none;
  }
  .item {
    flex: none;
  }
  .two {
    grid-template-columns: 1fr;
  }
  .nums {
    grid-template-columns: 1fr 1fr;
  }
}
</style>
