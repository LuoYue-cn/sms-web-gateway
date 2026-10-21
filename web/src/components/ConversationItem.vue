<script setup lang="ts">
import { computed } from 'vue'
import { NBadge, NIcon, NTag } from 'naive-ui'
import { AlertCircle, PinSharp } from '@vicons/ionicons5'
import type { Conversation } from '@/api'
import Avatar from './Avatar.vue'
import { displayName } from '@/utils/sms'
import { formatRelative } from '@/utils/time'

const props = defineProps<{ conv: Conversation; active: boolean; tz: string; now: number; showDevice: boolean }>()
const emit = defineEmits<{ (e: 'select'): void; (e: 'menu', ev: MouseEvent): void }>()

const name = computed(() => displayName(props.conv))
const sub = computed(() => (props.conv.contact_name?.trim() ? props.conv.phone_number : ''))
const prefix = computed(() => (props.conv.last_direction === 'outgoing' ? '我：' : ''))
</script>

<template>
  <div
    class="item"
    :class="{ active, unread: conv.unread_count > 0 }"
    role="button"
    tabindex="0"
    @click="emit('select')"
    @keydown.enter="emit('select')"
    @contextmenu.prevent="emit('menu', $event)"
  >
    <n-badge :value="conv.unread_count" :max="99" :show="conv.unread_count > 0" :offset="[-4, 4]">
      <avatar :name="name" :size="42" />
    </n-badge>
    <div class="body">
      <div class="row1">
        <span class="name">
          <n-icon v-if="conv.pinned" :component="PinSharp" size="12" class="pin" />
          <span class="nm">{{ name }}</span>
          <span v-if="sub" class="num">{{ sub }}</span>
        </span>
        <span class="time">{{ formatRelative(conv.last_message_at, tz, now) }}</span>
      </div>
      <div class="row2">
        <span class="last sms-text-line">
          <n-icon v-if="conv.has_failed" :component="AlertCircle" class="fail" size="14" title="有发送失败的短信" />
          {{ prefix }}{{ conv.last_message }}
        </span>
        <n-tag v-if="showDevice" size="tiny" round :bordered="false" class="dev">{{ conv.device_name }}</n-tag>
      </div>
    </div>
    <button class="more" type="button" aria-label="更多操作" @click.stop="emit('menu', $event)">⋯</button>
  </div>
</template>

<style scoped>
.item {
  position: relative;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 14px;
  cursor: pointer;
  border-left: 3px solid transparent;
  transition: background-color 0.15s ease;
  outline: none;
}
.item:hover,
.item:focus-visible {
  background: var(--panel-2);
}
.item.active {
  background: var(--accent-soft);
  border-left-color: var(--accent);
}
.body {
  flex: 1;
  min-width: 0;
}
.row1,
.row2 {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.name {
  display: flex;
  align-items: baseline;
  gap: 6px;
  min-width: 0;
  flex: 1;
}
.nm {
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.unread .nm {
  color: var(--text);
}
.num {
  font-size: 12px;
  color: var(--text-3);
  white-space: nowrap;
}
.pin {
  color: var(--accent);
  align-self: center;
  flex: none;
}
.time {
  font-size: 12px;
  color: var(--text-3);
  white-space: nowrap;
  flex: none;
}
.last {
  flex: 1;
  min-width: 0;
  color: var(--text-2);
  font-size: 13px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.unread .last {
  color: var(--text);
  font-weight: 500;
}
.fail {
  color: var(--danger);
  vertical-align: -2px;
  margin-right: 2px;
}
.dev {
  max-width: 90px;
  flex: none;
}
.more {
  display: none;
  border: none;
  background: transparent;
  color: var(--text-3);
  font-size: 18px;
  padding: 2px 6px;
  cursor: pointer;
  border-radius: 6px;
}
.item:hover .more {
  display: block;
}
@media (hover: none) {
  .more {
    display: block;
  }
}
</style>
