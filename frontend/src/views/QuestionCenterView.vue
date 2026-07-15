<template>
  <section class="page question-center">
    <PageHeader
      eyebrow="问答中心"
      :title="pageTitle"
      :description="pageDescription"
    >
      <template #meta>
        <span class="role-context">
          <el-icon><User /></el-icon>
          当前身份：{{ roleLabel }}
        </span>
      </template>
      <template #actions>
        <el-tooltip content="刷新问答" placement="bottom">
          <el-button
            circle
            :icon="Refresh"
            :loading="loading"
            aria-label="刷新问答"
            @click="handleRefresh"
          />
        </el-tooltip>
        <el-button v-if="isStudent && learningTasks.length > 0" type="primary" :icon="Plus" @click="openAskDialog">
          提出问题
        </el-button>
      </template>
    </PageHeader>

    <StatePanel
      v-if="!canAccess"
      type="error"
      title="当前身份无法访问问答中心"
      description="请先登录并选择学生、教师或教研负责人身份。"
    />
    <StatePanel
      v-else-if="loading && questions.length === 0"
      type="loading"
      title="正在读取问答"
      :description="loadingDescription"
    />
    <StatePanel
      v-else-if="isStudent && learningTasksLoading && learningTasks.length === 0"
      type="loading"
      title="正在读取可提问内容"
      description="正在读取你所在班级的已发布学习内容。"
    />
    <StatePanel
      v-else-if="isStudent && learningTasksError && learningTasks.length === 0"
      type="error"
      title="学习内容读取失败"
      :description="learningTasksError"
    >
      <template #action>
        <el-button type="primary" :icon="Refresh" @click="loadLearningTasks">重新加载</el-button>
      </template>
    </StatePanel>
    <StatePanel
      v-else-if="isStudent && learningTasks.length === 0"
      type="empty"
      title="暂无可提问的已发布内容"
      description="你所在的班级暂时没有已发布学习内容。教师发布后，你可以从学习内容详情发起提问。"
    />
    <StatePanel
      v-else-if="errorMessage && questions.length === 0"
      type="error"
      title="问答读取失败"
      :description="errorMessage"
    >
      <template #action>
        <el-button type="primary" :icon="Refresh" @click="loadQuestions">重新加载</el-button>
      </template>
    </StatePanel>

    <section v-else class="surface-panel question-panel" v-loading="loading">
      <div class="question-toolbar">
        <el-radio-group v-model="statusFilter" class="status-filter" aria-label="按状态筛选问答">
          <el-radio-button value="ALL">全部</el-radio-button>
          <el-radio-button value="OPEN">待回答</el-radio-button>
          <el-radio-button value="ANSWERED">已回答</el-radio-button>
          <el-radio-button value="CLOSED">已关闭</el-radio-button>
        </el-radio-group>
        <div class="question-toolbar__right">
          <el-select
            v-if="isStudent"
            v-model="publicationFilter"
            class="question-learning-select"
            :loading="learningTasksLoading"
            filterable
            placeholder="选择学习内容"
            aria-label="选择学习内容"
            no-data-text="暂无已发布学习内容"
          >
            <el-option
              v-for="task in learningTasks"
              :key="task.publicationId"
              :label="learningTaskLabel(task)"
              :value="task.publicationId"
            />
          </el-select>
          <el-input-number
            v-else
            v-model="publicationFilter"
            :min="1"
            :controls="false"
            placeholder="发布编号"
            aria-label="按发布编号筛选"
          />
          <span class="question-toolbar__count">{{ questions.length }} 条</span>
        </div>
      </div>

      <el-alert
        v-if="isStudent && learningTasksError"
        class="question-panel__alert"
        type="error"
        :title="learningTasksError"
        show-icon
        :closable="false"
      />

      <el-alert
        v-if="errorMessage"
        class="question-panel__alert"
        type="error"
        :title="errorMessage"
        show-icon
        :closable="false"
      />

      <el-empty
        v-if="questions.length === 0"
        :description="emptyDescription"
        :image-size="72"
      />

      <div v-else class="question-list">
        <article v-for="question in questions" :key="question.id" class="question-row">
          <header class="question-row__heading">
            <div class="question-row__identity">
              <div class="question-row__title-line">
                <h2>{{ question.title }}</h2>
                <el-tag :type="statusTagType(question.status)" effect="light" size="small">
                  {{ statusLabel(question.status) }}
                </el-tag>
              </div>
              <p>
                <span>#{{ question.id }}</span>
                <span v-if="isStudent">{{ learningTaskName(question.publicationId) }}</span>
                <span v-else>发布 #{{ question.publicationId }}</span>
                <span>项目 #{{ question.projectId }}</span>
                <span v-if="!isStudent">{{ question.studentName }}</span>
              </p>
            </div>
            <time :datetime="question.updatedAt">{{ formatFullDateTime(question.updatedAt) }}</time>
          </header>

          <p class="question-row__content">{{ question.content }}</p>

          <section v-if="question.answers.length > 0" class="answer-list" aria-label="教师回答">
            <article v-for="answer in question.answers" :key="answer.id" class="answer-item">
              <div class="answer-item__meta">
                <el-icon><ChatDotRound /></el-icon>
                <strong>{{ answer.teacherName }}</strong>
                <time :datetime="answer.createdAt">{{ formatFullDateTime(answer.createdAt) }}</time>
              </div>
              <p>{{ answer.content }}</p>
            </article>
          </section>
          <p v-else class="question-row__pending">暂未收到教师回答</p>

          <footer v-if="isTeacher" class="question-row__actions">
            <el-button
              v-if="question.status !== 'CLOSED'"
              type="primary"
              plain
              :icon="EditPen"
              @click="openAnswerDialog(question)"
            >
              回答
            </el-button>
            <el-button
              v-if="question.status !== 'CLOSED'"
              type="danger"
              plain
              :icon="CloseBold"
              :loading="closingQuestionId === question.id"
              @click="closeQuestion(question)"
            >
              关闭
            </el-button>
          </footer>
        </article>
      </div>
    </section>

    <el-dialog
      v-model="askDialogVisible"
      title="提出问题"
      width="560px"
      destroy-on-close
      :close-on-click-modal="false"
      @closed="resetAskForm"
    >
      <el-form label-position="top">
        <el-form-item label="学习内容" required>
          <el-select
            v-model="askForm.publicationId"
            class="full-width"
            :loading="learningTasksLoading"
            filterable
            placeholder="选择要提问的学习内容"
            no-data-text="暂无可提问的已发布内容"
          >
            <el-option
              v-for="task in learningTasks"
              :key="task.publicationId"
              :label="learningTaskLabel(task)"
              :value="task.publicationId"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="问题标题" required>
          <el-input v-model="askForm.title" maxlength="200" show-word-limit />
        </el-form-item>
        <el-form-item label="问题内容" required>
          <el-input
            v-model="askForm.content"
            type="textarea"
            :rows="5"
            maxlength="5000"
            show-word-limit
            resize="vertical"
          />
        </el-form-item>
        <el-alert v-if="askError" type="error" :title="askError" show-icon :closable="false" />
      </el-form>
      <template #footer>
        <el-button :disabled="asking" @click="askDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="asking" @click="submitQuestion">提交问题</el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="answerDialogVisible"
      title="回答问题"
      width="560px"
      destroy-on-close
      :close-on-click-modal="false"
      @closed="resetAnswerForm"
    >
      <div v-if="answerTarget" class="answer-target">
        <strong>{{ answerTarget.title }}</strong>
        <p>{{ answerTarget.content }}</p>
      </div>
      <el-form label-position="top">
        <el-form-item label="回答内容" required>
          <el-input
            v-model="answerContent"
            type="textarea"
            :rows="5"
            maxlength="5000"
            show-word-limit
            resize="vertical"
          />
        </el-form-item>
        <el-alert v-if="answerError" type="error" :title="answerError" show-icon :closable="false" />
      </el-form>
      <template #footer>
        <el-button :disabled="answering" @click="answerDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="answering" @click="submitAnswer">提交回答</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<script setup lang="ts">
