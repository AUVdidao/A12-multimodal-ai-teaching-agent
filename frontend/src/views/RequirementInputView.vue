<template>
  <section class="page requirement-page">
    <header class="page__header page__header--with-action">
      <div>
        <h2 class="page__title">教学需求与智能澄清</h2>
        <p class="page__description">项目 {{ projectId || '-' }} · M1 需求确认</p>
      </div>
      <el-button :icon="Refresh" :loading="loading" @click="loadState">刷新</el-button>
    </header>

    <el-steps :active="activeStep" finish-status="success" class="m1-steps" simple>
      <el-step title="需求输入" />
      <el-step title="智能澄清" />
      <el-step title="摘要确认" />
    </el-steps>

    <el-alert
      v-if="!projectId"
      title="请先从项目列表选择项目并完成生成模式设置。"
      type="warning"
      show-icon
      :closable="false"
    />

    <el-card v-else class="page-card requirement-form-card" shadow="never" v-loading="loading">
      <el-form label-position="top" @submit.prevent>
        <h3 class="section-title">课程信息</h3>
        <div class="form-grid">
          <el-form-item label="年级">
            <el-input v-model="form.gradeLevel" placeholder="例如：八年级" />
          </el-form-item>
          <el-form-item label="学科">
            <el-input v-model="form.subject" placeholder="例如：生物" />
          </el-form-item>
          <el-form-item label="课题">
            <el-input v-model="form.topic" placeholder="例如：光合作用" />
          </el-form-item>
          <el-form-item label="课时">
            <el-input v-model="form.lessonDuration" placeholder="例如：45分钟" />
          </el-form-item>
        </div>

        <h3 class="section-title">教学设计</h3>
        <el-form-item label="教学目标">
          <el-input v-model="form.teachingGoals" type="textarea" :rows="3" maxlength="4000" />
        </el-form-item>
        <div class="form-grid form-grid--wide">
          <el-form-item label="教学重点">
            <el-input v-model="form.keyPoints" type="textarea" :rows="3" maxlength="4000" />
          </el-form-item>
          <el-form-item label="教学难点">
            <el-input v-model="form.difficultPoints" type="textarea" :rows="3" maxlength="4000" />
          </el-form-item>
        </div>

        <h3 class="section-title">成果与补充描述</h3>
        <el-form-item label="输出类型">
          <el-checkbox-group v-model="form.outputTypes" class="output-types">
            <el-checkbox v-for="option in outputTypeOptions" :key="option.value" :value="option.value">
              {{ option.label }}
            </el-checkbox>
          </el-checkbox-group>
        </el-form-item>
        <el-form-item label="自由描述">
          <el-input
            v-model="form.rawRequirementText"
            type="textarea"
            :autosize="{ minRows: 4, maxRows: 8 }"
            maxlength="10000"
            show-word-limit
            placeholder="例如：希望课堂案例贴近生活，课件采用简洁的科技风格。"
          />
        </el-form-item>

        <el-alert
          v-if="errorMessage"
          class="inline-alert"
          :title="errorMessage"
          type="warning"
          show-icon
          :closable="false"
        />

        <div class="page__actions">
          <el-button :icon="Check" type="primary" :loading="saving" @click="saveAndCheck">
            保存并检查完整性
          </el-button>
          <el-button @click="router.push('/projects')">返回项目列表</el-button>
        </div>
      </el-form>
    </el-card>

    <section v-if="latestRequirement && projectId" class="clarification-section">
      <el-alert
        v-if="complete"
        title="需求信息已完整，可以生成结构化需求摘要。"
        type="success"
        show-icon
        :closable="false"
      />

      <template v-else-if="missingFields.length">
        <div class="section-heading">
          <div>
            <h3>AI 主动追问</h3>
            <p>还有 {{ missingFields.length }} 项关键信息待补充</p>
          </div>
          <el-tag type="warning">Mock AI</el-tag>
        </div>

        <div class="clarification-list">
          <article v-for="(field, index) in missingFields" :key="field.field" class="clarification-item">
            <div class="question-bubble">
              <el-icon><ChatDotRound /></el-icon>
              <div>
                <strong>{{ questions[index] || field.label }}</strong>
                <p>{{ field.reason }}</p>
              </div>
            </div>

            <el-checkbox-group
              v-if="field.field === 'outputTypes'"
              v-model="form.outputTypes"
              class="output-types"
            >
              <el-checkbox v-for="option in outputTypeOptions" :key="option.value" :value="option.value">
                {{ option.label }}
              </el-checkbox>
            </el-checkbox-group>
            <el-input
              v-else
              :model-value="fieldValue(field.field)"
              :type="field.field === 'teachingGoals' ? 'textarea' : 'text'"
              :rows="field.field === 'teachingGoals' ? 3 : undefined"
              :placeholder="fieldPlaceholder(field.field)"
              @update:model-value="setFieldValue(field.field, $event)"
            />
          </article>
        </div>

        <el-button
          :icon="EditPen"
          type="primary"
          :loading="supplementing"
          :disabled="supplementing"
          @click="saveSupplement"
        >
          保存补充信息
        </el-button>
      </template>
    </section>

    <section v-if="workflowMessages.length" class="history-section">
      <div class="section-heading">
        <div>
          <h3>澄清记录</h3>
          <p>{{ workflowMessages.length }} 条消息 · {{ sessionId }}</p>
        </div>
      </div>
      <div class="dialog-timeline">
        <article
          v-for="message in workflowMessages"
          :key="message.id"
          :class="['dialog-message', `dialog-message--${normalizeSender(message.sender).toLowerCase()}`]"
        >
          <div class="dialog-message__meta">
            <strong>{{ normalizeSender(message.sender) === 'AI' ? 'AI' : '教师' }}</strong>
            <span>第 {{ message.roundNo }} 轮</span>
            <time :datetime="message.createdAt">{{ formatDate(message.createdAt) }}</time>
          </div>
          <p>{{ message.content }}</p>
        </article>
      </div>
    </section>

    <div v-if="projectId" class="page__actions flow-actions">
      <el-button
        :icon="DocumentChecked"
        type="primary"
        :disabled="!complete"
        @click="openSummary"
      >
        生成需求摘要
      </el-button>
      <el-button @click="router.push({ name: 'project-mode', params: { projectId } })">
        返回生成模式
      </el-button>
    </div>
  </section>
