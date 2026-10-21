<script setup lang="ts">
import { h, ref } from 'vue'
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

const devices = useDevicesStore()
const settings = useSettingsStore()
const convs = useConversationsStore()
const dialog = useDialog()
const now = useNow(1000)

const showPair = ref(false)

// ---------- 重命名 ----------
const renameShow = ref(false)
const renameTarget = ref<Device | null>(null)
const renameValue = ref('')
const renaming = ref(false)

function openRename(d: Device): void {
  renameTarget.value = d
  renameValue.value = d.device_name
  renameShow.value = true
}

async function doRename(): Promise<void> {
  const d = renameTarget.value
  const name = renameValue.value.trim()
  if (!d || !name || renaming.value) return
  renaming.value = true
  try {
    await devicesApi.rename(d.id, name)
    devices.upsert({ ...d, device_name: name })
    notify.success('已重命名')
    renameShow.value = false
    void convs.refreshHead().catch(() => undefined)
  } catch (e) {
    notify.fromError(e, '重命名失败')
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

    <n-modal v-model:show="renameShow" preset="card" title="重命名设备" style="max-width: 400px" :mask-closable="!renaming">
      <n-input v-model:value="renameValue" maxlength="64" placeholder="设备名称" autofocus @keyup.enter="doRename" />
      <div class="foot">
        <n-button :disabled="renaming" @click="renameShow = false">取消</n-button>
        <n-button type="primary" :loading="renaming" :disabled="!renameValue.trim()" @click="doRename">保存</n-button>
      </div>
    </n-modal>
  </div>
</template>

<style scoped>
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
