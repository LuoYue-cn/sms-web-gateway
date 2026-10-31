<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { NButton, NDropdown, NEmpty, NIcon, NSpin, NTag } from 'naive-ui'
import { ArrowBackOutline, EllipsisHorizontal } from '@vicons/ionicons5'
import Avatar from './Avatar.vue'
import MessageBubble from './MessageBubble.vue'
import MessageComposer from './MessageComposer.vue'
import ResolveTaskDialog from './ResolveTaskDialog.vue'
import { useChatStore } from '@/stores/chat'
import { useDevicesStore } from '@/stores/devices'
import { useSettingsStore } from '@/stores/settings'
import { useConversationActions } from '@/composables/useConversationActions'
import { useSendMessage } from '@/composables/useSendMessage'
import { checkRecipient, displayName } from '@/utils/sms'
import { dayKey, formatDayDivider } from '@/utils/time'
import { notify } from '@/utils/notify'
import type { Message } from '@/api'

defineProps<{ showBack: boolean }>()
const emit = defineEmits<{ (e: 'back'): void }>()

const chat = useChatStore()
const devices = useDevicesStore()
const settings = useSettingsStore()
const actions = useConversationActions()
const { send, sending } = useSendMessage()

const scroller = ref<HTMLElement | null>(null)
const composer = ref<InstanceType<typeof MessageComposer> | null>(null)
const resolveShow = ref(false)
const resolveMsg = ref<Message | null>(null)

const conv = computed(() => chat.conversation)
const title = computed(() => (conv.value ? displayName(conv.value) : ''))
const device = computed(() => (conv.value ? devices.byId.get(conv.value.device_id) ?? null : null))
const tz = computed(() => settings.timezone)

const recipientCheck = computed(() => (conv.value ? checkRecipient(conv.value.phone_number) : null))
const composerDisabled = computed(() => {
  if (!conv.value) return true
  if (recipientCheck.value && !recipientCheck.value.ok) return true
  return false
})
const composerReason = computed(() => {
  if (recipientCheck.value && !recipientCheck.value.ok) return '该发件人为服务号 / 字母号码，无法回复短信'
  return ''
})

interface Row {
  key: string
  divider?: string
  msg?: Message
}
const rows = computed<Row[]>(() => {
  const out: Row[] = []
  let last = ''
  for (const m of chat.messages) {
    const k = dayKey(m.timestamp, tz.value)
    if (k !== last) {
      out.push({ key: `d-${k}-${m.id}`, divider: formatDayDivider(m.timestamp, tz.value) })
      last = k
    }
    out.push({ key: `m-${m.id}`, msg: m })
  }
  return out
})

function atBottom(): boolean {
  const el = scroller.value
  return !el || el.scrollHeight - el.scrollTop - el.clientHeight < 120
}

async function scrollBottom(smooth = false): Promise<void> {
  await nextTick()
  const el = scroller.value
  if (el) el.scrollTo({ top: el.scrollHeight, behavior: smooth ? 'smooth' : 'auto' })
}

// 打开 / 切换会话：滚到底并标记已读
watch(
  () => chat.activeId,
  async (id) => {
    if (id == null) return
    await scrollBottom()
  },
)
watch(
  () => chat.loading,
  async (l, was) => {
    if (was && !l) {
      await scrollBottom()
      markReadIfNeeded()
    }
  },
)

// 新消息到来：若用户本来就在底部则跟随滚动；否则不打扰
watch(
  () => chat.messages[chat.messages.length - 1]?.id,
  async (id, old) => {
    if (id == null || old == null || id === old) return
    const follow = atBottom()
    if (follow) await scrollBottom(true)
    markReadIfNeeded()
  },
)

function markReadIfNeeded(): void {
  const c = conv.value
  if (c && c.unread_count > 0) void actions.markRead(c)
}

async function onScroll(): Promise<void> {
  const el = scroller.value
  if (!el || el.scrollTop > 60 || !chat.hasMore || chat.loadingOlder) return
  const prevHeight = el.scrollHeight
  try {
    const n = await chat.loadOlder()
    if (n > 0) {
      await nextTick()
      // 保持用户当前阅读位置不跳动
      el.scrollTop = el.scrollHeight - prevHeight + el.scrollTop
    }
  } catch (e) {
    notify.fromError(e, '加载更早消息失败')
  }
}