</template>

<script setup lang="ts">
import {
  checkClarification,
  getClarificationQuestions,
  type MissingField,
} from '@/api/clarification';
import {
  listProjectDialogues,
  saveDialogueMessage,
  type DialogueMessage,
  type DialogueSender,
} from '@/api/dialogues';
import {
  getLatestTeachingRequirement,
  saveTeachingRequirement,
  type TeachingRequirement,
  type TeachingRequirementPayload,
} from '@/api/requirements';
import { ChatDotRound, Check, DocumentChecked, EditPen, Refresh } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import { computed, onMounted, reactive, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';

type TextField =
  | 'gradeLevel'
  | 'subject'
  | 'topic'
  | 'lessonDuration'
  | 'teachingGoals'
  | 'keyPoints'
  | 'difficultPoints'
  | 'rawRequirementText';

const route = useRoute();
const router = useRouter();

const projectId = computed(() => {
  const candidate = route.params.projectId || route.query.projectId;
  const value = Array.isArray(candidate) ? candidate[0] : candidate;
  const numericValue = Number(value);
  return Number.isInteger(numericValue) && numericValue > 0 ? numericValue : null;
});
const sessionId = computed(() => `project-${projectId.value}-clarification`);

const form = reactive<TeachingRequirementPayload>({
  gradeLevel: '',
  subject: '',
  topic: '',
  lessonDuration: '',
  teachingGoals: '',
  keyPoints: '',
  difficultPoints: '',
  outputTypes: [],
  rawRequirementText: '',
});

const outputTypeOptions = [
  { value: 'PPT', label: 'PPT 课件' },
  { value: 'LESSON_PLAN', label: 'Word 教案' },
  { value: 'INTERACTION', label: '互动内容' },
];

const latestRequirement = ref<TeachingRequirement | null>(null);
const missingFields = ref<MissingField[]>([]);
const questions = ref<string[]>([]);
const messages = ref<DialogueMessage[]>([]);
const complete = ref(false);
const loading = ref(false);
const saving = ref(false);
const supplementing = ref(false);
const errorMessage = ref('');

const workflowMessages = computed(() =>
  messages.value
    .filter((message) => message.sessionId === sessionId.value)
    .sort((left, right) => left.roundNo - right.roundNo || left.id - right.id),
);
const activeStep = computed(() => (complete.value ? 2 : latestRequirement.value ? 1 : 0));

onMounted(loadState);

async function loadState() {
  if (!projectId.value) {
    errorMessage.value = '缺少有效项目 ID。';
    return;
  }

  loading.value = true;
  errorMessage.value = '';
  try {
    const [requirement, dialogueHistory] = await Promise.all([
      getLatestTeachingRequirement(projectId.value),
      listProjectDialogues(projectId.value),
    ]);
    messages.value = dialogueHistory;
    latestRequirement.value = requirement;
    if (requirement) {
      applyRequirement(requirement);
      await evaluateCurrent(true);
    } else {
      complete.value = false;
      missingFields.value = [];
      questions.value = [];
    }
  } catch (error) {
    errorMessage.value = resolveErrorMessage(error, '需求信息读取失败。');
  } finally {
    loading.value = false;
  }
}

async function saveAndCheck() {
  if (!projectId.value || !validateTopicOrRawText()) {
    return;
  }

  saving.value = true;
  errorMessage.value = '';
  try {
    latestRequirement.value = await saveTeachingRequirement(projectId.value, payload());
    applyRequirement(latestRequirement.value);
    await evaluateCurrent(true);
    ElMessage.success(complete.value ? '需求已保存，信息完整' : '需求已保存，AI 已生成追问');
  } catch (error) {
    errorMessage.value = resolveErrorMessage(error, '需求保存失败。');
  } finally {
    saving.value = false;
  }
}

async function saveSupplement() {
  if (!projectId.value || supplementing.value) {
    return;
  }

  const answer = buildTeacherAnswer();
  if (!answer) {
    ElMessage.warning('请至少补充一项缺失信息。');
    return;
  }

  supplementing.value = true;
  errorMessage.value = '';
  try {
    latestRequirement.value = await saveTeachingRequirement(projectId.value, payload());
    const savedMessage = await saveDialogueMessage(projectId.value, {
      sessionId: sessionId.value,
      sender: 'TEACHER',
      content: answer,
      roundNo: currentQuestionRound(),
    });
    messages.value = [...messages.value, savedMessage];
    await evaluateCurrent(true);
    ElMessage.success(complete.value ? '补充完成，需求信息已完整' : '补充已保存，AI 已继续追问');
  } catch (error) {
    errorMessage.value = resolveErrorMessage(error, '补充信息保存失败。');
  } finally {
    supplementing.value = false;
  }
}

async function evaluateCurrent(persistQuestion: boolean) {
  if (!projectId.value) {
    return;
  }

  const checkResult = await checkClarification(projectId.value, payload());
  complete.value = checkResult.complete;
  missingFields.value = checkResult.missingFields;
  questions.value = [];

  if (checkResult.complete) {
    return;
  }

  const questionResult = await getClarificationQuestions(projectId.value, payload());
  questions.value = questionResult.questions;
  if (persistQuestion) {
    await persistAiQuestions(questionResult.questions);
  }
}

async function persistAiQuestions(items: string[]) {
  if (!projectId.value || items.length === 0) {
    return;
  }

  const content = items.map((question, index) => `${index + 1}. ${question}`).join('\n');
  const duplicate = workflowMessages.value.some(
    (message) => normalizeSender(message.sender) === 'AI' && normalizeContent(message.content) === normalizeContent(content),
  );
  if (duplicate) {
    return;
  }

  const savedMessage = await saveDialogueMessage(projectId.value, {
    sessionId: sessionId.value,
    sender: 'AI',
    content,
    roundNo: nextAiRound(),
  });
  messages.value = [...messages.value, savedMessage];
}

function payload(): TeachingRequirementPayload {
  return {
    gradeLevel: form.gradeLevel,
    subject: form.subject,
    topic: form.topic,
    lessonDuration: form.lessonDuration,
    teachingGoals: form.teachingGoals,
    keyPoints: form.keyPoints,
    difficultPoints: form.difficultPoints,
    outputTypes: [...form.outputTypes],
    rawRequirementText: form.rawRequirementText,
  };
}

function applyRequirement(requirement: TeachingRequirement) {
  form.gradeLevel = requirement.gradeLevel || '';
  form.subject = requirement.subject || '';
  form.topic = requirement.topic || '';
  form.lessonDuration = requirement.lessonDuration || '';
  form.teachingGoals = requirement.teachingGoals || '';
  form.keyPoints = requirement.keyPoints || '';
  form.difficultPoints = requirement.difficultPoints || '';
  form.outputTypes = [...(requirement.outputTypes || [])];
  form.rawRequirementText = requirement.rawRequirementText || '';
}

function validateTopicOrRawText() {
  if (!hasText(form.topic) && !hasText(form.rawRequirementText)) {
    errorMessage.value = '课题和自由描述至少填写一项。';
    return false;
  }
  return true;
}

function buildTeacherAnswer() {
  return missingFields.value
    .map((field) => {
      const value = answerValue(field.field);
      return value ? `${field.label}：${value}` : '';
    })
    .filter(Boolean)
    .join('\n');
}

function answerValue(field: string) {
  if (field === 'outputTypes') {
    return form.outputTypes.join('、');
  }
  return fieldValue(field).trim();
}

function fieldValue(field: string) {
  if (!isTextField(field)) {
    return '';
  }
  return form[field] || '';
}

function setFieldValue(field: string, value: string) {
  if (isTextField(field)) {
    form[field] = value;
  }
}

function isTextField(field: string): field is TextField {
  return [
    'gradeLevel',
    'subject',
    'topic',
    'lessonDuration',
    'teachingGoals',
    'keyPoints',
    'difficultPoints',
    'rawRequirementText',
  ].includes(field);
}

function fieldPlaceholder(field: string) {
  const placeholders: Record<string, string> = {
    gradeLevel: '补充学生年级',
    subject: '补充课程学科',
    topic: '补充具体课题',
    lessonDuration: '补充课时长度',
    teachingGoals: '补充可达成的教学目标',
  };
  return placeholders[field] || '补充信息';
}

function nextAiRound() {
  if (workflowMessages.value.length === 0) {
    return 1;
  }
  const maxRound = Math.max(...workflowMessages.value.map((message) => message.roundNo));
  const lastMessage = workflowMessages.value[workflowMessages.value.length - 1];
  return normalizeSender(lastMessage.sender) === 'TEACHER' ? maxRound + 1 : maxRound;
}

function currentQuestionRound() {
  const latestAiMessage = [...workflowMessages.value]
    .reverse()
    .find((message) => normalizeSender(message.sender) === 'AI');
  return latestAiMessage?.roundNo || 1;
}

function normalizeSender(sender: DialogueSender) {
  return sender === 'ASSISTANT' ? 'AI' : sender;
}

function normalizeContent(value: string) {
  return value.replace(/\s+/g, ' ').trim();
}

function hasText(value?: string) {
  return Boolean(value?.trim());
}

function formatDate(value: string) {
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(value));
}

