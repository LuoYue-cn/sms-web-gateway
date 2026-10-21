<script setup lang="ts">
import { computed } from 'vue'
import { avatarColor, avatarText } from '@/utils/sms'

const props = withDefaults(defineProps<{ name: string; size?: number }>(), { size: 40 })
const style = computed(() => ({
  width: `${props.size}px`,
  height: `${props.size}px`,
  fontSize: `${Math.round(props.size * 0.42)}px`,
  background: avatarColor(props.name),
}))
</script>

<template>
  <span class="avatar" :style="style">{{ avatarText(name) }}</span>
</template>

<style scoped>
.avatar {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex: none;
  border-radius: 50%;
  color: #fff;
  font-weight: 600;
  user-select: none;
}
</style>
