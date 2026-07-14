<template>
  <StatePanel v-if="!questions.length" type="empty" title="互动成果没有可预览题目" description="当前成果内容为空。" />
  <div v-else class="interaction-preview">
    <section v-for="(question, index) in questions" :key="questionKey(index)" class="question-card">
      <header>
        <span>第 {{ index + 1 }} 题</span>
        <UiStatusPill v-if="question.type" :label="question.type" tone="blue" />
      </header>
      <h3>{{ question.question }}</h3>

      <el-radio-group
        v-if="question.options.length"
        v-model="selectedAnswers[questionKey(index)]"
        class="question-options"
        :disabled="revealed[questionKey(index)]"
        :aria-label="`第 ${index + 1} 题选项`"
      >
        <el-radio v-for="option in question.options" :key="option.value" :value="option.value" border>
          <strong>{{ option.label }}</strong>
          <span>{{ option.text }}</span>
        </el-radio>
      </el-radio-group>

      <div class="question-actions">
        <el-button
          type="primary"
          :plain="Boolean(revealed[questionKey(index)])"
          :icon="revealed[questionKey(index)] ? RefreshLeft : Select"
          :disabled="question.options.length > 0 && !selectedAnswers[questionKey(index)]"
          @click="toggleReveal(index)"
        >
          {{ revealed[questionKey(index)] ? '重新作答' : question.options.length ? '提交答案' : '查看解析' }}
        </el-button>
      </div>

      <div
        v-if="revealed[questionKey(index)]"
        :class="['question-feedback', feedbackTone(question, index)]"
        role="status"
      >
        <div class="question-feedback__title">
          <el-icon><component :is="feedbackIcon(question, index)" /></el-icon>
          <strong>{{ feedbackTitle(question, index) }}</strong>
        </div>
        <p v-if="question.answer"><b>参考答案：</b>{{ question.answer }}</p>
        <p v-if="question.explanation"><b>解析：</b>{{ question.explanation }}</p>
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
import type { Artifact } from '@/api/generation';
import StatePanel from '@/components/StatePanel.vue';
import UiStatusPill from '@/components/ui/UiStatusPill.vue';
import { CircleCheck, InfoFilled, RefreshLeft, Select, WarningFilled } from '@element-plus/icons-vue';
import { normalizeInteractionQuestions, type InteractionQuestionView } from './artifactContent';
import { computed, reactive, watch } from 'vue';

const props = defineProps<{ artifact: Artifact }>();
const selectedAnswers = reactive<Record<string, string>>({});
const revealed = reactive<Record<string, boolean>>({});
const questions = computed(() => normalizeInteractionQuestions(props.artifact.content));

function questionKey(index: number) {
  return `${props.artifact.id}-${index}`;
}

function selectedCorrectness(question: InteractionQuestionView, index: number) {
  const selected = selectedAnswers[questionKey(index)];
  if (!selected || !question.options.length) return undefined;
  const selectedIndex = question.options.findIndex((option) => option.value === selected);
  const selectedOption = question.options[selectedIndex];
  if (question.correctIndex !== undefined) return selectedIndex === question.correctIndex;
  if (typeof selectedOption?.correct === 'boolean') return selectedOption.correct;

  const explicitlyCorrect = question.options.findIndex((option) => option.correct === true);
  if (explicitlyCorrect >= 0) return selectedIndex === explicitlyCorrect;
  if (!question.answer) return undefined;

  const normalizedAnswer = question.answer.trim().toLowerCase();
  const numericAnswer = Number(normalizedAnswer);
  if (Number.isInteger(numericAnswer) && numericAnswer >= 0 && numericAnswer <= question.options.length) {
    const expectedIndex = numericAnswer === 0 ? 0 : numericAnswer - 1;
    return selectedIndex === expectedIndex;
  }

  const optionLabel = selectedOption.label.trim().toLowerCase();
  const optionText = selectedOption.text.trim().toLowerCase();
  const optionValue = selectedOption.value.trim().toLowerCase();
  return normalizedAnswer === optionLabel
    || normalizedAnswer === optionText
    || normalizedAnswer === optionValue
    || normalizedAnswer.startsWith(`${optionLabel}.`)
    || normalizedAnswer.startsWith(`${optionLabel}、`);
}

