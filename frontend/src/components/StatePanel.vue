<template>
  <div :class="['state-panel', `state-panel--${type}`]" role="status">
    <el-icon class="state-panel__icon"><component :is="icon" /></el-icon>
    <div class="state-panel__content">
      <strong>{{ title }}</strong>
      <p v-if="description">{{ description }}</p>
      <div v-if="$slots.action" class="state-panel__action"><slot name="action" /></div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { CircleCheck, InfoFilled, Loading, WarningFilled } from '@element-plus/icons-vue';
import { computed } from 'vue';

const props = withDefaults(defineProps<{
  type?: 'empty' | 'error' | 'success' | 'loading' | 'info';
  title: string;
  description?: string;
}>(), { type: 'info' });

const icon = computed(() => ({
  empty: InfoFilled,
  error: WarningFilled,
  success: CircleCheck,
  loading: Loading,
  info: InfoFilled,
}[props.type]));
</script>

<style scoped>
.state-panel {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 18px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: var(--color-surface-subtle);
}

.state-panel--success {
  border-color: #bce8db;
  background: var(--color-success-soft);
}

.state-panel--error {
  border-color: #f0c4c8;
  background: var(--color-danger-soft);
}

.state-panel__icon {
  flex: 0 0 auto;
  margin-top: 2px;
  color: var(--color-primary);
  font-size: 20px;
}

.state-panel--success .state-panel__icon {
  color: var(--color-success);
}

.state-panel--error .state-panel__icon {
  color: var(--color-danger);
}

.state-panel--loading .state-panel__icon {
  animation: state-spin 1s linear infinite;
}

.state-panel__content {
  min-width: 0;
}

strong,
p {
  display: block;
  margin: 0;
}

strong {
  color: var(--color-text);
}

p {
  margin-top: 5px;
  color: var(--color-text-secondary);
  font-size: 13px;
  line-height: 1.6;
}

.state-panel__action {
  margin-top: 12px;
}

@keyframes state-spin {
  to { transform: rotate(360deg); }
}
</style>
