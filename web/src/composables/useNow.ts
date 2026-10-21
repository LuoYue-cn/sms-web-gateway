import { onBeforeUnmount, onMounted, ref } from 'vue'

/** 每 intervalMs 刷新一次的"当前时间"，用于相对时间 / 倒计时显示。 */
export function useNow(intervalMs = 1000) {
  const now = ref(Date.now())
  let timer: ReturnType<typeof setInterval> | null = null
  onMounted(() => {
    now.value = Date.now()
    timer = setInterval(() => {
      now.value = Date.now()
    }, intervalMs)
  })
  onBeforeUnmount(() => {
    if (timer) clearInterval(timer)
  })
  return now
}
