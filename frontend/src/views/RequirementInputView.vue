<template>
  <section class="page requirement-page">
    <PageHeader
      eyebrow="M1 · 第 3–4 步"
      title="教学需求与 AI 澄清"
      description="先记录教师的真实教学设想，再由 AI 识别缺失信息并引导补充。"
      :project-label="project ? project.projectName : projectId ? `项目 #${projectId}` : undefined"
    >
      <template #meta>
        <span v-if="project" class="header-meta">{{ project.courseName }} · {{ project.chapterTitle }}</span>
        <span v-if="modelModeName" class="header-meta">{{ modelModeName }}</span>
      </template>
      <template #actions>
        <el-button :icon="Refresh" :loading="loading" @click="loadState">刷新状态</el-button>
      </template>
    </PageHeader>

    <M1ProgressSteps
      :current-step="progressCurrentStep"
      :unlocked-step="progressUnlockedStep"
      :completed-through="progressCompletedThrough"
      :project-id="projectId"
    />

    <StatePanel
      v-if="!projectId"
      type="error"
      title="没有可用的教学项目"
      description="请先从项目列表创建或选择项目，并完成生成模式设置。"
    >
      <template #action><el-button type="primary" size="small" @click="router.push('/projects')">返回项目列表</el-button></template>
    </StatePanel>

    <div v-else class="requirement-workspace">
      <section class="surface-panel requirement-editor" v-loading="loading" aria-label="教学需求表单">
        <div v-if="latestRequirement" class="loaded-state" role="status">
          <el-icon><CircleCheck /></el-icon>
          <div><strong>已加载最近一次需求</strong><span>版本 #{{ latestRequirement.id }} · {{ formatDate(latestRequirement.updatedAt) }}</span></div>
        </div>

        <el-form label-position="top" @submit.prevent>
          <FormSection :icon="Reading" title="课程基础信息" description="先描述面向谁、讲什么以及本节课的时间范围。">
            <div class="form-grid">
              <el-form-item label="年级">
                <el-input v-model="form.gradeLevel" placeholder="例如：八年级" />
              </el-form-item>
              <el-form-item label="学科">
                <el-input v-model="form.subject" placeholder="例如：生物" />
              </el-form-item>
              <el-form-item label="课题">
                <el-input v-model="form.topic" placeholder="例如：绿色植物的光合作用" />
              </el-form-item>
              <el-form-item label="课时">
                <el-input v-model="form.lessonDuration" placeholder="例如：45 分钟" />
              </el-form-item>
            </div>
          </FormSection>

          <FormSection :icon="Notebook" title="教学设计" description="这些字段可以暂时留空，保存后 AI 会针对缺失信息主动追问。">
            <el-form-item label="教学目标">
              <el-input v-model="form.teachingGoals" type="textarea" :rows="3" maxlength="4000" placeholder="描述学生在本节课结束后应达到的学习结果" />
            </el-form-item>
            <div class="form-grid form-grid--two">
              <el-form-item label="教学重点">
                <el-input v-model="form.keyPoints" type="textarea" :rows="3" maxlength="4000" placeholder="本节课需要重点掌握的内容" />
              </el-form-item>
              <el-form-item label="教学难点">
                <el-input v-model="form.difficultPoints" type="textarea" :rows="3" maxlength="4000" placeholder="学生理解或迁移时可能遇到的困难" />
              </el-form-item>
            </div>
          </FormSection>

          <FormSection :icon="Document" title="输出与补充要求" description="这里只记录期望成果，不代表相关文件已经生成。">
            <el-form-item label="期望输出">
              <el-checkbox-group v-model="form.outputTypes" class="output-types">
                <el-checkbox v-for="option in outputTypeOptions" :key="option.value" :value="option.value">{{ option.label }}</el-checkbox>
              </el-checkbox-group>
            </el-form-item>
            <el-form-item label="自由描述">
              <el-input
                v-model="form.rawRequirementText"
                type="textarea"
                :autosize="{ minRows: 4, maxRows: 8 }"
                maxlength="10000"
                show-word-limit
                placeholder="例如：希望课堂案例贴近生活，以探究活动引导学生理解概念。"
              />
            </el-form-item>
          </FormSection>

          <el-alert v-if="errorMessage" class="inline-alert" :title="errorMessage" type="warning" show-icon :closable="false" />

          <PrimaryActionBar>
            <template #info>{{ latestRequirement ? '本次保存将形成新的需求版本。' : '课题与自由描述至少填写一项。' }}</template>
            <template #secondary><el-button @click="router.push({ name: 'project-mode', params: { projectId } })">返回生成模式</el-button></template>
            <el-button :icon="Check" type="primary" :loading="saving" :disabled="saving || supplementing" @click="saveAndCheck">
              {{ latestRequirement ? '保存新版本并重新检查' : '保存并进入 AI 澄清' }}
            </el-button>
          </PrimaryActionBar>
        </el-form>
      </section>

      <aside id="clarification" class="surface-panel clarification-panel" aria-label="AI 主动澄清">
        <header class="clarification-panel__header">
          <div class="ai-identity"><span><el-icon><Cpu /></el-icon></span><div><strong>AI 需求助教</strong><small>智能澄清</small></div></div>
          <StatusBadge :status="complete ? 'CONFIRMED' : latestRequirement ? 'DRAFT' : 'WAITING'" :label="complete ? '信息完整' : latestRequirement ? '正在澄清' : '等待需求'" />
        </header>

        <StatePanel
          v-if="!latestRequirement"
          type="info"
          title="保存后开始分析"
          description="AI 会检查年级、学科、课题、课时、教学目标和输出要求，不会编造缺失内容。"
        />

        <template v-else>
          <StatePanel v-if="complete" type="success" title="需求信息已经完整" description="所有必要字段已补齐，可以生成结构化需求摘要。">
            <template #action><el-button type="primary" size="small" :icon="DocumentChecked" @click="openSummary">生成需求摘要</el-button></template>
          </StatePanel>

          <template v-else-if="missingFields.length">
            <div class="clarification-summary">
              <div><strong>{{ missingFields.length }}</strong><span>项信息待补充</span></div>
              <p>根据下面问题补充内容，保存后将自动重新检查。</p>
            </div>

            <div class="clarification-list">
              <article v-for="(field, index) in missingFields" :key="field.field" class="clarification-item">
                <DialogueBubble sender="AI" :content="questions[index] || field.label" />
                <div class="clarification-item__answer">
                  <div class="field-label"><span>{{ field.label }}</span><small>{{ answerValue(field.field) ? '已填写' : '待补充' }}</small></div>
                  <el-checkbox-group v-if="field.field === 'outputTypes'" v-model="form.outputTypes" class="output-types output-types--compact">
                    <el-checkbox v-for="option in outputTypeOptions" :key="option.value" :value="option.value">{{ option.label }}</el-checkbox>
                  </el-checkbox-group>
                  <el-input
                    v-else
                    :model-value="fieldValue(field.field)"
                    :type="isLongField(field.field) ? 'textarea' : 'text'"
                    :rows="isLongField(field.field) ? 3 : undefined"
                    :placeholder="fieldPlaceholder(field.field)"
                    @update:model-value="setFieldValue(field.field, $event)"
                  />
                  <p>{{ field.reason }}</p>
                </div>
              </article>
            </div>

            <el-button class="supplement-action" :icon="EditPen" type="primary" :loading="supplementing" :disabled="supplementing || saving" @click="saveSupplement">保存并重新检查</el-button>
          </template>

          <section v-if="visibleHistoryMessages.length" class="history-section" aria-labelledby="history-title">
            <div class="history-section__heading"><div><strong id="history-title">澄清记录</strong><span>{{ visibleHistoryMessages.length }} 条消息</span></div><small>按轮次自动保存</small></div>
            <div ref="historyPanel" class="dialogue-timeline" tabindex="0" aria-label="澄清对话历史">
              <DialogueBubble
                v-for="message in visibleHistoryMessages"
                :key="message.id"
                :sender="normalizeSender(message.sender)"
                :content="message.content"
                :round-no="message.roundNo"
                :created-at="message.createdAt"
              />
            </div>
          </section>
        </template>
      </aside>
    </div>
  </section>