import {
  createQuestion,
  createQuestionAnswer,
  listQuestions,
  updateQuestionStatus,
  type Question,
  type QuestionStatus,
} from '@/api/questions';
import { listLearningTasks, type LearningTaskSummary } from '@/api/publications';
import PageHeader from '@/components/PageHeader.vue';
import StatePanel from '@/components/StatePanel.vue';
import { useAuthStore } from '@/stores/auth';
import { formatFullDateTime } from '@/utils/presentation';
import { ChatDotRound, CloseBold, EditPen, Plus, Refresh, User } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { computed, ref, watch } from 'vue';
import { useRoute } from 'vue-router';

type StatusFilter = 'ALL' | QuestionStatus;
type TagType = 'primary' | 'success' | 'warning' | 'info';

const auth = useAuthStore();
const route = useRoute();
const questions = ref<Question[]>([]);
const loading = ref(false);
const errorMessage = ref('');
const statusFilter = ref<StatusFilter>('ALL');
const publicationFilter = ref<number | undefined>();
const askDialogVisible = ref(false);
const answerDialogVisible = ref(false);
const asking = ref(false);
const answering = ref(false);
const closingQuestionId = ref<number | null>(null);
const askError = ref('');
const answerError = ref('');
const answerTarget = ref<Question | null>(null);
const answerContent = ref('');
const askForm = ref({ publicationId: undefined as number | undefined, title: '', content: '' });
const learningTasks = ref<LearningTaskSummary[]>([]);
const learningTasksLoading = ref(false);
const learningTasksError = ref('');
let requestSequence = 0;
let learningTaskSequence = 0;

