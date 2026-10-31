<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{ grid: number[][] }>()
const DAYS = ['周一', '周二', '周三', '周四', '周五', '周六', '周日']
const max = computed(() => Math.max(1, ...props.grid.flat()))
function bg(n: number): string {
  if (n <= 0) return 'var(--panel-2)'
  // 非线性：少量数据也能看出颜色，避免大数值把其它格子压成一片白
  const pct = 18 + Math.round(Math.sqrt(n / max.value) * 82)
  return `color-mix(in srgb, var(--accent) ${pct}%, transparent)`
}
</script>

<template>
  <div class="scroll">
    <div class="hm" role="img" aria-label="按星期和小时统计的收信热力图">
      <div class="corner" />
      <div v-for="h in 24" :key="'h' + h" class="hl">{{ (h - 1) % 3 === 0 ? String(h - 1).padStart(2, '0') : '' }}</div>
      <template v-for="(row, d) in grid" :key="d">
        <div class="dl">{{ DAYS[d] }}</div>
        <div v-for="(n, h) in row" :key="h" class="cell" :style="{ background: bg(n) }" :title="`${DAYS[d]} ${String(h).padStart(2, '0')}:00  ${n} 条`" />
      </template>
    </div>
  </div>
</template>

<style scoped>
.scroll {
  overflow-x: auto;
  padding-bottom: 4px;
}
.hm {
  display: grid;
  grid-template-columns: 34px repeat(24, minmax(14px, 1fr));
  gap: 2px;
  min-width: 460px;
}
.cell {
  aspect-ratio: 1;
  border-radius: 3px;
  min-height: 14px;
}
.hl,
.dl {
  font-size: 11px;
  color: var(--text-3);
  line-height: 14px;
}
.hl {
  text-align: left;
  white-space: nowrap;
  overflow: visible;
}
.dl {
  display: flex;
  align-items: center;
}
</style>
