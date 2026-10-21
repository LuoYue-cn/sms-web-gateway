import { h } from 'vue'
import { useDialog } from 'naive-ui'
import { useRouter } from 'vue-router'
import type { Conversation } from '@/api'
import { useChatStore } from '@/stores/chat'
import { useConversationsStore } from '@/stores/conversations'
import { notify } from '@/utils/notify'
import { displayName } from '@/utils/sms'

/** 会话级操作：标记已读 / 置顶 / 删除（含明确说明的确认框）。 */
export function useConversationActions() {
  const dialog = useDialog()
  const router = useRouter()
  const convs = useConversationsStore()
  const chat = useChatStore()

  async function markRead(c: Conversation): Promise<void> {
    try {
      await convs.markRead(c.id)
    } catch (e) {
      notify.fromError(e, '标记已读失败')
    }
  }

  async function togglePin(c: Conversation): Promise<void> {
    try {
      await convs.setPinned(c.id, !c.pinned)
    } catch (e) {
      notify.fromError(e, '操作失败')
    }
  }

  function confirmDelete(c: Conversation): void {
    dialog.warning({
      title: '删除会话记录',
      content: () =>
        h('div', {}, [
          h('p', {}, `确定要删除与「${displayName(c)}」的会话吗？`),
          h('p', { style: 'font-weight:600' }, '仅删除网页 / 服务端上保存的记录，不会删除手机上的短信。'),
          h('p', { class: 'muted' }, '之后如果手机重新同步，这些短信仍可能再次出现在网页中。'),
        ]),
      positiveText: '删除',
      negativeText: '取消',
      onPositiveClick: async () => {
        try {
          await convs.remove(c.id)
          chat.onConversationDeleted(c.id)
          if (router.currentRoute.value.name === 'chat' && Number(router.currentRoute.value.params.id) === c.id) {
            await router.replace({ name: 'home' })
          }
          notify.success('已删除网页端会话记录（手机短信未受影响）')
        } catch (e) {
          notify.fromError(e, '删除失败')
          return false
        }
      },
    })
  }

  return { markRead, togglePin, confirmDelete }
}
