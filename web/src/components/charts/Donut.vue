<script setup lang="ts">
import { computed } from 'vue'

export interface Slice {
  key: string
  label: string
  value: number
  color: string
}
const props = defineProps<{ slices: Slice[]; centerLabel?: string }>()

const R = 54
const C = 2 * Math.PI * R
const total = computed(() => props.slices.reduce((a, s) => a + s.value, 0))
const arcs = computed(() => {
  let off = 0
  return props.slices
    .filter((s) => s.value > 0)
    .map((s) => {
      const len = (s.value / total.value) * C
      const a = { ...s, dash: `${len} ${C - len}`, offset: -off }
      off += len
      return a
    })
})
</script>

<template>
  <div class="donut">
    <svg viewBox="0 0 140 140" role="img" :aria-label="centerLabel || '占比'">
      <circle cx="70" cy="70" :r="R" fill="none" stroke="var(--panel-2)" stroke-width="18" />
      <circle
        v-for="a in arcs"
        :key="a.key"
        cx="70"
        cy="70"
        :r="R"
        fill="none"
        :stroke="a.color"
        stroke-width="18"
        :stroke-dasharray="a.dash"
        :stroke-dashoffset="a.offset"
        transform="rotate(-90 70 70)"
      />
      <text x="70" y="68" text-anchor="middle" class="num">{{ total }}</text>
      <text x="70" y="86" text-anchor="middle" class="cap">{{ centerLabel || '合计' }}</text>
    </svg>
    <ul class="legend">
      <li v-for="s in slices" :key="s.key" :class="{ zero: s.value === 0 }">
        <i :style="{ background: s.color }" />
        <span class="l">{{ s.label }}</span>
        <span class="v">{{ s.value }}</span>
        <span class="p muted">{{ total ? Math.round((s.value / total) * 100) : 0 }}%</span>
      </li>
    </ul>
  </div>
</template>

<style scoped>
.donut {
  display: flex;
  align-items: center;
  gap: 18px;
  flex-wrap: wrap;
}
svg {
  width: 140px;
  height: 140px;
  flex: none;
}
.num {
  fill: var(--text);
  font-size: 22px;
  font-weight: 700;
}
.cap {
  fill: var(--text-3);
  font-size: 11px;
}
.legend {
  flex: 1;
  min-width: 150px;
  margin: 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.legend li {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
}
.legend li.zero {
  opacity: 0.45;
}
.legend i {
  width: 10px;
  height: 10px;
  border-radius: 3px;
  flex: none;
}
.l {
  flex: 1;
}
.v {
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}
.p {
  width: 38px;
  text-align: right;
  font-size: 12px;
}
</style>
