import { h, ref } from 'vue'
import { useDialog } from 'naive-ui'
import { ApiError, messagesApi } from '@/api'
import type { SendPayload, SendResult } from '@/api'
import { useChatStore } from '@/stores/chat'
import { useConversationsStore } from '@/stores/conversations'
import { useSettingsStore } from '@/stores/settings'
import { notify } from '@/utils/notify'
import { warningText } from '@/utils/sms'

/**
 * 发送短信的完整流程：
 *  - 422 confirmation_required：弹确认框展示警告，确认后带 confirm_warnings:true 重发
 *  - 423 send_paused：提示「已紧急暂停发送」并同步本地暂停状态
 *  - 其它错误：统一 message 提示（429 附带 retry_after）
 * 成功后把消息 / 会话写入 store。
 */
export function useSendMessage() {
  const dialog = useDialog()
  const sending = ref(false)

  function confirmWarnings(recipient: string, warnings: string[]): Promise<boolean> {
    return new Promise((resolve) => {
      dialog.warning({
        title: '发送前请确认',
        content: () =>
          h('div', { class: 'warn-confirm' }, [
            h('p', {}, `收件人：${recipient}`),
            ...(warnings.length ? warnings : ['unknown']).map((w) => h('p', { class: 'warn-line' }, `• ${warningText(w)}`)),
            h('p', {}, '确定要继续发送吗？'),
          ]),
        positiveText: '确认发送',
        negativeText: '取消',
        maskClosable: false,
        onPositiveClick: () => resolve(true),
        onNegativeClick: () => resolve(false),
        onClose: () => resolve(false),
        onMaskClick: () => resolve(false),
      })
    })
  }

  function applyResult(res: SendResult): void {
    const chat = useChatStore()
    const convs = useConversationsStore()
    convs.upsert(res.conversation)
    chat.upsert(res.message)
  }

  async function send(payload: SendPayload): Promise<SendResult | null> {
    if (sending.value) return null
    sending.value = true
    try {
      let body: SendPayload = { ...payload }
      for (let attempt = 0; attempt < 2; attempt++) {
        try {
          const res = await messagesApi.send(body)
          applyResult(res)
          return res
        } catch (e) {
          if (e instanceof ApiError && e.code === 'confirmation_required' && attempt === 0) {
            const raw = e.details.warnings
            const warnings = Array.isArray(raw) ? raw.map(String) : []
            const ok = await confirmWarnings(payload.recipient, warnings)
            if (!ok) return null
            body = { ...payload, confirm_warnings: true }
            continue
          }
          if (e instanceof ApiError && (e.status === 423 || e.code === 'send_paused')) {
            useSettingsStore().patch({ send_paused: true })
            notify.error('已紧急暂停发送：在恢复发送之前无法发出短信')
            return null
          }
          notify.fromError(e, '发送失败')
          return null
        }
      }
      return null
    } finally {
      sending.value = false
    }
  }

  return { send, sending }
}