function feedbackTitle(question: InteractionQuestionView, index: number) {
  const result = selectedCorrectness(question, index);
  if (result === true) return '回答正确';
  if (result === false) return '回答有误';
  return '答案已提交';
}

function feedbackTone(question: InteractionQuestionView, index: number) {
  const result = selectedCorrectness(question, index);
  return result === true ? 'is-correct' : result === false ? 'is-incorrect' : 'is-info';
}

function feedbackIcon(question: InteractionQuestionView, index: number) {
  const result = selectedCorrectness(question, index);
  return result === true ? CircleCheck : result === false ? WarningFilled : InfoFilled;
}

function toggleReveal(index: number) {
  const key = questionKey(index);
  if (revealed[key]) {
    revealed[key] = false;
    selectedAnswers[key] = '';
    return;
  }
  revealed[key] = true;
}

function resetAnswers() {
  Object.keys(selectedAnswers).forEach((key) => { delete selectedAnswers[key]; });
  Object.keys(revealed).forEach((key) => { delete revealed[key]; });
}

watch(() => props.artifact.id, resetAnswers);
</script>

<style scoped>
.interaction-preview {
  display: grid;
  gap: 14px;
}

.question-card {
  min-width: 0;
  padding: 18px;
  border: 1px solid var(--ui-border);
  border-radius: 8px;
  background: #fff;
}

.question-card header,
.question-feedback__title,
.question-actions {
  display: flex;
  align-items: center;
}

.question-card header {
  justify-content: space-between;
  gap: 12px;
}

.question-card header > span {
  color: var(--ui-primary);
  font-size: 12px;
  font-weight: 700;
}

.question-card h3 {
  margin: 10px 0 16px;
  color: #202a43;
  font-size: 16px;
  line-height: 1.6;
  overflow-wrap: anywhere;
}

.question-options {
  display: grid;
  width: 100%;
  gap: 9px;
}

.question-options :deep(.el-radio) {
  display: flex;
  width: 100%;
  min-width: 0;
  height: auto;
  min-height: 44px;
  padding: 9px 12px;
  margin: 0;
  white-space: normal;
}

.question-options :deep(.el-radio__input) {
  flex: 0 0 auto;
}

.question-options :deep(.el-radio__label) {
  display: flex;
  min-width: 0;
  align-items: flex-start;
  gap: 8px;
  padding-left: 9px;
  line-height: 1.55;
  white-space: normal;
}

.question-options :deep(.el-radio__label strong) {
  flex: 0 0 auto;
  color: var(--ui-primary);
}

.question-options :deep(.el-radio__label span) {
  min-width: 0;
  overflow-wrap: anywhere;
}

.question-actions {
  justify-content: flex-end;
  margin-top: 14px;
}

.question-feedback {
  padding: 13px 14px;
  margin-top: 14px;
  border: 1px solid transparent;
  border-radius: 6px;
}

.question-feedback.is-correct {
  border-color: #bce8d1;
  background: #e9f8f0;
  color: #168d52;
}

.question-feedback.is-incorrect {
  border-color: #f0c4c8;
  background: #fff0f1;
  color: #c43d4b;
}

.question-feedback.is-info {
  border-color: #cbdcfb;
  background: #edf4ff;
  color: #2f70e8;
}

.question-feedback__title {
  gap: 7px;
}

.question-feedback p {
  margin: 8px 0 0;
  color: #46536d;
  font-size: 13px;
  line-height: 1.65;
  overflow-wrap: anywhere;
  white-space: pre-wrap;
}

@media (max-width: 560px) {
  .question-card {
    padding: 15px;
  }

  .question-actions,
  .question-actions .el-button {
    width: 100%;
  }
}
</style>
