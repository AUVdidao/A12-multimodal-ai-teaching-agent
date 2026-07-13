<template>
  <span :class="['ui-status-pill', tone]">
    <span v-if="dot" class="ui-status-pill__dot" />
    {{ label }}
  </span>
</template>

<script setup lang="ts">
withDefaults(
  defineProps<{
    label: string;
    tone?: 'purple' | 'green' | 'orange' | 'blue' | 'gray' | 'red';
    dot?: boolean;
  }>(),
  {
    tone: 'purple',
    dot: false,
  },
);
</script>

<style scoped>
.ui-status-pill {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 7px;
  min-height: 24px;
  padding: 0 10px;
  border-radius: 8px;
  font-size: 12px;
  font-weight: 700;
  white-space: nowrap;
}

.ui-status-pill__dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: currentColor;
}

.purple {
  background: #f1edff;
  color: #5b45f6;
}

.green {
  background: #e9f8f0;
  color: #23a663;
}

.orange {
  background: #fff3e4;
  color: #df7d16;
}

.blue {
  background: #edf4ff;
  color: #2f70e8;
}

.gray {
  background: #f1f4f8;
  color: #65738a;
}

.red {
  background: #fff0f1;
  color: #df4b55;
}
</style>