</template>

<script setup lang="ts">
import { checkClarification, getClarificationQuestions, type MissingField } from '@/api/clarification';
import { listProjectDialogues, saveDialogueMessage, type DialogueMessage, type DialogueSender } from '@/api/dialogues';
import { getProject, getProjectModelMode, type TeachingProject } from '@/api/projects';
import { getLatestTeachingRequirement, saveTeachingRequirement, type TeachingRequirement, type TeachingRequirementPayload } from '@/api/requirements';
import DialogueBubble from '@/components/DialogueBubble.vue';
import FormSection from '@/components/FormSection.vue';
import M1ProgressSteps from '@/components/M1ProgressSteps.vue';
import PageHeader from '@/components/PageHeader.vue';
import PrimaryActionBar from '@/components/PrimaryActionBar.vue';
import StatePanel from '@/components/StatePanel.vue';
import StatusBadge from '@/components/StatusBadge.vue';
import { Check, CircleCheck, Cpu, Document, DocumentChecked, EditPen, Notebook, Reading, Refresh } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';

type TextField = 'gradeLevel' | 'subject' | 'topic' | 'lessonDuration' | 'teachingGoals' | 'keyPoints' | 'difficultPoints' | 'rawRequirementText';

const route = useRoute();
const router = useRouter();
const projectId = computed(() => {
  const candidate = route.params.projectId || route.query.projectId;
  const value = Array.isArray(candidate) ? candidate[0] : candidate;
  const numericValue = Number(value);
  return Number.isInteger(numericValue) && numericValue > 0 ? numericValue : null;
});
const sessionId = computed(() => `project-${projectId.value}-clarification`);

