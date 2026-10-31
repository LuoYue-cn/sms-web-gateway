<script setup lang="ts">
import { computed } from 'vue'
import type { HealthPoint } from '@/api'

const props = defineProps<{ points: HealthPoint[]; tz: string }>()
const W = 720
const H = 150
const PAD = { l: 30, r: 8, t: 8, b: 22 }
const iw = W - PAD.l - PAD.r
const ih = H - PAD.t - PAD.b
const n = computed(() => Math.max(1, props.points.length - 1))
const x = (i: number): number => PAD.l + (i / n.value) * iw
const y = (v: number): number => PAD.t + ih - (Math.max(0, Math.min(100, v)) / 100) * ih

/** 缺数据的小时断开线条，而不是把两端连起来冒充有数据 */
function path(get: (p: HealthPoint) => number | null): string {
  let d = ''
  let pen = false
  props.points.forEach((p, i) => {
    const v = get(p)
    if (v == null) {
      pen = false
      return
    }
    d += `${pen ? 'L' : 'M'}${x(i).toFixed(1)},${y(v).toFixed(1)}`
    pen = true
  })
  return d
}
const bat = computed(() => path((p) => p.battery))
const onl = computed(() => path((p) => p.online_pct))
const labels = computed(() => {
  const step = Math.max(1, Math.ceil(props.points.length / 6))
  return props.points
    .map((p, i) => ({ i, t: p.t }))
    .filter((v) => v.i % step === 0)
    .map((v) => ({
      i: v.i,
      text: new Intl.DateTimeFormat('zh-CN', { hour: '2-digit', hourCycle: 'h23', timeZone: props.tz || undefined }).format(new Date(v.t)) + '时',
    }))
})
</script>

<template>
  <svg :viewBox="`0 0 ${W} ${H}`" role="img" aria-label="电量与在线率">
    <g v-for="t in [0, 50, 100]" :key="t">
      <line :x1="PAD.l" :x2="W - PAD.r" :y1="y(t)" :y2="y(t)" class="grid" />
      <text :x="PAD.l - 5" :y="y(t) + 4" text-anchor="end" class="axis">{{ t }}</text>
    </g>
    <path :d="onl" class="line onl" />
    <path :d="bat" class="line bat" />
    <text v-for="l in labels" :key="l.i" :x="x(l.i)" :y="H - 6" text-anchor="middle" class="axis">{{ l.text }}</text>
  </svg>
</template>

<style scoped>
svg {
  width: 100%;
  height: auto;
  display: block;
}
.grid {
  stroke: var(--border);
}
.axis {
  fill: var(--text-3);
  font-size: 11px;
}
.line {
  fill: none;
  stroke-width: 2;
  stroke-linejoin: round;
  stroke-linecap: round;
}
.bat {
  stroke: var(--ok);
}
.onl {
  stroke: var(--accent);
  stroke-dasharray: 5 3;
}
</style>
