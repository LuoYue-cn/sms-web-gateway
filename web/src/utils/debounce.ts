export function debounce<A extends unknown[]>(fn: (...args: A) => void, wait: number): ((...args: A) => void) & { cancel: () => void } {
  let t: ReturnType<typeof setTimeout> | null = null
  const wrapped = (...args: A): void => {
    if (t) clearTimeout(t)
    t = setTimeout(() => {
      t = null
      fn(...args)
    }, wait)
  }
  wrapped.cancel = (): void => {
    if (t) clearTimeout(t)
    t = null
  }
  return wrapped
}