const isStudent = computed(() => auth.activeRole === 'STUDENT');
const isTeacher = computed(() => auth.activeRole === 'TEACHER');
const isLeader = computed(() => auth.activeRole === 'LEADER');
const canAccess = computed(() => isStudent.value || isTeacher.value || isLeader.value);
const roleLabels = {
  STUDENT: '学生',
  TEACHER: '教师',
  LEADER: '教研负责人',
} as const;
const roleLabel = computed(() => auth.activeRole ? roleLabels[auth.activeRole] : '未登录');
const pageTitle = computed(() => {
  if (isStudent.value) return '我的问答';
  if (isTeacher.value) return '项目问题';
  if (isLeader.value) return '发布问答概览';
  return '问答中心';
});
const pageDescription = computed(() => {
  if (isStudent.value) return '查看自己的提问与教师回答。';
  if (isTeacher.value) return '处理归属项目中的学生问题。';
  if (isLeader.value) return '只读查看自己发布范围内的问答。';
  return '按当前身份查看授权范围内的问答。';
});
const loadingDescription = computed(() => isStudent.value
  ? '正在读取你的问题与回答。'
  : '正在读取当前授权范围内的问题。');
const emptyDescription = computed(() => {
  if (isStudent.value) return '还没有符合筛选条件的问题。';
  if (isTeacher.value) return '当前项目范围内没有符合筛选条件的问题。';
  return '当前发布范围内没有符合筛选条件的问题。';
});

watch(
  () => auth.activeRole,
  (role) => {
    requestSequence += 1;
    learningTaskSequence += 1;
    questions.value = [];
    errorMessage.value = '';
    publicationFilter.value = undefined;
    resetAskForm();
    if (role === 'STUDENT') {
      void loadLearningTasks();
      return;
    }
    learningTasks.value = [];
    learningTasksLoading.value = false;
    learningTasksError.value = '';
    void loadQuestions();
  },
  { immediate: true },
);

watch([statusFilter, publicationFilter], () => {
  if (!canAccess.value || (isStudent.value && (learningTasksLoading.value || learningTasksError.value || learningTasks.value.length === 0))) return;
  void loadQuestions();
});

watch(() => route.query.publicationId, () => {
  if (!isStudent.value || learningTasks.value.length === 0) return;
  syncLearningTaskSelection();
});

async function loadLearningTasks() {
  if (!isStudent.value) return;
  const sequence = ++learningTaskSequence;
  learningTasksLoading.value = true;
  learningTasksError.value = '';
  try {
    const result = await listLearningTasks();
    if (sequence === learningTaskSequence) {
      learningTasks.value = result;
      syncLearningTaskSelection();
    }
  } catch (error) {
    if (sequence === learningTaskSequence) {
      learningTasks.value = [];
      learningTasksError.value = resolveError(error, '暂时无法读取已发布学习内容，请稍后重试。');
    }
  } finally {
    if (sequence === learningTaskSequence) {
      learningTasksLoading.value = false;
      if (!learningTasksError.value && learningTasks.value.length > 0) void loadQuestions();
    }
  }
}

function handleRefresh() {
  if (isStudent.value) {
    void loadLearningTasks();
    return;
  }
  void loadQuestions();
}

async function loadQuestions() {
  if (!canAccess.value) {
    requestSequence += 1;
    questions.value = [];
    loading.value = false;
    errorMessage.value = '';
    return;
  }

  if (isStudent.value && (learningTasksLoading.value || learningTasksError.value || learningTasks.value.length === 0)) {
    questions.value = [];
    loading.value = false;
    return;
  }

  const requestId = ++requestSequence;
  loading.value = true;
  errorMessage.value = '';
  try {
    const result = await listQuestions({
      publicationId: publicationFilter.value,
      status: statusFilter.value === 'ALL' ? undefined : statusFilter.value,
    });
    if (requestId === requestSequence) questions.value = result;
  } catch (error) {
    if (requestId === requestSequence) {
      errorMessage.value = resolveError(error, '暂时无法读取问答，请稍后重试。');
    }
  } finally {
    if (requestId === requestSequence) loading.value = false;
  }
}

