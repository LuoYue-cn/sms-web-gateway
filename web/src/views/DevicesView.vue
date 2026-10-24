<script setup lang="ts">
import { computed, h, ref } from 'vue'
import { NAlert, NButton, NCheckbox, NEmpty, NIcon, NInput, NModal, NSpin, useDialog } from 'naive-ui'
import { AddOutline, RefreshOutline } from '@vicons/ionicons5'
import DeviceCard from '@/components/DeviceCard.vue'
import PairDeviceModal from '@/components/PairDeviceModal.vue'
import { devicesApi } from '@/api'
import type { Device } from '@/api'
import { useDevicesStore } from '@/stores/devices'
import { useSettingsStore } from '@/stores/settings'
import { useConversationsStore } from '@/stores/conversations'
import { useNow } from '@/composables/useNow'
import { notify } from '@/utils/notify'
import { checkRecipient } from '@/utils/sms'

const devices = useDevicesStore()
const settings = useSettingsStore()
const convs = useConversationsStore()
const dialog = useDialog()
const now = useNow(1000)

const showPair = ref(false)

// ---------- 编辑（名称 + SIM 号码） ----------
const renameShow = ref(false)
const renameTarget = ref<Device | null>(null)
const renameValue = ref('')
const renaming = ref(false)
/** 每个卡槽的手动号码输入框；空字符串 = 不使用手动值 */
const simInputs = ref<Record<number, string>>({})
const simErrors = ref<Record<number, string>>({})

const editableSims = computed(() => (renameTarget.value?.sims ?? []).filter((x) => x.state !== 'absent'))

function openRename(d: Device): void {
  renameTarget.value = d
  renameValue.value = d.device_name
  simInputs.value = Object.fromEntries((d.sims ?? []).map((x) => [x.slot, x.number_source === 'manual' ? (x.number ?? '') : '']))
  simErrors.value = {}
  renameShow.value = true
}

async function doRename(): Promise<void> {
  const d = renameTarget.value
  const name = renameValue.value.trim()
  if (!d || !name || renaming.value) return

  // 只提交有变化的卡槽：新填的 / 改过的 / 被清空的（清空 = 恢复为手机上报的号码）
  const sim_numbers: Record<number, string> = {}
  const errs: Record<number, string> = {}
  for (const s of d.sims ?? []) {
    const before = s.number_source === 'manual' ? (s.number ?? '') : ''
    const raw = (simInputs.value[s.slot] ?? '').trim()
    if (raw === before) continue
    if (raw === '') {
      sim_numbers[s.slot] = ''
      continue
    }
    const chk = checkRecipient(raw)
    if (!chk.ok) {
      errs[s.slot] = chk.reason
      continue
    }
    sim_numbers[s.slot] = chk.value
  }
  simErrors.value = errs
  if (Object.keys(errs).length) return

  renaming.value = true
  try {
    const patch: { device_name?: string; sim_numbers?: Record<number, string> } = {}
    if (name !== d.device_name) patch.device_name = name
    if (Object.keys(sim_numbers).length) patch.sim_numbers = sim_numbers
    if (Object.keys(patch).length) {
      const res = await devicesApi.update(d.id, patch)
      devices.upsert(res.device)
    }
    notify.success('已保存')
    renameShow.value = false
    void convs.refreshHead().catch(() => undefined)
  } catch (e) {
    notify.fromError(e, '保存失败')
  } finally {
    renaming.value = false
  }
}

// ---------- 删除 ----------
function openRemove(d: Device): void {
  const purge = ref(false)
  dialog.error({
    title: `删除设备「${d.device_name}」`,
    content: () =>
      h('div', {}, [
        h('p', {}, '删除设备会立即吊销它的访问令牌，并删除设备记录。该手机之后无法再同步或接收发送任务，需要重新扫码绑定。'),
        h('p', {}, '默认保留该设备已同步的会话和短信记录（仅在网页 / 服务端）。如果勾选下面的选项，将同时删除这些记录。'),
        h('p', { style: 'font-weight:600' }, '以上操作都只影响服务端数据，不会删除手机上的任何短信。'),
        h(
          NCheckbox,
          { checked: purge.value, 'onUpdate:checked': (v: boolean) => (purge.value = v) },
          { default: () => '同时删除该设备的全部会话与短信记录（仅服务端，不可恢复）' },
        ),
      ]),
    positiveText: '确认删除',
    negativeText: '取消',
    onPositiveClick: async () => {
      try {
        await devicesApi.remove(d.id, purge.value)
        devices.remove(d.id)
        notify.success(purge.value ? '设备及其服务端记录已删除' : '设备已删除，短信记录已保留')
        void convs.load().catch(() => undefined)
      } catch (e) {
        notify.fromError(e, '删除失败')
        return false
      }
    },
  })
}

