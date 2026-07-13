<template>
  <span class="ui-brand-mark" :style="{ width: `${size}px`, height: `${size}px` }" aria-hidden="true">
    <img :src="logoMark" alt="" />
  </span>
</template>

<script setup lang="ts">
import logoMark from '@/assets/a12-ui/logo-mark.svg';

withDefaults(
  defineProps<{
    size?: number;
  }>(),
  {
    size: 44,
  },
);
</script>

<style scoped>
.ui-brand-mark {
  display: inline-grid;
  place-items: center;
  filter: drop-shadow(0 3px 8px rgba(91, 69, 246, 0.18));
}

.ui-brand-mark img {
  width: 100%;
  height: 100%;
}
</style>
