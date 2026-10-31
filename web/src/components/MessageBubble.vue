<script setup lang="ts">
import { computed } from 'vue'
import { NButton, NTag, NTooltip } from 'naive-ui'
import type { Message } from '@/api'
import CopyCodeButton from './CopyCodeButton.vue'
import { simLabel, statusMeta } from '@/utils/sms'
import { richSegments } from '@/utils/linkify'
import { formatClock, formatDateTime } from '@/utils/time'

const props = defineProps<{ msg: Message; tz: string }>()
const emit = defineEmits<{ (e: 'resolve', m: Message): void }>()

const outgoing = computed(() => props.msg.direction === 'outgoing')
const meta = computed(() => statusMeta(props.msg.status))
const sim = computed(() => simLabel(props.msg.sim_slot))
/** 链接显示成可点击的蓝链；收到的短信里识别出验证码时高亮。全部按片段渲染，不用 v-html */
const segs = computed(() => richSegments(props.msg.content, outgoing.value ? null : props.msg.code))
</script>

<template>
  <div class="row" :class="outgoing ? 'out' : 'in'">
    <div class="wrap">
      <!-- 短信内容一律按文本渲染（插值会转义），不使用 v-html -->
      <div class="bubble sms-text"><template v-for="(s, i) in segs" :key="i"><a v-if="s.href" :href="s.href" class="lnk" target="_blank" rel="noopener noreferrer nofollow">{{ s.text }}</a><mark v-else-if="s.code" class="code">{{ s.text }}</mark><template v-else>{{ s.text }}</template></template></div>
      <copy-code-button v-if="!outgoing && msg.code" :code="msg.code" class="copy-btn" />
      <div class="meta">
        <n-tooltip>
          <template #trigger>
            <span class="time">{{ formatClock(msg.timestamp, tz) }}</span>
          </template>
          {{ formatDateTime(msg.timestamp, tz) }}
        </n-tooltip>
        <span v-if="sim" class="sim">{{ sim }}</span>
        <template v-if="outgoing">
          <n-tooltip v-if="meta.hint">
            <template #trigger>
              <n-tag :type="meta.type" size="tiny" round :bordered="false">{{ meta.label }}</n-tag>
            </template>
            {{ meta.hint }}
          </n-tooltip>
          <n-tag v-else :type="meta.type" size="tiny" round :bordered="false">{{ meta.label }}</n-tag>
          <n-button v-if="msg.status === 'unknown' && msg.task_id" size="tiny" type="warning" secondary round @click="emit('resolve', msg)">
            处理
          </n-button>
        </template>
      </div>
      <div v-if="outgoing && msg.error_message && (msg.status === 'failed' || msg.status === 'unknown')" class="err sms-text">
        {{ msg.error_message }}
      </div>
    </div>
  </div>
</template>

<style scoped>
.row {
  display: flex;
  padding: 3px 0;
}
.row.out {
  justify-content: flex-end;
}
.wrap {
  display: flex;
  flex-direction: column;
  max-width: min(72%, 560px);
  animation: pop 0.18s ease;
}
.out .wrap {
  align-items: flex-end;
}
.bubble {
  padding: 8px 12px;
  border-radius: 16px;
  line-height: 1.5;
}
.in .bubble {
  background: var(--bubble-in);
  color: var(--bubble-in-text);
  border: 1px solid var(--bubble-in-border);
  box-shadow: 0 1px 2px rgba(20, 30, 60, 0.12);
  border-bottom-left-radius: 4px;
}
.out .bubble {
  background: var(--bubble-out);
  color: var(--bubble-out-text);
  border-bottom-right-radius: 4px;
}
.lnk {
  color: var(--link);
  text-decoration: underline;
  text-underline-offset: 2px;
  word-break: break-all;
}
.lnk:hover {
  filter: brightness(1.15);
}
/* 发出的气泡本身是蓝底，蓝链会看不见，改用浅色 */
.out .lnk {
  color: #fff;
  font-weight: 600;
}
.code {
  padding: 0 3px;
  border-radius: 4px;
  background: rgba(240, 160, 32, 0.28);
  color: inherit;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
  letter-spacing: 0.5px;
}
.copy-btn {
  align-self: flex-start;
  margin-top: 4px;
}
.meta {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 3px;
  padding: 0 4px;
  font-size: 11px;
  color: var(--text-3);
}
.out .meta {
  justify-content: flex-end;
}
.sim {
  padding: 0 5px;
  border: 1px solid var(--border);
  border-radius: 4px;
  line-height: 16px;
}
.err {
  margin-top: 2px;
  padding: 0 4px;
  font-size: 12px;
  color: var(--danger);
}
@keyframes pop {
  from {
    opacity: 0;
    transform: translateY(4px);
  }
}
@media (max-width: 820px) {
  .wrap {
    max-width: 86%;
  }
}
</style>
