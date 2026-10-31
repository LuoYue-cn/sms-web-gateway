<script setup lang="ts">
import { computed } from 'vue'

export interface Bar {
  label: string
  value: number
  sub?: string
}
const props = defineProps<{ items: Bar[]; empty?: string }>()
const max = computed(() => Math.max(1, ...props.items.map((i) => i.value)))
</script>

<template>
  <ul v-if="items.length" class="bars">
    <li v-for="(b, i) in items" :key="b.label + i">
      <div class="top">
        <span class="lab" :title="b.label">{{ b.label }}</span>
        <span class="val">{{ b.value }}<small v-if="b.sub" class="muted sub">{{ b.sub }}</small></span>
      </div>
      <div class="track"><div class="fill" :style="{ width: (b.value / max) * 100 + '%' }" /></div>
    </li>
  </ul>
  <div v-else class="muted">{{ empty || '暂无数据' }}</div>
</template>

<style scoped>
.bars {
  margin: 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 9px;
}
.top {
  display: flex;
  justify-content: space-between;
  gap: 10px;
  font-size: 13px;
  margin-bottom: 3px;
}
.lab {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  min-width: 0;
}
.val {
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}
.val small {
  font-weight: 400;
}
.val .sub {
  margin-left: 8px;
  font-size: 12px;
}
.track {
  height: 6px;
  border-radius: 3px;
  background: var(--panel-2);
}
.fill {
  height: 100%;
  border-radius: 3px;
  background: var(--accent);
  min-width: 2px;
}
</style>
