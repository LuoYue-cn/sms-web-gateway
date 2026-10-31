<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { NBadge, NIcon, NPopover } from 'naive-ui'
import { CloseOutline, KeyOutline } from '@vicons/ionicons5'
import RecentCodesList from './RecentCodesList.vue'
import { useCodesStore } from '@/stores/codes'
import { useMedia, MOBILE_QUERY } from '@/composables/useMedia'

const codes = useCodesStore()
const router = useRouter()
const isMobile = useMedia(MOBILE_QUERY)
/**
 * 手机上用底部面板：弹出框以图标为锚点，在窄屏上会超出屏幕左侧被截断。
 * 自己写而不用抽屉组件：高度要由内容决定（只有一两条验证码时不该撑满半屏）。
 */
const sheet = ref(false)

onMounted(() => {
  void codes.load().catch(() => undefined)
})

function open(conversationId: number): void {
  sheet.value = false
  void router.push({ name: 'chat', params: { id: conversationId } })
}
function openSheet(): void {
  sheet.value = true
  void codes.load().catch(() => undefined)
}
function onKey(e: KeyboardEvent): void {
  if (e.key === 'Escape') sheet.value = false
}
watch(sheet, (on) => {
  if (on) document.addEventListener('keydown', onKey)
  else document.removeEventListener('keydown', onKey)
})
onBeforeUnmount(() => document.removeEventListener('keydown', onKey))
</script>

<template>
  <template v-if="isMobile">
    <button class="chip" type="button" aria-label="最近验证码" @click="openSheet">
      <n-badge :value="codes.count" :max="9" :show="codes.count > 0" :offset="[4, -2]" type="warning">
        <n-icon :component="KeyOutline" :size="18" />
      </n-badge>
    </button>
    <teleport to="body">
      <div v-if="sheet" class="sheet-root" role="dialog" aria-modal="true" aria-label="最近验证码">
        <div class="mask" @click="sheet = false" />
        <div class="sheet">
          <button class="x" type="button" aria-label="关闭" @click="sheet = false">
            <n-icon :component="CloseOutline" :size="20" />
          </button>
          <recent-codes-list @open="open" />
        </div>
      </div>
    </teleport>
  </template>

  <n-popover v-else trigger="click" placement="bottom-end" :show-arrow="false" style="width: 320px">
    <template #trigger>
      <button class="chip" type="button" aria-label="最近验证码">
        <n-badge :value="codes.count" :max="9" :show="codes.count > 0" :offset="[4, -2]" type="warning">
          <n-icon :component="KeyOutline" :size="18" />
        </n-badge>
      </button>
    </template>
    <recent-codes-list @open="open" />
  </n-popover>
</template>

<style scoped>
.chip {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 30px;
  border: none;
  border-radius: 999px;
  background: transparent;
  color: var(--text-2);
  cursor: pointer;
}
.chip:hover {
  background: var(--panel-2);
  color: var(--text);
}
.sheet-root {
  position: fixed;
  inset: 0;
  z-index: 3000;
}
.mask {
  position: absolute;
  inset: 0;
  background: rgba(0, 0, 0, 0.45);
}
.sheet {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 0;
  max-height: 75vh;
  overflow-y: auto;
  padding: 16px 16px calc(16px + env(safe-area-inset-bottom));
  background: var(--panel);
  color: var(--text);
  border-radius: 16px 16px 0 0;
  box-shadow: 0 -4px 24px rgba(0, 0, 0, 0.25);
}
.x {
  position: absolute;
  top: 10px;
  right: 10px;
  display: inline-flex;
  padding: 6px;
  border: none;
  border-radius: 50%;
  background: transparent;
  color: var(--text-2);
  cursor: pointer;
}
.sheet :deep(.head) {
  padding-right: 34px;
}
/* 只做入场动画：关闭时立即移除，避免页面被暂停时离场过渡卡住、面板关不掉 */
.mask {
  animation: fade-in 0.2s ease;
}
.sheet {
  animation: slide-up 0.22s ease;
}
@keyframes fade-in {
  from {
    opacity: 0;
  }
}
@keyframes slide-up {
  from {
    transform: translateY(100%);
  }
}
</style>
