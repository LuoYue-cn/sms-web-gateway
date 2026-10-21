<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import { NButton, NDropdown, NEmpty, NIcon, NSelect, NSkeleton, NSpin } from 'naive-ui'
import { AddOutline } from '@vicons/ionicons5'
import ConversationItem from './ConversationItem.vue'
import { useConversationsStore } from '@/stores/conversations'
import { useDevicesStore } from '@/stores/devices'
import { useSettingsStore } from '@/stores/settings'
import { useConversationActions } from '@/composables/useConversationActions'
import { useNow } from '@/composables/useNow'
import { notify } from '@/utils/notify'
import type { Conversation } from '@/api'

defineProps<{ activeId: number | null }>()
const emit = defineEmits<{ (e: 'select', c: Conversation): void; (e: 'new'): void }>()

const convs = useConversationsStore()
const devices = useDevicesStore()
const settings = useSettingsStore()
const actions = useConversationActions()
const now = useNow(30_000)

const deviceFilter = ref<number | null>(convs.deviceId)

const deviceOptions = computed(() => [
  { label: '全部设备', value: 0 },
  ...devices.items.map((d) => ({ label: d.device_name, value: d.id })),
])
const showDevice = computed(() => devices.items.length > 1)

async function reload(): Promise<void> {
  try {
    await convs.load()
  } catch (e) {
    notify.fromError(e, '加载会话失败')
  }
}

function onDevice(v: number): void {
  deviceFilter.value = v || null
  convs.setFilter({ deviceId: v || null })
  void reload()
}

function onScroll(e: Event): void {
  const el = e.target as HTMLElement
  if (el.scrollHeight - el.scrollTop - el.clientHeight < 240) {
    convs.loadMore().catch((err) => notify.fromError(err, '加载更多失败'))
  }
}

// ---------- 右键 / 更多菜单 ----------
const menuShow = ref(false)
const menuX = ref(0)
const menuY = ref(0)
const menuTarget = ref<Conversation | null>(null)

const menuOptions = computed(() => {
  const c = menuTarget.value
  if (!c) return []
  return [
    { label: '标记已读', key: 'read', disabled: c.unread_count === 0 },
    { label: c.pinned ? '取消置顶' : '置顶', key: 'pin' },
    { type: 'divider', key: 'd' },
    { label: '删除会话（仅网页记录）', key: 'delete' },
  ]
})

function openMenu(c: Conversation, ev: MouseEvent): void {
  menuShow.value = false
  menuTarget.value = c
  menuX.value = ev.clientX
  menuY.value = ev.clientY
  // 等 DOM 更新后再展示，保证重复右键时菜单重新定位（不依赖动画帧，后台标签页也能正常弹出）
  void nextTick(() => (menuShow.value = true))
}

function onMenuSelect(key: string): void {
  menuShow.value = false
  const c = menuTarget.value
  if (!c) return
  if (key === 'read') void actions.markRead(c)
  else if (key === 'pin') void actions.togglePin(c)
  else if (key === 'delete') actions.confirmDelete(c)
}

onMounted(() => {
  if (!convs.items.length) void reload()
  else void convs.refreshHead().catch(() => undefined)
})
</script>

<template>
  <section class="list">
    <div class="tools">
      <n-select v-if="devices.items.length > 1" :value="deviceFilter ?? 0" :options="deviceOptions" size="small" class="dev-sel" @update:value="onDevice" />
      <span v-else class="title">短信</span>
      <n-button type="primary" size="small" round class="btn-new" aria-label="新建短信" @click="emit('new')">
        <template #icon><n-icon :component="AddOutline" /></template>
        新建
      </n-button>
    </div>

    <div class="scroll" @scroll.passive="onScroll">
      <template v-if="convs.loading && !convs.items.length">
        <div v-for="i in 6" :key="i" class="sk">
          <n-skeleton circle :width="42" :height="42" />
          <div style="flex: 1">
            <n-skeleton text style="width: 40%" />
            <n-skeleton text style="width: 80%" />
          </div>
        </div>
      </template>
      <template v-else-if="convs.items.length">
        <transition-group name="fade" tag="div">
          <conversation-item
            v-for="c in convs.items"
            :key="c.id"
            :conv="c"
            :active="c.id === activeId"
            :tz="settings.timezone"
            :now="now"
            :show-device="showDevice"
            @select="emit('select', c)"
            @menu="openMenu(c, $event)"
          />
        </transition-group>
        <div class="more">
          <n-spin v-if="convs.loadingMore" size="small" />
          <span v-else-if="!convs.hasMore" class="muted">共 {{ convs.total }} 个会话</span>
          <n-button v-else text size="small" @click="convs.loadMore()">加载更多</n-button>
        </div>
      </template>
      <n-empty v-else description="还没有会话" class="empty">
        <template #extra>
          <span class="muted">绑定手机并同步后，短信会出现在这里；也可以点击「新建」发送短信。</span>
        </template>
      </n-empty>
    </div>

    <n-dropdown
      placement="bottom-start"
      trigger="manual"
      :x="menuX"
      :y="menuY"
      :show="menuShow"
      :options="menuOptions"
      @select="onMenuSelect"
      @clickoutside="menuShow = false"
    />
  </section>
</template>

<style scoped>
.list {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  background: var(--panel);
}
.tools {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 12px;
}
.title {
  flex: 1;
  font-weight: 600;
  font-size: 15px;
}
.dev-sel {
  flex: 1;
  min-width: 0;
}
.btn-new {
  flex: none;
}
.scroll {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  border-top: 1px solid var(--border);
}
.sk {
  display: flex;
  gap: 12px;
  padding: 12px 14px;
}
.more {
  text-align: center;
  padding: 12px;
  font-size: 12px;
}
.empty {
  padding: 60px 20px;
}
</style>