const form = reactive<TeachingRequirementPayload>({ gradeLevel: '', subject: '', topic: '', lessonDuration: '', teachingGoals: '', keyPoints: '', difficultPoints: '', outputTypes: [], rawRequirementText: '' });
const outputTypeOptions = [
  { value: 'PPT', label: 'PPT 课件' },
  { value: 'LESSON_PLAN', label: 'Word 教案' },
  { value: 'INTERACTION', label: '互动内容' },
];

const project = ref<TeachingProject | null>(null);
const modelModeName = ref('');
const latestRequirement = ref<TeachingRequirement | null>(null);
const missingFields = ref<MissingField[]>([]);
const questions = ref<string[]>([]);
const messages = ref<DialogueMessage[]>([]);
const complete = ref(false);
const loading = ref(false);
const saving = ref(false);
const supplementing = ref(false);
const errorMessage = ref('');
const historyPanel = ref<HTMLElement>();

const workflowMessages = computed(() => messages.value
  .filter((message) => message.sessionId === sessionId.value)
  .sort((left, right) => left.roundNo - right.roundNo || left.id - right.id));
const currentQuestionContent = computed(() => questions.value.map((question, index) => `${index + 1}. ${question}`).join('\n'));
const visibleHistoryMessages = computed(() => workflowMessages.value.filter((message) => !(
  !complete.value &&
  normalizeSender(message.sender) === 'AI' &&
  normalizeContent(message.content) === normalizeContent(currentQuestionContent.value)
)));
const progressCurrentStep = computed(() => latestRequirement.value ? 3 : 2);
const progressUnlockedStep = computed(() => complete.value ? 4 : latestRequirement.value ? 3 : 2);
const progressCompletedThrough = computed(() => complete.value ? 3 : latestRequirement.value ? 2 : 1);

watch(() => visibleHistoryMessages.value.length, async () => {
  await nextTick();
  if (historyPanel.value) historyPanel.value.scrollTop = historyPanel.value.scrollHeight;
});
onMounted(loadState);

async function loadState() {
  if (!projectId.value) {
    errorMessage.value = '缺少有效项目 ID。';
    return;
  }
  loading.value = true;
  errorMessage.value = '';
  try {
    const [requirement, dialogueHistory, projectResult, modeResult] = await Promise.all([
      getLatestTeachingRequirement(projectId.value),
      listProjectDialogues(projectId.value),
      getProject(projectId.value),
      getProjectModelMode(projectId.value),
    ]);
    project.value = projectResult;
    modelModeName.value = modeResult.name;
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
    errorMessage.value = resolveErrorMessage(error, '需求信息读取失败，请稍后重试。');
  } finally {
    loading.value = false;
  }
}

async function saveAndCheck() {
  if (!projectId.value || saving.value || !validateTopicOrRawText()) return;
  saving.value = true;
  errorMessage.value = '';
  try {
    latestRequirement.value = await saveTeachingRequirement(projectId.value, payload());
    applyRequirement(latestRequirement.value);
    await evaluateCurrent(true);
    ElMessage.success(complete.value ? '需求已保存，信息完整' : '需求已保存，AI 已生成追问');
  } catch (error) {
    errorMessage.value = resolveErrorMessage(error, '需求保存失败，请稍后重试。');
  } finally {
    saving.value = false;
  }
}

