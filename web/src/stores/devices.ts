import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { devicesApi } from '@/api'
import type { Device, EvSyncProgress } from '@/api'

export const useDevicesStore = defineStore('devices', () => {
  const items = ref<Device[]>([])
  const loaded = ref(false)
  const loading = ref(false)

  const onlineCount = computed(() => items.value.filter((d) => d.online).length)
  const byId = computed(() => new Map(items.value.map((d) => [d.id, d])))

  function name(id: number): string {
    return byId.value.get(id)?.device_name ?? `设备 #${id}`
  }

  async function load(): Promise<void> {
    loading.value = true
    try {
      items.value = (await devicesApi.list()).items
      loaded.value = true
    } finally {
      loading.value = false
    }
  }

  function upsert(d: Device): void {
    const i = items.value.findIndex((x) => x.id === d.id)
    if (i >= 0) items.value.splice(i, 1, d)
    else items.value.push(d)
  }

  function remove(id: number): void {
    items.value = items.value.filter((d) => d.id !== id)
  }

  function applySync(p: EvSyncProgress): void {
    const d = items.value.find((x) => x.id === p.device_id)
    if (!d) return
    d.sync_status = p.sync_status
    d.sync_total = p.sync_total
    d.sync_synced = p.sync_synced
  }

  /** 设备心跳只在服务端刷新 online；前端按阈值自行推算，避免等待下一次事件才变灰 */
  function recomputeOnline(thresholdSec: number, now = Date.now()): void {
    for (const d of items.value) {
      if (d.online && (d.last_seen == null || now - d.last_seen > thresholdSec * 1000 + 5000)) d.online = false
    }
  }

  return { items, loaded, loading, onlineCount, byId, name, load, upsert, remove, applySync, recomputeOnline }
})
