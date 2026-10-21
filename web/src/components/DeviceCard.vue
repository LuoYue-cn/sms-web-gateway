<script setup lang="ts">
import { computed } from 'vue'
import { NButton, NIcon, NProgress, NTag } from 'naive-ui'
import { BatteryChargingOutline, BatteryFullOutline, CreateOutline, TrashOutline, WifiOutline } from '@vicons/ionicons5'
import type { Device } from '@/api'
import { formatAgo, formatDateTime } from '@/utils/time'

const props = defineProps<{ device: Device; now: number; tz: string }>()
const emit = defineEmits<{ (e: 'rename', d: Device): void; (e: 'remove', d: Device): void }>()

const d = computed(() => props.device)

const NETWORK: Record<string, string> = { wifi: 'Wi-Fi', mobile: '移动数据', ethernet: '以太网', none: '无网络' }
const SYNC: Record<string, { label: string; type: 'default' | 'info' | 'success' | 'error' }> = {
  never: { label: '尚未同步', type: 'default' },
  idle: { label: '空闲', type: 'default' },
  running: { label: '同步中', type: 'info' },
  done: { label: '已完成', type: 'success' },
  error: { label: '同步出错', type: 'error' },
}
const WATCHDOG: Record<string, { label: string; type: 'default' | 'success' | 'warning' }> = {
  running: { label: '正常', type: 'success' },
  stopped: { label: '已停止', type: 'warning' },
  not_installed: { label: '未安装', type: 'default' },
}
const PROTECTION: Record<string, { label: string; type: 'default' | 'warning' | 'error' }> = {
  none: { label: '无', type: 'default' },
  cooldown: { label: '冷却中', type: 'warning' },
  protected: { label: '保护状态（已暂停自动恢复）', type: 'error' },
}

const syncPercent = computed(() => {
  if (d.value.sync_total > 0) return Math.min(100, Math.floor((d.value.sync_synced / d.value.sync_total) * 100))
  return d.value.sync_status === 'done' ? 100 : 0
})
const syncMeta = computed(() => SYNC[d.value.sync_status] ?? SYNC.idle)
const sims = computed(() => (d.value.sims?.length ? d.value.sims : []))
const lowBattery = computed(() => d.value.battery_level != null && d.value.battery_level <= 20 && !d.value.charging)

function simState(s: string): string {
  return ({ ready: '就绪', absent: '无卡', locked: '已锁定', unknown: '未知' } as Record<string, string>)[s] ?? s
}
function yesNo(v: boolean): string {
  return v ? '是' : '否'
}
</script>

