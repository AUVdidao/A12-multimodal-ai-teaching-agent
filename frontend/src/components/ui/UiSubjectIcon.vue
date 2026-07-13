<template>
  <span :class="['ui-subject-icon', tone]" aria-hidden="true">
    <A12AssetIcon :name="icon" :size="30" />
  </span>
</template>

<script setup lang="ts">
import A12AssetIcon, { type A12AssetIconName } from '@/components/ui/A12AssetIcon.vue';

defineProps<{
  icon: A12AssetIconName;
  tone: 'purple' | 'green' | 'orange' | 'blue' | 'red';
}>();
</script>

<style scoped>
.ui-subject-icon {
  display: inline-flex;
  width: 40px;
  height: 40px;
  flex: 0 0 auto;
  align-items: center;
  justify-content: center;
  border-radius: 9px;
  line-height: 0;
  vertical-align: middle;
}

.ui-subject-icon :deep(.a12-asset-icon) {
  display: block;
  transform: translateY(1px);
}

.purple {
  background: #f2eeff;
}

.green {
  background: #eaf8ef;
}

.orange {
  background: #fff4e8;
}

.blue {
  background: #edf5ff;
}

.red {
  background: #fff0f3;
}
</style>
