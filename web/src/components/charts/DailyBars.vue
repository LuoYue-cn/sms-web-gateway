<script setup lang="ts">
import { computed, ref } from 'vue'
import type { StatsDay } from '@/api'

const props = defineProps<{ days: StatsDay[] }>()

const W = 720
const H = 220
const PAD = { l: 36, r: 8, t: 10, b: 24 }
const hover = ref<number | null>(null)

/** 纵轴固定 4 等份，步长取 1/2/5×10^n，刻度才是整齐的整数 */
const step = computed(() => {
  const m = Math.max(1, ...props.days.map((d) => Math.max(d.incoming, d.outgoing)))
  const raw = m / 4
  const pow = 10 ** Math.floor(Math.log10(raw))
  const n = raw / pow
  return Math.max(1, (n <= 1 ? 1 : n <= 2 ? 2 : n <= 5 ? 5 : 10) * pow)
})
const max = computed(() => step.value * 4)
const ticks = computed(() => [0, 1, 2, 3, 4].map((i) => i * step.value))
const innerW = W - PAD.l - PAD.r
const innerH = H - PAD.t - PAD.b
const slot = computed(() => innerW / Math.max(1, props.days.length))
const barW = computed(() => Math.max(1.5, Math.min(18, slot.value * 0.34)))
const y = (v: number): number => PAD.t + innerH - (v / max.value) * innerH
const cx = (i: number): number => PAD.l + slot.value * (i + 0.5)
const labelEvery = computed(() => Math.max(1, Math.ceil(props.days.length / 8)))
const cur = computed(() => (hover.value == null ? null : props.days[hover.value]))
</script>

<template>
  <div class="wrap">
    <div class="readout" aria-live="polite">
      <template v-if="cur">
        <strong>{{ cur.date }}</strong>
        <span><i class="dot in" />收 {{ cur.incoming }}</span>
        <span><i class="dot out" />发 {{ cur.outgoing }}</span>
        <span><i class="dot code" />验证码 {{ cur.codes }}</span>
      </template>
      <template v-else>
        <span><i class="dot in" />收到</span>
        <span><i class="dot out" />发出</span>
        <span class="muted">鼠标移到柱子上查看当天数字</span>
      </template>
    </div>
    <svg :viewBox="`0 0 ${W} ${H}`" role="img" aria-label="每日收发短信数量" preserveAspectRatio="xMidYMid meet" @mouseleave="hover = null">
      <g v-for="t in ticks" :key="t">
        <line :x1="PAD.l" :x2="W - PAD.r" :y1="y(t)" :y2="y(t)" class="grid" />
        <text :x="PAD.l - 6" :y="y(t) + 4" text-anchor="end" class="axis">{{ t }}</text>
      </g>
      <g v-for="(d, i) in days" :key="d.date">
        <rect v-if="hover === i" :x="PAD.l + slot * i" :y="PAD.t" :width="slot" :height="innerH" class="hl" />
        <rect :x="cx(i) - barW - 0.5" :y="y(d.incoming)" :width="barW" :height="Math.max(0, PAD.t + innerH - y(d.incoming))" rx="2" class="in" />
        <rect :x="cx(i) + 0.5" :y="y(d.outgoing)" :width="barW" :height="Math.max(0, PAD.t + innerH - y(d.outgoing))" rx="2" class="out" />
        <rect :x="PAD.l + slot * i" :y="PAD.t" :width="slot" :height="innerH" fill="transparent" @mouseenter="hover = i" @touchstart.passive="hover = i" />
        <text v-if="i % labelEvery === 0" :x="cx(i)" :y="H - 6" text-anchor="middle" class="axis">{{ d.date.slice(5) }}</text>
      </g>
    </svg>
  </div>
</template>

<style scoped>
.wrap {
  width: 100%;
}
svg {
  width: 100%;
  height: auto;
  display: block;
}
.grid {
  stroke: var(--border);
  stroke-width: 1;
}
.axis {
  fill: var(--text-3);
  font-size: 11px;
}
.in {
  fill: var(--accent);
  background: var(--accent);
}
.out {
  fill: var(--ok);
  background: var(--ok);
}
.hl {
  fill: var(--accent-soft);
}
.readout {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px 14px;
  min-height: 22px;
  margin-bottom: 4px;
  font-size: 13px;
}
.dot {
  display: inline-block;
  width: 9px;
  height: 9px;
  margin-right: 5px;
  border-radius: 3px;
}
.dot.in {
  background: var(--accent);
}
.dot.out {
  background: var(--ok);
}
.dot.code {
  background: var(--warn);
}
</style>