function openSummary() {
  if (projectId.value && complete.value) {
    router.push({ name: 'project-requirement-summary', params: { projectId: projectId.value } });
  }
}

function resolveErrorMessage(error: unknown, fallback: string) {
  const candidate = error as { response?: { data?: { message?: string } } };
  return candidate.response?.data?.message || fallback;
}
</script>

<style scoped>
.requirement-page {
  max-width: 1120px;
}

.m1-steps {
  margin-bottom: 20px;
}

.requirement-form-card {
  max-width: 960px;
}

.section-title {
  margin: 6px 0 16px;
  padding-top: 14px;
  border-top: 1px solid #e5e7eb;
  font-size: 16px;
}

.section-title:first-child {
  padding-top: 0;
  border-top: 0;
}

.form-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 14px;
}

.form-grid--wide {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.output-types {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
}

.clarification-section,
.history-section {
  max-width: 960px;
  margin-top: 24px;
  padding-top: 22px;
  border-top: 1px solid #dfe3ea;
}

.section-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 16px;
}

.section-heading h3,
.section-heading p {
  margin: 0;
}

.section-heading h3 {
  font-size: 18px;
}

.section-heading p {
  margin-top: 6px;
  color: #667085;
  font-size: 13px;
}

.clarification-list {
  display: grid;
  gap: 12px;
  margin-bottom: 16px;
}