async function refresh(): Promise<void> {
  try {
    await devices.load()
  } catch (e) {
    notify.fromError(e, '刷新失败')
  }
}
</script>

<template>
  <div class="page-scroll">
    <div class="page-inner">
      <h2 class="page-title">
        <span>设备管理</span>
        <span class="acts">
          <n-button size="small" :loading="devices.loading" @click="refresh">
            <template #icon><n-icon :component="RefreshOutline" /></template>
            刷新
          </n-button>
          <n-button type="primary" size="small" @click="showPair = true">
            <template #icon><n-icon :component="AddOutline" /></template>
            添加设备
          </n-button>
        </span>
      </h2>

      <n-alert type="info" :bordered="false" style="margin-bottom: 16px">
        「离线」只表示最近 {{ settings.onlineThreshold }} 秒内没有收到心跳，不代表手机一定关机（Android 后台限制可能延迟心跳）。
      </n-alert>

      <div v-if="devices.loading && !devices.loaded" class="center"><n-spin /></div>
      <n-empty v-else-if="!devices.items.length" description="还没有绑定任何设备" class="empty">
        <template #extra>
          <n-button type="primary" @click="showPair = true">添加设备</n-button>
        </template>
      </n-empty>
      <div v-else class="cards">
        <device-card
          v-for="d in devices.items"
          :key="d.id"
          :device="d"
          :now="now"
          :tz="settings.timezone"
          @rename="openRename"
          @remove="openRemove"
        />
      </div>
    </div>

    <pair-device-modal v-model:show="showPair" />

    <n-modal v-model:show="renameShow" preset="card" title="编辑设备" style="max-width: 440px" :mask-closable="!renaming">
      <div class="field">
        <label class="lbl">设备名称</label>
        <n-input v-model:value="renameValue" maxlength="64" placeholder="设备名称" autofocus @keyup.enter="doRename" />
      </div>
      <div v-for="s in editableSims" :key="s.slot" class="field">
        <label class="lbl">
          SIM {{ s.slot + 1 }} 号码<span v-if="s.operator" class="muted"> · {{ s.operator }}</span>
        </label>
        <n-input
          v-model:value="simInputs[s.slot]"
          :status="simErrors[s.slot] ? 'error' : undefined"
          :placeholder="s.number_source === 'device' && s.number ? `手机上报：${s.number}（填写可覆盖）` : '手机没有读到号码，可手动填写，如 +8613800138000'"
          clearable
          inputmode="tel"
          maxlength="24"
          @keyup.enter="doRename"
        />
        <div v-if="simErrors[s.slot]" class="err">{{ simErrors[s.slot] }}</div>
      </div>
      <div v-if="!editableSims.length" class="muted small">还没有收到手机上报的 SIM 信息，等设备下一次心跳后可在此填写号码。</div>
      <div class="muted small tip">手动填写的号码只保存在服务器，用于在网页上区分设备的各张卡，不会修改手机上的任何设置。清空输入框即恢复为手机上报的号码。</div>
      <div class="foot">
        <n-button :disabled="renaming" @click="renameShow = false">取消</n-button>
        <n-button type="primary" :loading="renaming" :disabled="!renameValue.trim()" @click="doRename">保存</n-button>
      </div>
    </n-modal>
  </div>
</template>

<style scoped>
.field {
  margin-bottom: 12px;
}
.lbl {
  display: block;
  margin-bottom: 4px;
  font-size: 13px;
  font-weight: 500;
}
.err {
  margin-top: 4px;
  font-size: 12px;
  color: var(--danger);
}
.tip {
  margin-top: 4px;
}
.acts {
  display: flex;
  gap: 8px;
}
.cards {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(420px, 1fr));
  gap: 16px;
}
.center {
  display: flex;
  justify-content: center;
  padding: 60px;
}
.empty {
  padding: 60px 0;
}
.foot {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 16px;
}
@media (max-width: 520px) {
  .cards {
    grid-template-columns: 1fr;
    gap: 12px;
  }
  .page-title {
    flex-wrap: wrap;
    font-size: 18px;
    margin-bottom: 12px;
  }
  .acts {
    flex: 1;
    justify-content: flex-end;
  }
}
</style>
