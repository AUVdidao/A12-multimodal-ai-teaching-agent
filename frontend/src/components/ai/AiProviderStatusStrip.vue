<template>
  <section
    :class="['ai-provider-strip', `is-${presentation.tone}`, { 'is-compact': compact }]"
    role="status"
    aria-live="polite"
  >
    <span class="ai-provider-strip__icon">
      <el-icon><component :is="statusIcon" /></el-icon>
    </span>
    <div class="ai-provider-strip__copy">
      <strong>{{ presentation.label }}</strong>
      <p>{{ presentation.summary }}</p>
    </div>
    <div class="ai-provider-strip__meta">
      <UiStatusPill :label="`目标：${presentation.requestedLabel}`" :tone="pillTone" />
      <UiStatusPill
        v-if="status?.fallbackToMock"
        label="Mock 回退已开启"
        :tone="presentation.fallbackActive ? 'orange' : 'gray'"
      />
      <el-tooltip v-if="presentation.diagnostic" :content="presentation.diagnostic" placement="top" :show-after="250">
        <el-button text circle :icon="InfoFilled" aria-label="查看 AI Provider 诊断信息" />
      </el-tooltip>
      <el-tooltip content="刷新 AI Provider 状态" placement="top">
        <el-button text circle :icon="Refresh" :loading="loading" aria-label="刷新 AI Provider 状态" @click="$emit('refresh')" />
      </el-tooltip>
    </div>
  </section>
</template>

<script setup lang="ts">
import type { AiGatewayStatus } from '@/api/aiAssistant';
import UiStatusPill from '@/components/ui/UiStatusPill.vue';
import { describeAiProvider } from '@/utils/aiProvider';
import { CircleCheck, InfoFilled, MagicStick, Refresh, WarningFilled } from '@element-plus/icons-vue';
import { computed } from 'vue';

const props = withDefaults(defineProps<{
  status?: AiGatewayStatus;
  loading?: boolean;
  error?: string;
  compact?: boolean;
}>(), {
  loading: false,
  error: '',
  compact: false,
});

defineEmits<{ refresh: [] }>();

const presentation = computed(() => describeAiProvider(props.status, props.loading, props.error));
const statusIcon = computed(() => {
  if (presentation.value.tone === 'danger') return WarningFilled;
  if (presentation.value.tone === 'warning') return MagicStick;
  return CircleCheck;
});
const pillTone = computed(() => {
  if (presentation.value.tone === 'danger') return 'red' as const;
  if (presentation.value.tone === 'warning') return 'orange' as const;
  if (presentation.value.tone === 'success') return 'green' as const;
  return 'blue' as const;
});
</script>

<style scoped>
.ai-provider-strip {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 11px;
  padding: 12px 14px;
  margin: 12px 0 16px;
  border: 1px solid #bcddec;
  border-radius: 8px;
  background: #f2f8ff;
}

.ai-provider-strip.is-success {
  border-color: #bce8d2;
  background: #f0faf5;
}

.ai-provider-strip.is-warning {
  border-color: #f1d39e;
  background: #fff8ed;
}

.ai-provider-strip.is-danger {
  border-color: #efc4c8;
  background: #fff4f5;
}

.ai-provider-strip__icon {
  display: grid;
  width: 34px;
  height: 34px;
  flex: 0 0 34px;
  place-items: center;
  border-radius: 8px;
  background: #fff;
  color: var(--ui-info);
  font-size: 18px;
}

.is-success .ai-provider-strip__icon { color: var(--ui-success); }
.is-warning .ai-provider-strip__icon { color: var(--ui-warning); }
.is-danger .ai-provider-strip__icon { color: var(--ui-danger); }

.ai-provider-strip__copy {
  min-width: 0;
  flex: 1;
}

.ai-provider-strip__copy strong,
.ai-provider-strip__copy p {
  overflow-wrap: anywhere;
}

.ai-provider-strip__copy strong {
  color: var(--ui-text);
  font-size: 13px;
}

.ai-provider-strip__copy p {
  margin: 3px 0 0;
  color: var(--ui-muted);
  font-size: 12px;
  line-height: 1.5;
}

.ai-provider-strip__meta {
  display: flex;
  flex: 0 0 auto;
  align-items: center;
  justify-content: flex-end;
  gap: 7px;
}

.ai-provider-strip.is-compact {
  padding: 9px 11px;
}

.ai-provider-strip.is-compact .ai-provider-strip__copy p {
  display: none;
}

@media (max-width: 760px) {
  .ai-provider-strip {
    align-items: flex-start;
    flex-wrap: wrap;
  }

  .ai-provider-strip__meta {
    width: 100%;
    justify-content: flex-start;
    padding-left: 45px;
    flex-wrap: wrap;
  }
}
</style>