.clarification-item {
  display: grid;
  grid-template-columns: minmax(260px, 1fr) minmax(260px, 1fr);
  align-items: center;
  gap: 18px;
  padding: 16px;
  border: 1px solid #bfdbfe;
  border-radius: 8px;
  background: #f8fbff;
}

.question-bubble {
  display: flex;
  align-items: flex-start;
  gap: 10px;
}

.question-bubble strong,
.question-bubble p {
  display: block;
  margin: 0;
}

.question-bubble p {
  margin-top: 6px;
  color: #667085;
  font-size: 13px;
  line-height: 1.6;
}

.dialog-timeline {
  display: grid;
  gap: 12px;
}

.dialog-message {
  width: min(78%, 760px);
  padding: 14px 16px;
  border: 1px solid #dbeafe;
  border-radius: 8px;
  background: #eff6ff;
}

.dialog-message--teacher {
  justify-self: end;
  border-color: #a7f3d0;
  background: #ecfdf5;
}

.dialog-message--ai {
  justify-self: start;
}

.dialog-message__meta {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  color: #667085;
  font-size: 12px;
}

.dialog-message p {
  margin: 8px 0 0;
  line-height: 1.7;
  overflow-wrap: anywhere;
  white-space: pre-wrap;
}

.flow-actions {
  max-width: 960px;
  padding-top: 4px;
}

@media (max-width: 900px) {
  .form-grid,
  .form-grid--wide,
  .clarification-item {
    grid-template-columns: 1fr;
  }

  .dialog-message {
    width: 100%;
  }
}
</style>
