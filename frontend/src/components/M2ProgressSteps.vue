<template>
  <nav class="m2-progress" aria-label="M2 资料增强流程">
    <ol>
      <li v-for="(step, index) in steps" :key="step.label" :class="stepClass(index)">
        <button type="button" :disabled="index > unlockedStep" :aria-current="index === currentStep ? 'step' : undefined" :title="index > unlockedStep ? '完成前序步骤后解锁' : step.label" @click="openStep(index)">
          <span class="m2-progress__marker"><el-icon v-if="completed[index]"><Check /></el-icon><span v-else>{{ index + 1 }}</span></span>
          <span class="m2-progress__text"><strong>{{ step.label }}</strong><small>{{ step.caption }}</small></span>
        </button>
      </li>
    </ol>
  </nav>
</template>

<script setup lang="ts">
import { Check } from '@element-plus/icons-vue';
import { computed } from 'vue';
import { useRouter } from 'vue-router';

const props = defineProps<{ currentStep: number; projectId: number; hasMaterials: boolean; hasUsages: boolean; hasParsed: boolean; hasKnowledge: boolean; intentConfirmed: boolean }>();
const router = useRouter();
const steps = [
  { label: '上传资料', caption: '真实文件' },
  { label: '标记用途', caption: '增强方向' },
  { label: '解析摘要', caption: '原型结果' },
  { label: '知识检索', caption: '本地检索' },
  { label: '意图确认', caption: '生成前确认' },
];
const completed = computed(() => [props.hasMaterials, props.hasUsages, props.hasParsed, props.hasKnowledge, props.intentConfirmed]);
const unlockedStep = computed(() => props.hasKnowledge ? 4 : props.hasParsed ? 3 : props.hasUsages ? 2 : props.hasMaterials ? 1 : 0);

function stepClass(index: number) {
  return { 'is-current': index === props.currentStep, 'is-completed': completed.value[index], 'is-locked': index > unlockedStep.value };
}
function openStep(index: number) {
  if (index > unlockedStep.value) return;
  if (index <= 2) router.push(`/projects/${props.projectId}/materials`);
  if (index === 3) router.push(`/projects/${props.projectId}/knowledge`);
  if (index === 4) router.push(`/projects/${props.projectId}/teaching-intent`);
}
</script>

<style scoped>
.m2-progress { margin-bottom: 22px; padding: 8px; border: 1px solid var(--color-border); border-radius: var(--radius-lg); background: var(--color-surface); box-shadow: var(--shadow-card); }
ol { display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); gap: 4px; margin: 0; padding: 0; list-style: none; }
li { position: relative; min-width: 0; }
li:not(:last-child)::after { position: absolute; top: 20px; right: -4px; width: 8px; height: 1px; background: var(--color-border-strong); content: ''; }
button { display: flex; align-items: center; width: 100%; min-height: 48px; gap: 9px; padding: 7px 9px; border: 0; border-radius: var(--radius-md); background: transparent; color: var(--color-text-secondary); cursor: pointer; text-align: left; }
button:not(:disabled):hover, .is-current button { background: var(--color-primary-soft); color: var(--color-primary); }
button:disabled { color: #9aa4b2; cursor: not-allowed; }
.is-completed button { color: var(--color-success); }
.m2-progress__marker { display: grid; flex: 0 0 26px; width: 26px; height: 26px; place-items: center; border: 1px solid currentColor; border-radius: 50%; font-size: 12px; font-weight: 700; }
.m2-progress__text { min-width: 0; }
.m2-progress__text strong, .m2-progress__text small { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.m2-progress__text strong { font-size: 13px; }
.m2-progress__text small { margin-top: 2px; color: var(--color-text-muted); font-size: 10px; }
@media (max-width: 760px) { .m2-progress__text small { display: none; } button { justify-content: center; padding: 7px 4px; } .m2-progress__text strong { font-size: 11px; text-align: center; white-space: normal; } }
@media (max-width: 480px) { button { min-height: 64px; flex-direction: column; gap: 4px; } .m2-progress__marker { flex-basis: 23px; width: 23px; height: 23px; } .m2-progress__text strong { font-size: 9px; line-height: 1.25; } }
</style>
