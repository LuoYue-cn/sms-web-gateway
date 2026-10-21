<script setup lang="ts">
import { h, ref } from 'vue'
import { NAlert, NButton, NModal, useDialog } from 'naive-ui'
import { tasksApi } from '@/api'
import type { Message, ResolveAction } from '@/api'
import { useChatStore } from '@/stores/chat'
import { useConversationsStore } from '@/stores/conversations'
import { notify } from '@/utils/notify'
import { formatDateTime } from '@/utils/time'

const props = defineProps<{ show: boolean; message: Message | null; tz: string }>()
const emit = defineEmits<{ (e: 'update:show', v: boolean): void }>()

const dialog = useDialog()
const busy = ref(false)

interface Choice {
  action: ResolveAction
  title: string
  desc: string
  type: 'warning' | 'success' | 'error'
  risk: string
}

const choices: Choice[] = [
  {
    action: 'resend',
    title: '重新发送',
    desc: '创建一条新的发送任务（新 ID），原任务标记为失败。',
    type: 'warning',
    risk: '风险：原短信可能其实已经发出，重新发送可能导致对方收到两条相同短信（并可能重复计费）。请先到手机的「已发送」里确认后再选择。',
  },
  {
    action: 'mark_sent',
    title: '标记为已发送',
    desc: '你已确认手机实际发出了这条短信，仅修正网页上的状态，不会再发送。',
    type: 'success',
    risk: '请确认：只在已经通过手机确认短信确实发出后才选择此项，否则网页状态将与事实不符。',
  },
  {
    action: 'mark_failed',
    title: '标记为失败',
    desc: '你已确认这条短信没有发出，仅修正网页上的状态，不会再发送。',
    type: 'error',
    risk: '请确认：如果短信其实已发出，标记失败后你可能会手动再发一次而造成重复。',
  },
]

function close(): void {
  emit('update:show', false)
}

function choose(c: Choice): void {
  const m = props.message
  if (!m?.task_id) return
  const taskId = m.task_id
  // 第二步：二次确认
  dialog.warning({
    title: `确认「${c.title}」`,
    content: () =>
      h('div', {}, [
        h('p', { class: 'sms-text', style: 'margin:0 0 8px' }, `收件人 ${m.phone_number}：${m.content.length > 80 ? m.content.slice(0, 80) + '…' : m.content}`),
        h('p', { style: 'margin:0 0 8px' }, c.desc),
        h('p', { style: 'margin:0;font-weight:600;color:var(--warn)' }, c.risk),
      ]),
    positiveText: `确认${c.title}`,
    negativeText: '返回',
    onPositiveClick: async () => {
      busy.value = true
      try {
        await tasksApi.resolve(taskId, c.action)
        notify.success(`已${c.title}`)
        close()
        // 事件可能稍后才到，这里主动刷新一次以立即反映状态 / 新任务
        await Promise.allSettled([useChatStore().refresh(), useConversationsStore().refreshHead()])
      } catch (e) {
        notify.fromError(e, '处理失败')
        return false
      } finally {
        busy.value = false
      }
    },
  })
}
</script>

<template>
  <n-modal :show="show" preset="card" title="处理「结果未知」的短信" style="max-width: 520px" :mask-closable="!busy" @update:show="emit('update:show', $event)">
    <template v-if="message">
      <n-alert type="warning" :bordered="false" style="margin-bottom: 12px">
        手机在发送过程中中断，无法确定这条短信是否已经发出。系统不会自动重发，请由你决定如何处理。
      </n-alert>
      <div class="info">
        <div><span class="muted">收件人：</span>{{ message.phone_number }}</div>
        <div><span class="muted">时间：</span>{{ formatDateTime(message.timestamp, tz) }}</div>
        <div class="sms-text content">{{ message.content }}</div>
      </div>
      <div class="choices">
        <div v-for="c in choices" :key="c.action" class="choice">
          <div class="txt">
            <strong>{{ c.title }}</strong>
            <div class="muted small">{{ c.desc }}</div>
          </div>
          <n-button :type="c.type" secondary :disabled="busy" @click="choose(c)">选择</n-button>
        </div>
      </div>
    </template>
  </n-modal>
</template>

<style scoped>
.info {
  padding: 10px 12px;
  border-radius: 8px;
  background: var(--panel-2);
  margin-bottom: 12px;
}
.content {
  margin-top: 6px;
  max-height: 120px;
  overflow: auto;
}
.choices {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.choice {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 12px;
  border: 1px solid var(--border);
  border-radius: 8px;
}
.small {
  font-size: 12px;
}
</style>
