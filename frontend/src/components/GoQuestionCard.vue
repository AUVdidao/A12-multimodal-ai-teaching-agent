<template>
  <section class="lf-plan-card go-question-card" data-test="go-active-question">
    <div class="go-question-card__header"><div class="lf-card-kicker">QUESTION<span v-if="questionIndex && questionTotal"> {{ questionIndex }} OF {{ questionTotal }}</span></div><span>需求确认</span></div>
    <h2>{{ question.text }}</h2>
    <fieldset v-if="question.type !== 'TEXT'" :disabled="submitting">
      <legend class="go-question-type">{{ question.type === 'SINGLE_CHOICE' ? '请选择一项' : '可选择多项' }}</legend>
      <label v-for="option in safeOptions" :key="option" class="go-question-option">
        <input v-if="question.type === 'SINGLE_CHOICE'" v-model="selectedValue" type="radio" name="go-question-choice" :value="option" />
        <input v-else v-model="selectedValues" type="checkbox" :value="option" />
        <span>{{ option }}</span>
      </label>
    </fieldset>
    <label v-else class="go-question-text-label">
      <span>请填写回答</span>
      <textarea v-model="textAnswer" rows="3" maxlength="4000" :disabled="submitting" data-test="go-question-text" />
    </label>
    <p v-if="error" class="go-inline-error" role="alert" data-test="go-question-error">{{ error }}</p>
    <button class="lf-primary-button" type="button" :disabled="submitting || !canSubmit" data-test="go-question-submit" @click="submit">
      {{ submitting ? '提交中…' : '提交回答' }}
    </button>
    <p class="go-question-boundary">提交成功表示回答和新的 AgentRun 已被服务端接受；生成结果会通过服务端事件和后续刷新显示。</p>
  </section>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { answerGoQuestion, type GoQuestion } from '@/api/go';
import { goErrorMessage } from '@/api/go';

const props = defineProps<{
  question: GoQuestion;
  questionIndex?: number;
  questionTotal?: number;
  missionId: number;
  userId?: number;
}>();
const emit = defineEmits<{ submitted: [] }>();

const selectedValue = ref('');
const selectedValues = ref<string[]>([]);
const textAnswer = ref('');
const submitting = ref(false);
const error = ref('');
let active = true;

const safeOptions = computed(() => Array.isArray(props.question.options) ? props.question.options.filter((option): option is string => typeof option === 'string' && option.trim().length > 0) : []);
const canSubmit = computed(() => {
  if (props.question.type === 'TEXT') return textAnswer.value.trim().length > 0;
  if (props.question.type === 'SINGLE_CHOICE') return safeOptions.value.includes(selectedValue.value);
  return selectedValues.value.length > 0 && selectedValues.value.every((value) => safeOptions.value.includes(value));
});

function syncAnswer() {
  const latest = props.question.latestAnswer;
  const allowed = new Set(safeOptions.value);
  selectedValues.value = (latest?.selectedValues || []).filter((value) => allowed.has(value));
  selectedValue.value = selectedValues.value[0] || '';
  textAnswer.value = latest?.textAnswer || '';
  error.value = '';
}

function contextIsCurrent(userId: number | undefined, missionId: number) {
  return active && props.userId === userId && props.missionId === missionId;
}

async function submit() {
  if (submitting.value) return;
  error.value = '';
  if (!canSubmit.value) {
    error.value = props.question.type === 'TEXT' ? '请填写回答。' : '请选择当前问题提供的选项。';
    return;
  }
  const userId = props.userId;
  const missionId = props.missionId;
  const questionId = props.question.id;
  if (userId == null || !Number.isInteger(missionId) || missionId <= 0) return;
  submitting.value = true;
  try {
    await answerGoQuestion(questionId, {
      selectedValues: props.question.type === 'TEXT' ? [] : props.question.type === 'SINGLE_CHOICE' ? [selectedValue.value] : [...selectedValues.value],
      textAnswer: props.question.type === 'TEXT' ? textAnswer.value.trim() : '',
    });
    if (contextIsCurrent(userId, missionId) && props.question.id === questionId) emit('submitted');
  } catch (reason) {
    if (contextIsCurrent(userId, missionId) && props.question.id === questionId) error.value = goErrorMessage(reason, '回答提交失败，请稍后重试。');
  } finally {
    if (contextIsCurrent(userId, missionId)) submitting.value = false;
  }
}

watch(() => [props.question.id, props.question.latestAnswer?.id, props.question.latestAnswer?.answeredAt, safeOptions.value.join('\u0000')], syncAnswer, { immediate: true });
onBeforeUnmount(() => { active = false; });
</script>