async function saveSupplement() {
  if (!projectId.value || supplementing.value || saving.value) return;
  const answer = buildTeacherAnswer();
  if (!answer) {
    ElMessage.warning('请至少补充一项缺失信息。');
    return;
  }
  supplementing.value = true;
  errorMessage.value = '';
  try {
    latestRequirement.value = await saveTeachingRequirement(projectId.value, payload());
    const savedMessage = await saveDialogueMessage(projectId.value, { sessionId: sessionId.value, sender: 'TEACHER', content: answer, roundNo: currentQuestionRound() });
    messages.value = [...messages.value, savedMessage];
    await evaluateCurrent(true);
    ElMessage.success(complete.value ? '补充完成，需求信息已完整' : '补充已保存，AI 已继续追问');
  } catch (error) {
    errorMessage.value = resolveErrorMessage(error, '补充信息保存失败，请稍后重试。');
  } finally {
    supplementing.value = false;
  }
}

async function evaluateCurrent(persistQuestion: boolean) {
  if (!projectId.value) return;
  const checkResult = await checkClarification(projectId.value, payload());
  complete.value = checkResult.complete;
  missingFields.value = checkResult.missingFields;
  questions.value = [];
  if (checkResult.complete) return;
  const questionResult = await getClarificationQuestions(projectId.value, payload());
  questions.value = questionResult.questions;
  if (persistQuestion) await persistAiQuestions(questionResult.questions);
}

async function persistAiQuestions(items: string[]) {
  if (!projectId.value || items.length === 0) return;
  const content = items.map((question, index) => `${index + 1}. ${question}`).join('\n');
  const duplicate = workflowMessages.value.some((message) => normalizeSender(message.sender) === 'AI' && normalizeContent(message.content) === normalizeContent(content));
  if (duplicate) return;
  const savedMessage = await saveDialogueMessage(projectId.value, { sessionId: sessionId.value, sender: 'AI', content, roundNo: nextAiRound() });
  messages.value = [...messages.value, savedMessage];
}

function payload(): TeachingRequirementPayload {
  return { gradeLevel: form.gradeLevel, subject: form.subject, topic: form.topic, lessonDuration: form.lessonDuration, teachingGoals: form.teachingGoals, keyPoints: form.keyPoints, difficultPoints: form.difficultPoints, outputTypes: [...form.outputTypes], rawRequirementText: form.rawRequirementText };
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
    errorMessage.value = '请至少填写课题或自由描述中的一项。';
    return false;
  }
  return true;
}

function buildTeacherAnswer() {
  return missingFields.value.map((field) => {
    const value = answerValue(field.field);
    return value ? `${field.label}：${value}` : '';
  }).filter(Boolean).join('\n');
}

function answerValue(field: string) {
  if (field === 'outputTypes') return form.outputTypes.map(outputLabel).join('、');
  return fieldValue(field).trim();
}

function outputLabel(value: string) {
  return outputTypeOptions.find((option) => option.value === value)?.label || value;
}

function fieldValue(field: string) {
  return isTextField(field) ? form[field] || '' : '';
}

function setFieldValue(field: string, value: string) {
  if (isTextField(field)) form[field] = value;
}

function isTextField(field: string): field is TextField {
  return ['gradeLevel', 'subject', 'topic', 'lessonDuration', 'teachingGoals', 'keyPoints', 'difficultPoints', 'rawRequirementText'].includes(field);
}

function isLongField(field: string) {
  return ['teachingGoals', 'keyPoints', 'difficultPoints', 'rawRequirementText'].includes(field);
}

function fieldPlaceholder(field: string) {
  const placeholders: Record<string, string> = {
    gradeLevel: '补充学生年级', subject: '补充课程学科', topic: '补充具体课题', lessonDuration: '补充课时长度', teachingGoals: '补充可达成的教学目标', keyPoints: '补充教学重点', difficultPoints: '补充教学难点',
  };
  return placeholders[field] || '补充信息';
}

function nextAiRound() {
  if (workflowMessages.value.length === 0) return 1;
  const maxRound = Math.max(...workflowMessages.value.map((message) => message.roundNo));
  const lastMessage = workflowMessages.value[workflowMessages.value.length - 1];
  return normalizeSender(lastMessage.sender) === 'TEACHER' ? maxRound + 1 : maxRound;
}