async function onSend(content: string, simSlot: number | undefined): Promise<void> {
  const c = conv.value
  const chk = recipientCheck.value
  if (!c || !chk || !chk.ok) return
  const res = await send({ device_id: c.device_id, recipient: chk.value, content, sim_slot: simSlot })
  if (res) {
    composer.value?.clear()
    await scrollBottom(true)
  }
}

function openResolve(m: Message): void {
  resolveMsg.value = m
  resolveShow.value = true
}

const menu = computed(() => [
  { label: '标记已读', key: 'read', disabled: !conv.value || conv.value.unread_count === 0 },
  { label: conv.value?.pinned ? '取消置顶' : '置顶', key: 'pin' },
  { type: 'divider', key: 'd' },
  { label: '删除会话（仅网页记录）', key: 'delete' },
])
function onMenu(k: string): void {
  const c = conv.value
  if (!c) return
  if (k === 'read') void actions.markRead(c)
  else if (k === 'pin') void actions.togglePin(c)
  else if (k === 'delete') actions.confirmDelete(c)
}
</script>

<template>
  <section class="chat">
    <template v-if="conv">
      <header class="head">
        <n-button v-if="showBack" quaternary circle size="small" aria-label="返回会话列表" @click="emit('back')">
          <template #icon><n-icon :component="ArrowBackOutline" /></template>
        </n-button>
        <avatar :name="title" :size="36" />
        <div class="who">
          <div class="t">{{ title }}</div>
          <div class="s muted">
            <span v-if="conv.contact_name">{{ conv.phone_number }} · </span>{{ conv.device_name }}
            <n-tag v-if="device && !device.online" size="tiny" round :bordered="false" type="warning" style="margin-left: 6px">设备离线</n-tag>
          </div>
        </div>
        <n-dropdown trigger="click" :options="menu" @select="onMenu">
          <n-button quaternary circle size="small" aria-label="更多操作">
            <template #icon><n-icon :component="EllipsisHorizontal" /></template>
          </n-button>
        </n-dropdown>
      </header>

      <div ref="scroller" class="msgs" @scroll.passive="onScroll">
        <div v-if="chat.loadingOlder" class="older"><n-spin size="small" /></div>
        <div v-else-if="chat.hasMore" class="older muted">向上滚动加载更早的消息</div>
        <div v-else-if="chat.messages.length" class="older muted">没有更早的消息了</div>

        <div v-if="chat.loading" class="center"><n-spin /></div>
        <n-empty v-else-if="!chat.messages.length" description="暂无消息" class="center" />
        <template v-else>
          <template v-for="r in rows" :key="r.key">
            <div v-if="r.divider" class="divider"><span>{{ r.divider }}</span></div>
            <message-bubble v-else-if="r.msg" :msg="r.msg" :tz="tz" @resolve="openResolve" />
          </template>
        </template>
      </div>

      <message-composer
        ref="composer"
        :sending="sending"
        :disabled="composerDisabled"
        :disabled-reason="composerReason"
        :device="device"
        @send="onSend"
      />
      <resolve-task-dialog v-model:show="resolveShow" :message="resolveMsg" :tz="tz" />
    </template>
    <n-empty v-else class="blank" description="选择一个会话开始查看" />
  </section>
</template>

<style scoped>
.chat {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  background: var(--chat-bg);
}
.head {
  flex: none;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 14px;
  background: var(--panel);
  border-bottom: 1px solid var(--border);
}
.who {
  flex: 1;
  min-width: 0;
}
.t {
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.s {
  font-size: 12px;
}
.msgs {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 8px 16px;
  overscroll-behavior: contain;
}
.older {
  text-align: center;
  font-size: 12px;
  padding: 6px;
}
.divider {
  text-align: center;
  margin: 12px 0 6px;
}
.divider span {
  display: inline-block;
  padding: 1px 10px;
  font-size: 12px;
  color: var(--text-2);
  background: var(--panel);
  border: 1px solid var(--border);
  border-radius: 999px;
}
.center {
  display: flex;
  justify-content: center;
  padding: 60px 0;
}
.blank {
  margin: auto;
}
@media (max-width: 820px) {
  .msgs {
    padding: 8px 10px;
  }
}
</style>