function openAskDialog() {
  askError.value = '';
  askForm.value.publicationId = learningTasks.value.some((task) => task.publicationId === publicationFilter.value)
    ? publicationFilter.value
    : learningTasks.value[0]?.publicationId;
  askDialogVisible.value = true;
}

function openAnswerDialog(question: Question) {
  answerTarget.value = question;
  answerContent.value = '';
  answerError.value = '';
  answerDialogVisible.value = true;
}

async function submitQuestion() {
  const publicationId = askForm.value.publicationId;
  const visibleTask = learningTasks.value.find((task) => task.publicationId === publicationId);
  if (!visibleTask || !askForm.value.title.trim() || !askForm.value.content.trim()) {
    askError.value = '请选择本人可见的学习内容，并填写问题标题和问题内容。';
    return;
  }
  const selectedPublicationId = visibleTask.publicationId;

  asking.value = true;
  askError.value = '';
  try {
    const created = await createQuestion({
      publicationId: selectedPublicationId,
      title: askForm.value.title.trim(),
      content: askForm.value.content.trim(),
    });
    askDialogVisible.value = false;
    if (matchesCurrentFilter(created)) questions.value = [created, ...questions.value];
    ElMessage.success('问题已提交');
  } catch (error) {
    askError.value = resolveError(error, '提交问题失败，请稍后重试。');
  } finally {
    asking.value = false;
  }
}

async function submitAnswer() {
  if (!answerTarget.value || !answerContent.value.trim()) {
    answerError.value = '请填写回答内容。';
    return;
  }

  answering.value = true;
  answerError.value = '';
  try {
    const updated = await createQuestionAnswer(answerTarget.value.id, { content: answerContent.value.trim() });
    replaceQuestion(updated);
    answerDialogVisible.value = false;
    ElMessage.success('回答已提交');
  } catch (error) {
    answerError.value = resolveError(error, '提交回答失败，请稍后重试。');
  } finally {
    answering.value = false;
  }
}

async function closeQuestion(question: Question) {
  try {
    await ElMessageBox.confirm(`确认关闭“${question.title}”吗？`, '关闭问题', {
      confirmButtonText: '关闭问题',
      cancelButtonText: '取消',
      type: 'warning',
    });
  } catch {
    return;
  }

  closingQuestionId.value = question.id;
  try {
    const updated = await updateQuestionStatus(question.id, { status: 'CLOSED' });
    replaceQuestion(updated);
    ElMessage.success('问题已关闭');
  } catch (error) {
    ElMessage.error(resolveError(error, '关闭问题失败，请稍后重试。'));
  } finally {
    closingQuestionId.value = null;
  }
}

function replaceQuestion(updated: Question) {
  if (!matchesCurrentFilter(updated)) {
    questions.value = questions.value.filter((question) => question.id !== updated.id);
    return;
  }
  questions.value = questions.value.map((question) => question.id === updated.id ? updated : question);
}

function matchesCurrentFilter(question: Question) {
  return (publicationFilter.value == null || question.publicationId === publicationFilter.value)
    && (statusFilter.value === 'ALL' || question.status === statusFilter.value);
}

function syncLearningTaskSelection() {
  if (!isStudent.value) return;
  const queryPublicationId = parsePublicationId(route.query.publicationId);
  const currentIsVisible = learningTasks.value.some((task) => task.publicationId === publicationFilter.value);
  const queryIsVisible = learningTasks.value.some((task) => task.publicationId === queryPublicationId);
  publicationFilter.value = queryIsVisible
    ? queryPublicationId
    : currentIsVisible
      ? publicationFilter.value
      : learningTasks.value[0]?.publicationId;
}

function parsePublicationId(value: unknown) {
  const raw = Array.isArray(value) ? value[0] : value;
  if (typeof raw !== 'string' || !/^\d+$/.test(raw)) return undefined;
  const parsed = Number(raw);
  return Number.isSafeInteger(parsed) && parsed > 0 ? parsed : undefined;
}

function learningTaskLabel(task: LearningTaskSummary) {
  return `${task.title} · ${task.courseName} · ${task.className}`;
}

function learningTaskName(publicationId: number) {
  const task = learningTasks.value.find((item) => item.publicationId === publicationId);
  return task ? `学习内容：${task.title}` : '当前学习内容';
}

function resetAskForm() {
  askForm.value = { publicationId: undefined, title: '', content: '' };
  askError.value = '';
}

function resetAnswerForm() {
  answerTarget.value = null;
  answerContent.value = '';
  answerError.value = '';
}

