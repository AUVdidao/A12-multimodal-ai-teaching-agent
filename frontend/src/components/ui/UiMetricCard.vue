<template>
  <component
    :is="clickable ? 'button' : 'article'"
    :type="clickable ? 'button' : undefined"
    :class="['ui-metric-card', `ui-metric-card--${variant}`, { 'is-clickable': clickable }]"
    :aria-label="clickable ? `查看${label}` : undefined"
    @click="emit('click', $event)"
  >
    <div :class="['ui-metric-card__icon', tone]">
      <slot name="icon">
        <A12AssetIcon v-if="icon" :name="icon" :size="40" />
        <span v-else>{{ fallbackIcon }}</span>
      </slot>
    </div>
    <div class="ui-metric-card__content">
      <small>{{ label }}</small>
      <strong>{{ value }}</strong>
      <p>{{ note }}</p>
    </div>
    <el-icon v-if="variant === 'shortcut'" class="ui-metric-card__arrow" aria-hidden="true">
      <ArrowRight />
    </el-icon>
  </component>
</template>

<script setup lang="ts">
import A12AssetIcon, { type A12AssetIconName } from '@/components/ui/A12AssetIcon.vue';
import { ArrowRight } from '@element-plus/icons-vue';

const emit = defineEmits<{
  click: [event: MouseEvent];
}>();

withDefaults(
  defineProps<{
    label: string;
    value: string | number;
    note?: string;
    tone?: 'purple' | 'green' | 'orange' | 'blue' | 'red';
    fallbackIcon?: string;
    icon?: A12AssetIconName;
    variant?: 'default' | 'shortcut';
    clickable?: boolean;
  }>(),
  {
    note: '',
    tone: 'purple',
    fallbackIcon: '·',
    variant: 'default',
    clickable: false,
  },
);
</script>

<style scoped>
.ui-metric-card {
  display: grid;
  grid-template-columns: 52px minmax(0, 1fr);
  align-items: center;
  gap: 16px;
  min-height: 126px;
  padding: 18px;
  border: 1px solid var(--ui-border);
  border-radius: 12px;
  background: #fff;
  box-shadow: var(--shadow-panel);
}

button.ui-metric-card {
  width: 100%;
  color: inherit;
  font: inherit;
  text-align: left;
  cursor: pointer;
}

.ui-metric-card__icon {
  display: grid;
  width: 52px;
  height: 52px;
  place-items: center;
  border-radius: 14px;
  font-size: 22px;
  font-weight: 800;
}

.ui-metric-card small {
  color: var(--ui-muted);
  font-weight: 700;
}

.ui-metric-card strong {
  display: block;
  margin-top: 7px;
  color: var(--ui-text);
  font-size: 29px;
  line-height: 1;
}

.ui-metric-card p {
  margin: 8px 0 0;
  color: var(--ui-muted);
  font-size: 13px;
}

.ui-metric-card--shortcut {
  grid-template-columns: 48px minmax(0, 1fr) 18px;
  gap: 16px;
  height: 126px;
  min-height: 126px;
  padding: 18px 19px;
  border-color: #e3e7f0;
  border-radius: 12px;
  box-shadow: 0 8px 24px rgba(28, 34, 80, 0.06);
  transition:
    border-color 160ms ease,
    box-shadow 160ms ease,
    transform 160ms ease;
}

.ui-metric-card--shortcut .ui-metric-card__icon {
  width: 48px;
  height: 48px;
  border-radius: 12px;
}

.ui-metric-card--shortcut small {
  display: block;
  color: #2d344b;
  font-size: 14px;
  line-height: 20px;
}

.ui-metric-card--shortcut strong {
  margin-top: 4px;
  color: #111827;
  font-size: 28px;
  line-height: 30px;
}

.ui-metric-card--shortcut p {
  overflow: hidden;
  margin-top: 7px;
  color: #707895;
  line-height: 18px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ui-metric-card__arrow {
  align-self: start;
  margin-top: 4px;
  color: #37415b;
  font-size: 17px;
}

.ui-metric-card--shortcut.is-clickable:hover {
  border-color: #d5daea;
  box-shadow: 0 10px 28px rgba(28, 34, 80, 0.1);
  transform: translateY(-1px);
}

.ui-metric-card--shortcut.is-clickable:focus-visible {
  outline: 3px solid rgba(91, 69, 246, 0.2);
  outline-offset: 2px;
}

.purple {
  background: #f2eeff;
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

.red {
  background: #fff0f1;
  color: #df4b55;
}
</style>
