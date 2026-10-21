import { onBeforeUnmount, onMounted, ref } from 'vue'

/** 响应式 matchMedia。 */
export function useMedia(query: string) {
  const matches = ref(typeof window !== 'undefined' && window.matchMedia ? window.matchMedia(query).matches : false)
  let mql: MediaQueryList | null = null
  const handler = (e: MediaQueryListEvent): void => {
    matches.value = e.matches
  }
  onMounted(() => {
    mql = window.matchMedia(query)
    matches.value = mql.matches
    mql.addEventListener('change', handler)
  })
  onBeforeUnmount(() => mql?.removeEventListener('change', handler))
  return matches
}

/** 手机布局：会话列表 -> 聊天 两屏切换 */
export const MOBILE_QUERY = '(max-width: 820px)'
/** 宽屏：显示第三栏（会话信息） */
export const WIDE_QUERY = '(min-width: 1360px)'
