<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import ConversationList from '@/components/ConversationList.vue'
import ChatPane from '@/components/ChatPane.vue'
import NewMessageModal from '@/components/NewMessageModal.vue'
import { useChatStore } from '@/stores/chat'
import { useConversationsStore } from '@/stores/conversations'
import { useMedia, MOBILE_QUERY } from '@/composables/useMedia'
import { ApiError } from '@/api'
import { notify } from '@/utils/notify'
import type { Conversation } from '@/api'

const route = useRoute()
const router = useRouter()
const chat = useChatStore()
const convs = useConversationsStore()
const isMobile = useMedia(MOBILE_QUERY)
const showNew = ref(false)

const routeId = computed(() => (route.name === 'chat' ? Number(route.params.id) : null))
/** 手机上：有选中会话显示聊天，否则显示列表；桌面两栏同时显示 */
const showListPane = computed(() => !isMobile.value || routeId.value == null)
const showChatPane = computed(() => !isMobile.value || routeId.value != null)

async function openChat(id: number): Promise<void> {
  try {
    await chat.open(id)
    // 会话可能不在已加载列表里（如从搜索结果跳转），需要补进来以显示标题 / 设备
    if (!convs.byId.has(id)) {
      const first = chat.messages[0]
      await convs.ensureLoaded(id, first?.phone_number ?? '')
    }
    const c = convs.byId.get(id)
    if (c && c.unread_count > 0) void convs.markRead(id).catch(() => undefined)
  } catch (e) {
    if (e instanceof ApiError && e.status === 404) {
      notify.error('该会话不存在或已被删除')
      chat.close()
      await router.replace({ name: 'home' })
      return
    }
    notify.fromError(e, '加载消息失败')
  }
}

watch(
  routeId,
  (id) => {
    if (id == null || Number.isNaN(id)) chat.close()
    else void openChat(id)
  },
  { immediate: true },
)

// 会话被其它端删除时（chat.close 已由事件处理），若仍停留在该路由则回到首页
watch(
  () => chat.activeId,
  (id) => {
    if (id == null && routeId.value != null && !chat.loading) {
      // 仅当路由对应的会话已不在列表且已加载完时才回退，避免打开过程中误跳
      if (!convs.loading && convs.items.length && !convs.byId.has(routeId.value)) void router.replace({ name: 'home' })
    }
  },
)

function select(c: Conversation): void {
  void router.push({ name: 'chat', params: { id: c.id } })
}

onBeforeUnmount(() => chat.close())
</script>

<template>
  <div class="msgs-view" :class="{ mobile: isMobile }">
    <aside v-show="showListPane" class="left">
      <conversation-list :active-id="routeId" @select="select" @new="showNew = true" />
    </aside>
    <section v-show="showChatPane" class="right">
      <chat-pane :show-back="isMobile" @back="router.push({ name: 'home' })" />
    </section>
    <new-message-modal v-model:show="showNew" />
  </div>
</template>

<style scoped>
.msgs-view {
  display: flex;
  height: 100%;
  min-height: 0;
}
.left {
  width: 340px;
  flex: none;
  border-right: 1px solid var(--border);
  min-height: 0;
}
.right {
  flex: 1;
  min-width: 0;
  min-height: 0;
}
.mobile .left {
  width: 100%;
  border-right: none;
}
</style>