function currentQuestionRound() {
  const latestAiMessage = [...workflowMessages.value].reverse().find((message) => normalizeSender(message.sender) === 'AI');
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
  return new Intl.DateTimeFormat('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }).format(new Date(value));
}

function openSummary() {
  if (projectId.value && complete.value) router.push({ name: 'project-requirement-summary', params: { projectId: projectId.value } });
}

function resolveErrorMessage(error: unknown, fallback: string) {
  const candidate = error as { response?: { data?: { message?: string } } };
  const message = candidate.response?.data?.message;
  return message && !/(Exception|java\.|Axios)/i.test(message) ? message : fallback;
}
</script>

<style scoped>
.header-meta {
  color: var(--color-text-muted);
  font-size: 11px;
}

.requirement-workspace {
  display: grid;
  grid-template-columns: minmax(0, 1.8fr) minmax(330px, .82fr);
  grid-template-areas: "clarification editor";
  align-items: start;
  gap: 16px;
}

.requirement-editor {
  grid-area: editor;
  position: sticky;
  top: 78px;
  min-width: 0;
  max-height: calc(100vh - 100px);
  overflow: hidden auto;
  padding: 18px;
}

.loaded-state {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 18px;
  padding: 12px 14px;
  border: 1px solid #bce8db;
  border-radius: var(--radius-md);
  background: var(--color-success-soft);
  color: var(--color-success);
}

.loaded-state strong,
.loaded-state span {
  display: block;
}

.loaded-state strong {
  color: var(--color-text);
  font-size: 12px;
}

.loaded-state span {
  margin-top: 2px;
  font-size: 10px;
}

.form-grid {
  display: grid;
  grid-template-columns: 1fr;
  gap: 0;
}

.form-grid--two {
  grid-template-columns: 1fr;
}

.output-types {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 18px;
}

.clarification-panel {
  grid-area: clarification;
  min-width: 0;
  min-height: 620px;
  padding: 20px;
}

.clarification-panel__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 18px;
  padding-bottom: 16px;
  border-bottom: 1px solid var(--color-border);
}

.ai-identity {
  display: flex;
  align-items: center;
  gap: 10px;
}

.ai-identity > span {
  display: grid;
  width: 34px;
  height: 34px;
  place-items: center;
  border-radius: var(--radius-md);
  background: var(--color-ai-soft);
  color: var(--color-ai);
  font-size: 18px;
}

.ai-identity strong,
.ai-identity small {
  display: block;
}

.ai-identity strong {
  font-size: 13px;
}

.ai-identity small {
  margin-top: 2px;
  color: var(--color-text-muted);
  font-size: 9px;
}

.clarification-summary {
  margin-bottom: 16px;
  padding: 14px;
  border-left: 3px solid var(--color-warning);
  background: var(--color-warning-soft);
}

.clarification-summary div {
  display: flex;
  align-items: baseline;
  gap: 6px;
}

.clarification-summary strong {
  color: var(--color-warning);
  font-size: 22px;
}

.clarification-summary span {
  font-size: 12px;
  font-weight: 700;
}

.clarification-summary p {
  margin: 5px 0 0;
  color: var(--color-text-secondary);
  font-size: 10px;
}

.clarification-list {
  display: grid;
  gap: 18px;
}

.clarification-item {
  display: grid;
  gap: 10px;
}

.clarification-item__answer {
  margin-left: 42px;
  padding: 12px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: var(--color-surface-subtle);
}

.clarification-item__answer > p {
  margin: 7px 0 0;
  color: var(--color-text-muted);
  font-size: 9px;
  line-height: 1.5;
}

.field-label {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 7px;
}

.field-label span {
  color: var(--color-text);
  font-size: 11px;
  font-weight: 700;
}

.field-label small {
  color: var(--color-success);
  font-size: 9px;
}

.output-types--compact {
  display: grid;
  gap: 6px;
}

.supplement-action {
  position: sticky;
  bottom: 0;
  z-index: 2;
  width: 100%;
  margin-top: 18px;
  box-shadow: 0 -10px 18px var(--color-surface);
}

.history-section {
  margin-top: 22px;
  padding-top: 18px;
  border-top: 1px solid var(--color-border);
}

.history-section__heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}

.history-section__heading strong,
.history-section__heading span {
  display: block;
}

.history-section__heading strong {
  font-size: 12px;
}

.history-section__heading span,
.history-section__heading small {
  margin-top: 2px;
  color: var(--color-text-muted);
  font-size: 9px;
}

.dialogue-timeline {
  display: grid;
  max-height: 380px;
  gap: 12px;
  overflow-y: auto;
  padding: 4px 5px 4px 0;
  scroll-behavior: smooth;
}

@media (max-width: 1120px) {
  .requirement-workspace {
    grid-template-columns: 1fr;
    grid-template-areas: "clarification" "editor";
  }

  .clarification-panel {
    position: static;
    max-height: none;
  }

  .requirement-editor {
    position: static;
    max-height: none;
  }
}

@media (max-width: 640px) {
  .requirement-editor,
  .clarification-panel {
    padding: 18px;
  }

  .form-grid,
  .form-grid--two {
    grid-template-columns: 1fr;
  }

  .clarification-item__answer {
    margin-left: 0;
  }
}
</style>
