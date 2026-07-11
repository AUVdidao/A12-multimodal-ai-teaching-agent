<template>
  <span :class="['status-badge', `status-badge--${tone}`]">
    <span class="status-badge__dot" aria-hidden="true" />
    {{ label }}
  </span>
</template>

<script setup lang="ts">
import { computed } from 'vue';

const props = defineProps<{
  status: string;
  label?: string;
}>();

const labels: Record<string, string> = {
  CREATED: '待完善需求',
  REQUIREMENT_CONFIRMED: '需求已确认',
  MATERIAL_READY: '资料已就绪',
  INTENT_CONFIRMED: '教学意图已确认',
  GENERATED: '内容已生成',
  FINALIZED: '已定稿',
  DRAFT: '待确认',
  CONFIRMED: '已确认',
  UPLOADED: '已上传',
  NOT_STARTED: '等待解析',
  PROCESSING: '解析中',
  SUCCEEDED: '解析完成',
  FAILED: '处理失败',
  UP: '服务正常',
};

const label = computed(() => props.label || labels[props.status] || props.status);
const tone = computed(() => {
  if (['CONFIRMED', 'REQUIREMENT_CONFIRMED', 'INTENT_CONFIRMED', 'FINALIZED', 'UP', 'SUCCEEDED'].includes(props.status)) return 'success';
  if (props.status === 'FAILED') return 'danger';
  if (['DRAFT', 'CREATED'].includes(props.status)) return 'warning';
  return 'neutral';
});
</script>

<style scoped>
.status-badge {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  min-height: 28px;
  padding: 4px 10px;
  border: 1px solid var(--color-border);
  border-radius: 999px;
  background: var(--color-surface-subtle);
  color: var(--color-text-secondary);
  font-size: 12px;
  font-weight: 700;
  white-space: nowrap;
}

.status-badge__dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: currentColor;
}

.status-badge--success {
  border-color: #bce8db;
  background: var(--color-success-soft);
  color: var(--color-success);
}

.status-badge--warning {
  border-color: #efd39a;
  background: var(--color-warning-soft);
  color: var(--color-warning);
}

.status-badge--danger {
  border-color: #f0c4c8;
  background: var(--color-danger-soft);
  color: var(--color-danger);
}
</style>