function statusLabel(status: QuestionStatus) {
  return ({ OPEN: '待回答', ANSWERED: '已回答', CLOSED: '已关闭' })[status];
}

function statusTagType(status: QuestionStatus): TagType {
  if (status === 'ANSWERED') return 'success';
  if (status === 'CLOSED') return 'info';
  return 'warning';
}

function resolveError(error: unknown, fallback: string) {
  const message = (error as { response?: { data?: { message?: string } } }).response?.data?.message;
  return message && !/(Exception|java\.|Axios)/i.test(message) ? message : fallback;
}
</script>

<style scoped>
.question-center {
  min-width: 0;
}

.role-context {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: var(--color-text-secondary);
  font-size: 13px;
}

.question-panel {
  overflow: hidden;
}

.question-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 16px 20px;
  border-bottom: 1px solid var(--color-border);
}

.status-filter {
  max-width: 100%;
  overflow-x: auto;
}

.question-toolbar__right {
  display: flex;
  flex: 0 0 auto;
  align-items: center;
  gap: 12px;
}

.question-toolbar__right :deep(.el-input-number) {
  width: 124px;
}

.question-learning-select {
  width: min(100%, 300px);
}

.full-width {
  width: 100%;
}

.question-toolbar__count {
  color: var(--color-text-muted);
  font-size: 13px;
  white-space: nowrap;
}

.question-panel__alert {
  margin: 16px 20px 0;
}

.question-list {
  margin: 0;
}

.question-row {
  padding: 20px;
  border-bottom: 1px solid var(--color-border);
}

.question-row:last-child {
  border-bottom: 0;
}

.question-row__heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 20px;
}

.question-row__identity {
  min-width: 0;
}

.question-row__title-line {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.question-row__title-line h2 {
  margin: 0;
  color: var(--color-text);
  font-size: 16px;
  line-height: 1.45;
  overflow-wrap: anywhere;
}

.question-row__identity p {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 12px;
  margin: 7px 0 0;
  color: var(--color-text-muted);
  font-size: 12px;
}

.question-row__heading > time {
  flex: 0 0 auto;
  color: var(--color-text-muted);
  font-size: 12px;
  white-space: nowrap;
}

.question-row__content {
  margin: 16px 0 0;
  color: var(--color-text-secondary);
  font-size: 14px;
  line-height: 1.7;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

.answer-list {
  display: grid;
  gap: 10px;
  margin-top: 16px;
}

.answer-item {
  padding: 13px 14px;
  border-left: 3px solid var(--color-success);
  background: var(--color-success-soft);
}

.answer-item__meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 7px;
  color: var(--color-text-secondary);
  font-size: 12px;
}

.answer-item__meta .el-icon {
  color: var(--color-success);
}

.answer-item__meta strong {
  color: var(--color-text);
}

.answer-item__meta time {
  color: var(--color-text-muted);
}

.answer-item p {
  margin: 8px 0 0;
  color: var(--color-text-secondary);
  font-size: 13px;
  line-height: 1.65;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

.question-row__pending {
  margin: 16px 0 0;
  color: var(--color-text-muted);
  font-size: 13px;
}

.question-row__actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 18px;
}

.question-row__actions .el-button + .el-button {
  margin-left: 0;
}

.answer-target {
  margin: -4px 0 18px;
  padding: 12px 14px;
  border-left: 3px solid var(--color-primary);
  background: var(--color-primary-soft);
}

.answer-target strong,
.answer-target p {
  display: block;
  margin: 0;
}

.answer-target strong {
  color: var(--color-text);
  font-size: 14px;
  overflow-wrap: anywhere;
}

.answer-target p {
  margin-top: 6px;
  color: var(--color-text-secondary);
  font-size: 13px;
  line-height: 1.6;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

@media (max-width: 720px) {
  .question-toolbar {
    align-items: stretch;
    flex-direction: column;
  }

  .question-toolbar__right {
    justify-content: space-between;
  }

  .question-row__heading {
    align-items: stretch;
    flex-direction: column;
    gap: 10px;
  }

  .question-row__heading > time {
    white-space: normal;
  }
}

@media (max-width: 480px) {
  .question-toolbar,
  .question-row {
    padding-right: 16px;
    padding-left: 16px;
  }

  .status-filter {
    width: 100%;
  }

  .question-toolbar__right :deep(.el-input-number) {
    width: min(100%, 160px);
  }

  .question-learning-select {
    width: 100%;
  }

  .question-row__actions {
    align-items: stretch;
    flex-direction: column;
  }

  .question-row__actions .el-button {
    width: 100%;
  }
}
</style>
