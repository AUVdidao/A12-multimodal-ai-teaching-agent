<template>
  <nav class="m1-progress" aria-label="M1 需求澄清流程">
    <ol>
      <li v-for="(step, index) in steps" :key="step.label" :class="stepClass(index)">
        <button
          type="button"
          :disabled="index > unlockedStep"
          :aria-current="index === currentStep ? 'step' : undefined"
          :title="index > unlockedStep ? '完成前序步骤后解锁' : step.label"
          @click="openStep(index)"
        >
          <span class="m1-progress__marker">
            <el-icon v-if="index <= completedThrough"><Check /></el-icon>
            <span v-else>{{ index + 1 }}</span>
          </span>
          <span class="m1-progress__text">
            <strong>{{ step.label }}</strong>
            <small>{{ step.caption }}</small>
          </span>
        </button>
      </li>
    </ol>
  </nav>
</template>

<script setup lang="ts">
import { Check } from '@element-plus/icons-vue';
import { useRouter } from 'vue-router';

const props = withDefaults(defineProps<{
  currentStep: number;
  unlockedStep: number;
  completedThrough?: number;
  projectId?: number | string | null;
}>(), { completedThrough: -1, projectId: null });

const router = useRouter();
const steps = [
  { label: '创建项目', caption: '课程基础' },
  { label: '生成模式', caption: '策略选择' },
  { label: '教学需求', caption: '教师输入' },
  { label: 'AI 澄清', caption: '补齐信息' },
  { label: '摘要确认', caption: '确认版本' },
];

function stepClass(index: number) {
  return {
    'is-current': index === props.currentStep,
    'is-completed': index <= props.completedThrough,
    'is-locked': index > props.unlockedStep,
  };
}

function openStep(index: number) {
  if (index > props.unlockedStep) return;
  if (index === 0 || !props.projectId) {
    router.push(index === 0 ? '/projects' : '/projects/new');
    return;
  }
  if (index === 1) router.push(`/projects/${props.projectId}/mode`);
  if (index === 2) router.push(`/projects/${props.projectId}/requirements`);
  if (index === 3) router.push({ path: `/projects/${props.projectId}/requirements`, hash: '#clarification' });
  if (index === 4) router.push(`/projects/${props.projectId}/requirement-summary`);
}
</script>

<style scoped>
.m1-progress {
  margin-bottom: 14px;
  padding: 4px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  background: var(--color-surface);
  box-shadow: none;
}

ol {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 4px;
  margin: 0;
  padding: 0;
  list-style: none;
}

li {
  position: relative;
  min-width: 0;
}

li:not(:last-child)::after {
  position: absolute;
  top: 20px;
  right: -4px;
  z-index: 1;
  width: 8px;
  height: 1px;
  background: var(--color-border-strong);
  content: '';
}

button {
  display: flex;
  align-items: center;
  width: 100%;
  min-height: 38px;
  gap: 9px;
  padding: 7px 9px;
  border: 0;
  border-radius: var(--radius-md);
  background: transparent;
  color: var(--color-text-secondary);
  cursor: pointer;
  text-align: left;
  transition: background-color var(--transition-fast), color var(--transition-fast);
}

button:not(:disabled):hover {
  background: var(--color-primary-soft);
  color: var(--color-primary);
}

button:disabled {
  color: #9aa4b2;
  cursor: not-allowed;
}

.is-current button {
  background: var(--color-primary-soft);
  color: var(--color-primary);
}

.is-completed button {
  color: var(--color-success);
}

.m1-progress__marker {
  display: grid;
  flex: 0 0 22px;
  width: 22px;
  height: 22px;
  place-items: center;
  border: 1px solid currentColor;
  border-radius: 50%;
  font-size: 12px;
  font-weight: 700;
}

.m1-progress__text {
  display: block;
  min-width: 0;
}

.m1-progress__text strong,
.m1-progress__text small {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.m1-progress__text strong {
  font-size: 13px;
}

.m1-progress__text small { display: none; }

@media (max-width: 760px) {
  .m1-progress__text small {
    display: none;
  }

  button {
    justify-content: center;
    padding: 7px 4px;
  }

  .m1-progress__text strong {
    font-size: 11px;
    text-align: center;
    white-space: normal;
  }
}

@media (max-width: 480px) {
  ol {
    min-width: 0;
  }

  button {
    min-height: 64px;
    flex-direction: column;
    gap: 4px;
  }

  .m1-progress__marker {
    flex-basis: 23px;
    width: 23px;
    height: 23px;
  }

  .m1-progress__text strong {
    font-size: 9px;
    line-height: 1.25;
  }
}
</style>