<template>
  <article class="card dev" :class="{ off: !d.online }">
    <header class="top">
      <span class="dot" :class="d.online ? 'ok' : 'off'" />
      <div class="ttl">
        <div class="name">{{ d.device_name }}</div>
        <div class="muted small">Android {{ d.android_version || '—' }} · App {{ d.app_version || '—' }}</div>
      </div>
      <n-tag :type="d.online ? 'success' : 'default'" size="small" round :bordered="false">{{ d.online ? '在线' : '离线' }}</n-tag>
      <n-button quaternary circle size="small" aria-label="重命名" @click="emit('rename', d)">
        <template #icon><n-icon :component="CreateOutline" /></template>
      </n-button>
      <n-button quaternary circle size="small" type="error" aria-label="删除设备" @click="emit('remove', d)">
        <template #icon><n-icon :component="TrashOutline" /></template>
      </n-button>
    </header>

    <section class="grid">
      <div class="kv">
        <span class="k">电量</span>
        <span class="v" :class="{ bad: lowBattery }">
          <n-icon :component="d.charging ? BatteryChargingOutline : BatteryFullOutline" />
          {{ d.battery_level != null ? `${d.battery_level}%` : '—' }}{{ d.charging ? '（充电中）' : '' }}
        </span>
      </div>
      <div class="kv">
        <span class="k">网络</span>
        <span class="v"><n-icon :component="WifiOutline" /> {{ NETWORK[d.network_type] ?? (d.network_type || '—') }}</span>
      </div>
      <div class="kv">
        <span class="k">最近心跳</span>
        <span class="v" :title="formatDateTime(d.last_seen, tz)">{{ formatAgo(d.last_seen, now, '从未') }}</span>
      </div>
      <div class="kv">
        <span class="k">待发送任务</span>
        <span class="v">
          <n-tag v-if="d.pending_tasks > 0" size="small" type="warning" round :bordered="false">{{ d.pending_tasks }}</n-tag>
          <template v-else>0</template>
        </span>
      </div>
      <div class="kv full">
        <span class="k">SIM 卡（{{ d.sim_count }}）</span>
        <span class="v wrap">
          <template v-if="sims.length">
            <n-tag v-for="s in sims" :key="s.slot" size="small" :type="s.state === 'ready' ? 'info' : 'default'" :bordered="false">
              SIM {{ s.slot + 1 }}：{{ s.operator || '—' }} · {{ simState(s.state) }}
            </n-tag>
          </template>
          <template v-else>{{ d.sim_operator || '—' }}</template>
        </span>
      </div>
    </section>

    <section class="sync">
      <div class="sync-h">
        <span class="k">历史短信同步</span>
        <n-tag :type="syncMeta.type" size="small" round :bordered="false">{{ syncMeta.label }}</n-tag>
        <span class="muted small cnt">{{ d.sync_synced }} / {{ d.sync_total }}</span>
      </div>
      <n-progress
        type="line"
        :percentage="syncPercent"
        :status="d.sync_status === 'error' ? 'error' : d.sync_status === 'done' ? 'success' : 'default'"
        :processing="d.sync_status === 'running'"
        :height="10"
        :indicator-placement="'inside'"
      />
    </section>

    <section class="keep">
      <div class="keep-t">保活状态</div>
      <div class="grid">
        <div class="kv">
          <span class="k">保活模式</span>
          <span class="v">
            <n-tag size="small" :type="d.keepalive_mode === 'root_enhanced' ? 'success' : 'default'" :bordered="false">
              {{ d.keepalive_mode === 'root_enhanced' ? 'Root 增强' : '普通' }}
            </n-tag>
          </span>
        </div>
        <div class="kv">
          <span class="k">守护状态</span>
          <span class="v">
            <n-tag size="small" :type="(WATCHDOG[d.watchdog_status] ?? WATCHDOG.not_installed).type" :bordered="false">
              {{ (WATCHDOG[d.watchdog_status] ?? WATCHDOG.not_installed).label }}
            </n-tag>
          </span>
        </div>
        <div class="kv">
          <span class="k">最近同步</span>
          <span class="v" :title="formatDateTime(d.last_sync_at, tz)">{{ formatAgo(d.last_sync_at, now) }}</span>
        </div>
        <div class="kv">
          <span class="k">自动恢复次数</span>
          <span class="v">
            {{ d.recovery_count }}
            <span v-if="d.last_recovery_at" class="muted small">（最近 {{ formatAgo(d.last_recovery_at, now) }}）</span>
          </span>
        </div>
        <div class="kv">
          <span class="k">Root 授权</span>
          <span class="v">{{ yesNo(d.root_granted) }}</span>
        </div>
        <div class="kv">
          <span class="k">电池优化白名单</span>
          <span class="v" :class="{ bad: !d.battery_whitelisted }">{{ yesNo(d.battery_whitelisted) }}</span>
        </div>
        <div class="kv full">
          <span class="k">保护状态</span>
          <span class="v">
            <n-tag size="small" :type="(PROTECTION[d.protection_state] ?? PROTECTION.none).type" :bordered="false">
              {{ (PROTECTION[d.protection_state] ?? PROTECTION.none).label }}
            </n-tag>
          </span>
        </div>
      </div>
    </section>
  </article>
</template>

<style scoped>
.dev {
  padding: 16px;
  transition: box-shadow 0.2s, transform 0.2s, opacity 0.2s;
}
.dev:hover {
  box-shadow: 0 6px 20px rgba(20, 30, 60, 0.1);
}
.dev.off {
  opacity: 0.88;
}
.top {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
}
.ttl {
  flex: 1;
  min-width: 0;
}
.name {
  font-weight: 600;
  font-size: 16px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.small {
  font-size: 12px;
}
.dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: var(--text-3);
  flex: none;
}
.dot.ok {
  background: var(--ok);
  box-shadow: 0 0 0 4px rgba(24, 160, 88, 0.18);
}
.grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px 16px;
}
.kv {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}
.kv.full {
  grid-column: 1 / -1;
}
.k {
  font-size: 12px;
  color: var(--text-2);
}
.v {
  display: flex;
  align-items: center;
  gap: 4px;
  min-width: 0;
}
.v.wrap {
  flex-wrap: wrap;
}
.v.bad {
  color: var(--danger);
}
.sync {
  margin-top: 14px;
}
.sync-h {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}
.cnt {
  margin-left: auto;
}
.keep {
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px dashed var(--border);
}
.keep-t {
  font-weight: 600;
  margin-bottom: 8px;
}
@media (max-width: 520px) {
  .dev {
    padding: 12px;
  }
  .top {
    gap: 6px;
    margin-bottom: 10px;
  }
  .name {
    font-size: 15px;
  }
  .grid {
    gap: 8px 12px;
  }
  .kv {
    padding: 6px 8px;
    background: var(--panel-2);
    border-radius: 8px;
  }
  .kv.full {
    background: transparent;
    padding: 2px 0;
  }
  .sync,
  .keep {
    margin-top: 12px;
  }
}
</style>
