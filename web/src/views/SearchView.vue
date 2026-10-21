<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { NButton, NEmpty, NIcon, NPagination, NSelect, NSpin, NTag } from 'naive-ui'
import { ArrowForwardOutline } from '@vicons/ionicons5'
import Avatar from '@/components/Avatar.vue'
import { messagesApi } from '@/api'
import type { SearchMessage } from '@/api'
import { useDevicesStore } from '@/stores/devices'
import { useSettingsStore } from '@/stores/settings'
import { excerptAround, messageDisplayName, splitHighlight, simLabel } from '@/utils/sms'
import { formatDateTime } from '@/utils/time'
import { notify } from '@/utils/notify'

const route = useRoute()
const router = useRouter()
const devices = useDevicesStore()
const settings = useSettingsStore()

const PAGE_SIZE = 20
const q = computed(() => (typeof route.query.q === 'string' ? route.query.q.trim() : ''))
const deviceId = ref<number | null>(null)
const page = ref(1)
const items = ref<SearchMessage[]>([])
const total = ref(0)
const loading = ref(false)
const searched = ref(false)
let seq = 0

const deviceOptions = computed(() => [
  { label: '全部设备', value: 0 },
  ...devices.items.map((d) => ({ label: d.device_name, value: d.id })),
])

async function run(): Promise<void> {
  if (!q.value) {
    items.value = []
    total.value = 0
    searched.value = false
    return
  }
  const my = ++seq
  loading.value = true
  try {
    const res = await messagesApi.search(q.value, { device_id: deviceId.value, page: page.value, page_size: PAGE_SIZE })
    if (my !== seq) return
    items.value = res.items
    total.value = res.total
    searched.value = true
  } catch (e) {
    if (my === seq) notify.fromError(e, '搜索失败')
  } finally {
    if (my === seq) loading.value = false
  }
}

watch(q, () => {
  page.value = 1
  void run()
}, { immediate: true })


function onDevice(v: number): void {
  deviceId.value = v || null
  page.value = 1
  void run()
}
function onPage(p: number): void {
  page.value = p
  void run()
}

function convIdOf(m: SearchMessage): number {
  return m.conversation_id ?? Number(m.conversation?.id)
}

function open(m: SearchMessage): void {
  void router.push({ name: 'chat', params: { id: convIdOf(m) } })
}

function deviceName(m: SearchMessage): string {
  return m.device_name || m.conversation?.device_name || devices.name(m.device_id)
}
</script>

<template>
  <div class="page-scroll">
    <div class="page-inner">
      <h2 class="page-title">
        <span>搜索结果<span v-if="q" class="muted kw">「{{ q }}」</span></span>
        <n-select v-if="devices.items.length > 1" :value="deviceId ?? 0" :options="deviceOptions" size="small" class="dev-sel" @update:value="onDevice" />
      </h2>

      <div v-if="!q" class="muted">请在顶部搜索框输入关键字，然后点击「搜索」。</div>
      <div v-else-if="loading && !items.length" class="center"><n-spin /></div>
      <n-empty v-else-if="searched && !items.length" description="没有找到匹配的短信" class="empty" />
      <template v-else>
        <div v-if="searched" class="muted total">共 {{ total }} 条结果</div>
        <ul class="results" :class="{ dim: loading }">
          <li v-for="m in items" :key="m.id" class="card res" role="button" tabindex="0" @click="open(m)" @keydown.enter="open(m)">
            <avatar :name="messageDisplayName(m)" :size="38" />
            <div class="body">
              <div class="r1">
                <strong>{{ messageDisplayName(m) }}</strong>
                <span v-if="m.contact_name" class="muted num">{{ m.phone_number }}</span>
                <n-tag size="tiny" round :bordered="false">{{ m.direction === 'incoming' ? '收到' : '发送' }}</n-tag>
                <n-tag v-if="simLabel(m.sim_slot)" size="tiny" round :bordered="false">{{ simLabel(m.sim_slot) }}</n-tag>
                <n-tag v-if="devices.items.length > 1" size="tiny" round :bordered="false">{{ deviceName(m) }}</n-tag>
                <span class="time muted">{{ formatDateTime(m.timestamp, settings.timezone) }}</span>
              </div>
              <!-- 高亮通过切片 + <mark> 渲染，内容始终是文本，不使用 v-html -->
              <div class="r2 sms-text">
                <template v-for="(s, i) in splitHighlight(excerptAround(m.content, q), q)" :key="i">
                  <mark v-if="s.hit">{{ s.text }}</mark>
                  <template v-else>{{ s.text }}</template>
                </template>
              </div>
            </div>
            <n-button quaternary circle size="small" tabindex="-1" aria-label="跳转到会话">
              <template #icon><n-icon :component="ArrowForwardOutline" /></template>
            </n-button>
          </li>
        </ul>
        <div v-if="total > PAGE_SIZE" class="pager">
          <n-pagination :page="page" :page-size="PAGE_SIZE" :item-count="total" :page-slot="5" @update:page="onPage" />
        </div>
      </template>
    </div>
  </div>
</template>

<style scoped>
.kw {
  font-weight: 400;
  font-size: 16px;
}
.dev-sel {
  width: 160px;
}
.center {
  display: flex;
  justify-content: center;
  padding: 60px;
}
.empty {
  padding: 60px 0;
}
.total {
  margin-bottom: 8px;
  font-size: 12px;
}
.results {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 10px;
  transition: opacity 0.15s;
}
.results.dim {
  opacity: 0.55;
}
.res {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 14px;
  cursor: pointer;
  outline: none;
}
.res:hover,
.res:focus-visible {
  border-color: var(--accent);
}
.body {
  flex: 1;
  min-width: 0;
}
.r1 {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
}
.num {
  font-size: 12px;
}
.time {
  margin-left: auto;
  font-size: 12px;
}
.r2 {
  margin-top: 4px;
  color: var(--text-2);
}
mark {
  background: rgba(240, 160, 32, 0.35);
  color: inherit;
  border-radius: 3px;
  padding: 0 1px;
}
.pager {
  display: flex;
  justify-content: center;
  margin: 16px 0;
}
@media (max-width: 820px) {
  .res {
    align-items: flex-start;
    gap: 10px;
    padding: 10px 12px;
  }
  .res > :last-child {
    display: none;
  }
  .time {
    margin-left: 0;
    flex-basis: 100%;
  }
  .dev-sel {
    width: 120px;
  }
}
</style>
