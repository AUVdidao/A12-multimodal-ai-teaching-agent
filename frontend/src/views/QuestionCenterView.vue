<template>
  <section class="page question-center">
    <PageHeader eyebrow="问答中心" :title="pageTitle" :description="pageDescription">
      <template #meta>
        <span class="role-context">
          <el-icon><User /></el-icon>
          当前身份：{{ roleLabel }}
        </span>
      </template>
      <template #actions>
        <el-tooltip content="刷新问答与可见范围" placement="bottom">
          <el-button
            class="question-refresh-action"
            circle
            :icon="Refresh"
            :loading="loading"
            aria-label="刷新问答与可见范围"
            @click="loadPage"
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
      v-else-if="loading && !loaded"
      type="loading"
      title="正在读取问答工作区"
      :description="loadingDescription"
    />
    <StatePanel
      v-else-if="errorMessage && !hasAnyContext && allQuestions.length === 0"
      type="error"
      title="问答工作区暂时不可用"
      :description="errorMessage"
    >
      <template #action>
        <el-button type="primary" :icon="Refresh" @click="loadPage">重新加载</el-button>
      </template>
    </StatePanel>

    <template v-else-if="canAccess">
      <el-alert
        v-if="errorMessage || contextError"
        class="question-alert"
        :type="errorMessage ? 'error' : 'warning'"
        :title="[errorMessage, contextError].filter(Boolean).join('；')"
        show-icon
        :closable="false"
      />

      <section class="question-summary" aria-label="问答摘要指标">
        <button
          v-for="metric in summaryMetrics"
          :key="metric.key"
          type="button"
          :class="['question-summary__item', { 'is-active': statusFilter === metric.status }]"
          :aria-pressed="statusFilter === metric.status"
          @click="statusFilter = metric.status"
        >
          <span :class="['question-summary__icon', `question-summary__icon--${metric.tone}`]">
            <el-icon><component :is="metric.icon" /></el-icon>
          </span>
          <span class="question-summary__copy">
            <small>{{ metric.label }}</small>
            <strong>{{ metric.value }}</strong>
            <span>{{ metric.description }}</span>
          </span>
        </button>
      </section>

      <section class="surface-panel question-scope" aria-labelledby="question-scope-heading">
        <header class="question-scope__header">
          <div>
            <h2 id="question-scope-heading">问答范围</h2>
            <p>{{ contextSummary }}</p>
          </div>
          <span>{{ visibleQuestions.length }} 条结果</span>
        </header>

        <div class="question-filters">
          <label class="question-filter">
            <span>教学项目</span>
            <el-select
              v-model="selectedProjectId"
              clearable
              filterable
              :loading="contextLoading"
              placeholder="全部项目"
              no-data-text="暂无可见项目"
              aria-label="按教学项目筛选"
            >
              <el-option
                v-for="project in projectOptions"
                :key="project.id"
                :label="project.label"
                :value="project.id"
              />
            </el-select>
          </label>

          <label class="question-filter">
            <span>{{ isStudent ? '学习内容' : '发布内容' }}</span>
            <el-select
              v-model="publicationFilter"
              clearable
              filterable
              :loading="contextLoading"
              :placeholder="isStudent ? '全部学习内容' : '全部发布内容'"
              :no-data-text="isStudent ? '暂无已发布学习内容' : '暂无可见发布记录'"
              aria-label="按发布内容筛选"
            >
              <el-option
                v-for="publication in filteredPublicationOptions"
                :key="publication.id"
                :label="publication.label"
                :value="publication.id"
              />
            </el-select>
          </label>

          <div class="question-filter question-filter--status">
            <span>问题状态</span>
            <el-radio-group v-model="statusFilter" class="status-filter" aria-label="按问题状态筛选">
              <el-radio-button value="ALL">全部</el-radio-button>
              <el-radio-button value="OPEN">待回答</el-radio-button>
              <el-radio-button value="ANSWERED">已回答</el-radio-button>
              <el-radio-button value="CLOSED">已关闭</el-radio-button>
            </el-radio-group>
          </div>
        </div>
      </section>

      <section class="surface-panel question-panel" v-loading="loading" aria-labelledby="question-list-heading">
        <header class="question-panel__header">
          <div>
            <h2 id="question-list-heading">问题列表</h2>
            <p>{{ listDescription }}</p>
          </div>
          <el-button v-if="hasActiveFilters" text type="primary" @click="clearFilters">清除筛选</el-button>
        </header>

        <div v-if="visibleQuestions.length === 0" class="question-empty">
          <span class="question-empty__icon"><el-icon><ChatDotRound /></el-icon></span>
          <div class="question-empty__content">
            <h3>{{ emptyTitle }}</h3>
            <p>{{ emptyDescription }}</p>
            <div class="question-empty__actions">
              <el-button
                v-if="emptyStateNeedsReload"
                type="primary"
                :icon="Refresh"
                @click="loadPage"
              >
                重新加载
              </el-button>
              <el-button
                v-else-if="contextualQuestions.length > 0 && statusFilter !== 'ALL'"
                type="primary"
                @click="statusFilter = 'ALL'"
              >
                查看全部状态
              </el-button>
              <el-button
                v-else-if="isStudent && learningTasks.length > 0"
                type="primary"
                :icon="Plus"
                @click="openAskDialog"
              >
                提出第一个问题
              </el-button>
              <el-button
                v-else-if="isTeacher && projectOptions.length === 0"
                type="primary"
                @click="goToRoute('project-create')"
              >
                新建教学项目
              </el-button>
              <el-button
                v-else
                type="primary"
                @click="goToPrimaryContext"
              >
                {{ primaryContextActionLabel }}
              </el-button>
            </div>
          </div>

          <ul v-if="guidanceItems.length > 0" class="question-empty__contexts" aria-label="可继续查看的真实内容">
            <li v-for="item in guidanceItems" :key="item.key">
              <div>
                <strong>{{ item.title }}</strong>
                <span>{{ item.description }}</span>
              </div>
              <button
                type="button"
                class="question-context-link"
                :aria-label="`进入${item.title}`"
                @click="openGuidanceItem(item)"
              >
                <span>进入</span>
                <el-icon><ArrowRight /></el-icon>
              </button>
            </li>
          </ul>
        </div>

        <div v-else class="question-list">
          <article v-for="question in pagedQuestions" :key="question.id" class="question-row">
            <header class="question-row__heading">
              <div class="question-row__identity">
                <div class="question-row__title-line">
                  <h3>{{ question.title }}</h3>
                  <el-tag :type="statusTagType(question.status)" effect="light" size="small">
                    {{ statusLabel(question.status) }}
                  </el-tag>
                </div>
                <p>
                  <span>{{ publicationName(question.publicationId) }}</span>
                  <span>{{ projectName(question.projectId) }}</span>
                  <span v-if="!isStudent">学生：{{ question.studentName }}</span>
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

        <footer v-if="visibleQuestions.length > questionPageSize" class="question-pagination">
          <span>共 {{ visibleQuestions.length }} 条</span>
          <el-pagination
            v-model:current-page="questionPage"
            background
            :page-size="questionPageSize"
            :total="visibleQuestions.length"
            layout="prev, pager, next"
          />
        </footer>
      </section>
    </template>

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
            :loading="contextLoading"
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
import { listProjects, type TeachingProject } from '@/api/projects';
import {
  createQuestion,
  createQuestionAnswer,
  listQuestions,
  updateQuestionStatus,
  type Question,
  type QuestionStatus,
} from '@/api/questions';
import {
  listLearningTasks,
  listPublications,
  type LearningTaskSummary,
  type Publication,
} from '@/api/publications';
import PageHeader from '@/components/PageHeader.vue';
import StatePanel from '@/components/StatePanel.vue';
import { useAuthStore } from '@/stores/auth';
import { formatFullDateTime } from '@/utils/presentation';
import {
  ArrowRight,
  ChatDotRound,
  CircleCheck,
  CloseBold,
  EditPen,
  Plus,
  Refresh,
  User,
  WarningFilled,
} from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { computed, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';

type StatusFilter = 'ALL' | QuestionStatus;
type TagType = 'primary' | 'success' | 'warning' | 'info';
type MetricTone = 'neutral' | 'warning' | 'success' | 'muted';

interface ContextProject {
  id: number;
  label: string;
  description: string;
}

interface ContextPublication {
  id: number;
  projectId: number;
  label: string;
  description: string;
  publishedAt: string;
}

interface GuidanceItem {
  key: string;
  title: string;
  description: string;
  routeName: string;
  params?: Record<string, number>;
  query?: Record<string, number>;
}

const auth = useAuthStore();
const route = useRoute();
const router = useRouter();
const allQuestions = ref<Question[]>([]);
const projects = ref<TeachingProject[]>([]);
const publications = ref<Publication[]>([]);
const learningTasks = ref<LearningTaskSummary[]>([]);
const loading = ref(false);
const contextLoading = ref(false);
const loaded = ref(false);
const errorMessage = ref('');
const contextError = ref('');
const statusFilter = ref<StatusFilter>('ALL');
const selectedProjectId = ref<number | undefined>();
const publicationFilter = ref<number | undefined>();
const questionPage = ref(1);
const questionPageSize = 6;
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
let requestSequence = 0;

const isStudent = computed(() => auth.activeRole === 'STUDENT');
const isTeacher = computed(() => auth.activeRole === 'TEACHER');
const isLeader = computed(() => auth.activeRole === 'LEADER');
const canAccess = computed(() => isStudent.value || isTeacher.value || isLeader.value);
const roleLabels = { STUDENT: '学生', TEACHER: '教师', LEADER: '教研负责人' } as const;
const roleLabel = computed(() => auth.activeRole ? roleLabels[auth.activeRole] : '未登录');
const pageTitle = computed(() => {
  if (isStudent.value) return '我的问答';
  if (isTeacher.value) return '项目问题';
  if (isLeader.value) return '发布问答概览';
  return '问答中心';
});
const pageDescription = computed(() => {
  if (isStudent.value) return '按已发布学习内容查看自己的提问与教师回答。';
  if (isTeacher.value) return '按真实项目和发布内容筛选，集中处理学生问题。';
  if (isLeader.value) return '按负责范围内的真实发布记录只读巡查学生问答。';
  return '按当前身份查看授权范围内的问答。';
});
const loadingDescription = computed(() => isStudent.value
  ? '正在读取你的学习内容、提问与教师回答。'
  : '正在读取可见项目、发布记录和学生问题。');

const projectOptions = computed<ContextProject[]>(() => {
  const result = new Map<number, ContextProject>();
  projects.value.forEach((project) => result.set(project.id, {
    id: project.id,
    label: project.projectName,
    description: `${project.courseName} · ${project.chapterTitle}`,
  }));
  publications.value.forEach((publication) => {
    if (!result.has(publication.projectId)) result.set(publication.projectId, {
      id: publication.projectId,
      label: publication.projectName,
      description: publication.courseName,
    });
  });
  learningTasks.value.forEach((task) => {
    if (!result.has(task.projectId)) result.set(task.projectId, {
      id: task.projectId,
      label: task.projectName,
      description: `${task.courseName} · ${task.className}`,
    });
  });
  return [...result.values()].sort((left, right) => left.label.localeCompare(right.label, 'zh-CN'));
});

const publicationOptions = computed<ContextPublication[]>(() => {
  if (isStudent.value) {
    return learningTasks.value.map((task) => ({
      id: task.publicationId,
      projectId: task.projectId,
      label: task.title,
      description: `${task.courseName} · ${task.className}`,
      publishedAt: task.publishedAt,
    })).sort(sortPublicationContext);
  }
  return publications.value.map((publication) => ({
    id: publication.id,
    projectId: publication.projectId,
    label: publication.title,
    description: `${publication.projectName} · ${publication.className} · ${publication.status === 'PUBLISHED' ? '发布中' : '已撤回'}`,
    publishedAt: publication.publishedAt,
  })).sort(sortPublicationContext);
});

const filteredPublicationOptions = computed(() => selectedProjectId.value == null
  ? publicationOptions.value
  : publicationOptions.value.filter((item) => item.projectId === selectedProjectId.value));

const contextualQuestions = computed(() => allQuestions.value.filter((question) => (
  (selectedProjectId.value == null || question.projectId === selectedProjectId.value)
  && (publicationFilter.value == null || question.publicationId === publicationFilter.value)
)));
const visibleQuestions = computed(() => contextualQuestions.value
  .filter((question) => statusFilter.value === 'ALL' || question.status === statusFilter.value)
  .sort((left, right) => toTimestamp(right.updatedAt) - toTimestamp(left.updatedAt)));
const pagedQuestions = computed(() => {
  const start = (questionPage.value - 1) * questionPageSize;
  return visibleQuestions.value.slice(start, start + questionPageSize);
});
const summaryMetrics = computed(() => {
  const scoped = contextualQuestions.value;
  return [
    metric('all', '当前范围', scoped.length, '全部问题', 'ALL', ChatDotRound, 'neutral'),
    metric('open', '待回答', scoped.filter((item) => item.status === 'OPEN').length, '需要教师跟进', 'OPEN', WarningFilled, 'warning'),
    metric('answered', '已回答', scoped.filter((item) => item.status === 'ANSWERED').length, '已有教师回复', 'ANSWERED', CircleCheck, 'success'),
    metric('closed', '已关闭', scoped.filter((item) => item.status === 'CLOSED').length, '已结束处理', 'CLOSED', CloseBold, 'muted'),
  ];
});
const hasAnyContext = computed(() => projectOptions.value.length > 0 || publicationOptions.value.length > 0);
const hasActiveFilters = computed(() => selectedProjectId.value != null
  || publicationFilter.value != null
  || statusFilter.value !== 'ALL');
const emptyStateNeedsReload = computed(() => (
  (Boolean(errorMessage.value) && allQuestions.value.length === 0)
  || (Boolean(contextError.value) && publicationOptions.value.length === 0)
));
const contextSummary = computed(() => `${projectOptions.value.length} 个可见项目 · ${publicationOptions.value.length} 条真实发布内容`);
const listDescription = computed(() => hasActiveFilters.value
  ? '当前列表按项目、发布内容和状态筛选。'
  : '按最近更新时间展示当前身份可见的问题。');
const emptyTitle = computed(() => {
  if (emptyStateNeedsReload.value) {
    if (errorMessage.value && allQuestions.value.length === 0) return '问题列表暂时不可用';
    return isStudent.value ? '学习内容暂时无法读取' : '发布范围暂时无法确认';
  }
  if (contextualQuestions.value.length > 0 && statusFilter.value !== 'ALL') return '当前状态筛选没有问题';
  if (isStudent.value && learningTasks.value.length === 0) return '暂无可提问的已发布内容';
  if (isTeacher.value && projectOptions.value.length === 0) return '还没有可承载问答的教学项目';
  if (!isStudent.value && publicationOptions.value.length === 0) return '当前项目尚未形成发布内容';
  return isStudent.value ? '还没有提问记录' : '真实发布范围内暂时没有学生提问';
});
const emptyDescription = computed(() => {
  if (emptyStateNeedsReload.value) {
    return '当前不会把接口失败解释为零条业务数据；重新加载后可继续查看真实范围。';
  }
  if (contextualQuestions.value.length > 0 && statusFilter.value !== 'ALL') return '切换问题状态即可查看当前范围内的其他记录。';
  if (isStudent.value && learningTasks.value.length === 0) return '教师发布学习内容后，你可以从学习详情针对具体内容提问。';
  if (isTeacher.value && projectOptions.value.length === 0) return '先创建教学项目并完成内容生产，后续发布后可在这里处理学生问题。';
  if (!isStudent.value && publicationOptions.value.length === 0) return '先查看真实项目或发布记录，问答只会在学生实际提问后出现。';
  return isStudent.value
    ? '选择一条本人可见的学习内容提出问题，提交后会显示在这里。'
    : '这里不会生成演示问题；学生基于已发布内容实际提问后才会出现记录。';
});
const primaryContextActionLabel = computed(() => {
  if (isStudent.value) return '查看学习内容';
  if (isLeader.value) return '查看发布记录';
  return '查看教学项目';
});
const guidanceItems = computed<GuidanceItem[]>(() => {
  const publicationItems = filteredPublicationOptions.value.slice(0, 3).map((item) => ({
    key: `publication:${item.id}`,
    title: item.label,
    description: item.description,
    routeName: isStudent.value ? 'student-learning' : isLeader.value ? 'leader-publications' : 'teacher-publications',
    query: isStudent.value ? { publicationId: item.id } : undefined,
  }));
  if (publicationItems.length > 0) return publicationItems;
  if (!isTeacher.value) return [];
  return projectOptions.value.slice(0, 3).map((item) => ({
    key: `project:${item.id}`,
    title: item.label,
    description: item.description,
    routeName: 'project-overview',
    params: { projectId: item.id },
  }));
});

watch(
  () => auth.activeRole,
  () => {
    requestSequence += 1;
    resetPageData();
    if (canAccess.value) void loadPage();
  },
  { immediate: true },
);

watch(selectedProjectId, () => {
  if (publicationFilter.value != null
    && !filteredPublicationOptions.value.some((item) => item.id === publicationFilter.value)) {
    publicationFilter.value = undefined;
  }
  questionPage.value = 1;
});
watch([publicationFilter, statusFilter], () => { questionPage.value = 1; });
watch(() => visibleQuestions.value.length, (length) => {
  const maxPage = Math.max(1, Math.ceil(length / questionPageSize));
  if (questionPage.value > maxPage) questionPage.value = maxPage;
});
watch(() => route.query.publicationId, () => {
  if (isStudent.value && learningTasks.value.length > 0) syncRoutePublication();
});

async function loadPage() {
  if (!canAccess.value) return;
  const requestId = ++requestSequence;
  loading.value = true;
  contextLoading.value = true;
  errorMessage.value = '';
  contextError.value = '';

  try {
    if (isStudent.value) {
      const [questionResult, learningResult] = await Promise.allSettled([listQuestions(), listLearningTasks()]);
      if (requestId !== requestSequence) return;
      applyQuestionResult(questionResult);
      if (learningResult.status === 'fulfilled') {
        learningTasks.value = learningResult.value;
        syncRoutePublication();
      } else {
        contextError.value = '已发布学习内容读取失败，暂时无法选择新的提问范围。';
      }
    } else if (isTeacher.value) {
      const [questionResult, projectResult, publicationResult] = await Promise.allSettled([
        listQuestions(),
        listProjects(),
        listPublications(),
      ]);
      if (requestId !== requestSequence) return;
      applyQuestionResult(questionResult);
      const failed: string[] = [];
      if (projectResult.status === 'fulfilled') projects.value = projectResult.value;
      else failed.push('教学项目');
      if (publicationResult.status === 'fulfilled') publications.value = publicationResult.value;
      else failed.push('发布记录');
      if (failed.length > 0) contextError.value = `${failed.join('、')}读取失败，筛选范围可能不完整。`;
    } else {
      const [questionResult, publicationResult] = await Promise.allSettled([listQuestions(), listPublications()]);
      if (requestId !== requestSequence) return;
      applyQuestionResult(questionResult);
      if (publicationResult.status === 'fulfilled') publications.value = publicationResult.value;
      else contextError.value = '负责范围内的发布记录读取失败，筛选范围可能不完整。';
    }
  } finally {
    if (requestId === requestSequence) {
      loading.value = false;
      contextLoading.value = false;
      loaded.value = true;
    }
  }
}

function applyQuestionResult(result: PromiseSettledResult<Question[]>) {
  if (result.status === 'fulfilled') allQuestions.value = result.value;
  else errorMessage.value = '问题列表读取失败，请稍后重试。';
}

function metric(
  key: string,
  label: string,
  value: number,
  description: string,
  status: StatusFilter,
  icon: typeof ChatDotRound,
  tone: MetricTone,
) {
  return { key, label, value, description, status, icon, tone };
}

function clearFilters() {
  selectedProjectId.value = undefined;
  publicationFilter.value = undefined;
  statusFilter.value = 'ALL';
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

  asking.value = true;
  askError.value = '';
  try {
    const created = await createQuestion({
      publicationId: visibleTask.publicationId,
      title: askForm.value.title.trim(),
      content: askForm.value.content.trim(),
    });
    allQuestions.value = [created, ...allQuestions.value.filter((item) => item.id !== created.id)];
    selectedProjectId.value = created.projectId;
    publicationFilter.value = created.publicationId;
    statusFilter.value = 'ALL';
    askDialogVisible.value = false;
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
    replaceQuestion(await updateQuestionStatus(question.id, { status: 'CLOSED' }));
    ElMessage.success('问题已关闭');
  } catch (error) {
    ElMessage.error(resolveError(error, '关闭问题失败，请稍后重试。'));
  } finally {
    closingQuestionId.value = null;
  }
}

function replaceQuestion(updated: Question) {
  allQuestions.value = allQuestions.value.map((question) => question.id === updated.id ? updated : question);
}

function syncRoutePublication() {
  const queryPublicationId = parsePublicationId(route.query.publicationId);
  const task = learningTasks.value.find((item) => item.publicationId === queryPublicationId);
  if (!task) return;
  selectedProjectId.value = task.projectId;
  publicationFilter.value = task.publicationId;
}

function goToPrimaryContext() {
  if (isStudent.value) goToRoute('student-learning');
  else if (isLeader.value) goToRoute('leader-publications');
  else goToRoute('projects');
}

function openGuidanceItem(item: GuidanceItem) {
  void router.push({ name: item.routeName, params: item.params, query: item.query });
}

function goToRoute(name: string) {
  void router.push({ name });
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

function projectName(projectId: number) {
  return projectOptions.value.find((item) => item.id === projectId)?.label || `项目 #${projectId}`;
}

function publicationName(publicationId: number) {
  return publicationOptions.value.find((item) => item.id === publicationId)?.label || `发布 #${publicationId}`;
}

function resetPageData() {
  allQuestions.value = [];
  projects.value = [];
  publications.value = [];
  learningTasks.value = [];
  selectedProjectId.value = undefined;
  publicationFilter.value = undefined;
  statusFilter.value = 'ALL';
  questionPage.value = 1;
  loading.value = false;
  contextLoading.value = false;
  loaded.value = false;
  errorMessage.value = '';
  contextError.value = '';
  resetAskForm();
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

function sortPublicationContext(left: ContextPublication, right: ContextPublication) {
  return toTimestamp(right.publishedAt) - toTimestamp(left.publishedAt);
}

function toTimestamp(value?: string | null) {
  const timestamp = value ? new Date(value).getTime() : 0;
  return Number.isNaN(timestamp) ? 0 : timestamp;
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

.question-alert {
  margin-bottom: 16px;
}

.question-summary {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
  margin-bottom: 16px;
}

.question-summary__item {
  display: grid;
  grid-template-columns: 38px minmax(0, 1fr);
  align-items: center;
  gap: 11px;
  min-width: 0;
  min-height: 98px;
  padding: 14px 15px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: var(--color-surface);
  color: inherit;
  font: inherit;
  text-align: left;
  cursor: pointer;
  transition: border-color 160ms ease, box-shadow 160ms ease, transform 160ms ease;
}

.question-summary__item:hover,
.question-summary__item.is-active {
  border-color: var(--color-primary);
  box-shadow: var(--shadow-card);
}

.question-summary__item:focus-visible {
  outline: 2px solid var(--color-primary);
  outline-offset: 2px;
}

.question-summary__icon {
  display: grid;
  width: 38px;
  height: 38px;
  place-items: center;
  border-radius: 8px;
  background: var(--color-primary-soft);
  color: var(--color-primary);
  font-size: 19px;
}

.question-summary__icon--warning { background: var(--color-warning-soft); color: var(--color-warning); }
.question-summary__icon--success { background: var(--color-success-soft); color: var(--color-success); }
.question-summary__icon--muted { background: var(--color-surface-subtle); color: var(--color-text-muted); }

.question-summary__copy {
  display: grid;
  min-width: 0;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 4px 8px;
}

.question-summary__copy small,
.question-summary__copy span {
  overflow: hidden;
  color: var(--color-text-muted);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.question-summary__copy strong {
  grid-row: 1 / span 2;
  grid-column: 2;
  align-self: center;
  color: var(--color-text);
  font-size: 26px;
  line-height: 1;
}

.question-scope,
.question-panel {
  overflow: hidden;
}

.question-scope {
  margin-bottom: 16px;
}

.question-scope__header,
.question-panel__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  padding: 15px 20px;
  border-bottom: 1px solid var(--color-border);
}

.question-scope__header h2,
.question-scope__header p,
.question-panel__header h2,
.question-panel__header p {
  margin: 0;
}

.question-scope__header h2,
.question-panel__header h2 {
  color: var(--color-text);
  font-size: 16px;
}

.question-scope__header p,
.question-panel__header p,
.question-scope__header > span {
  margin-top: 4px;
  color: var(--color-text-muted);
  font-size: 12px;
}

.question-scope__header > span {
  flex: 0 0 auto;
  white-space: nowrap;
}

.question-filters {
  display: grid;
  grid-template-columns: minmax(180px, 0.8fr) minmax(220px, 1fr) minmax(330px, 1.3fr);
  align-items: end;
  gap: 14px;
  padding: 16px 20px 18px;
}

.question-filter {
  display: grid;
  min-width: 0;
  gap: 7px;
}

.question-filter > span {
  color: var(--color-text-secondary);
  font-size: 12px;
  font-weight: 700;
}

.question-filter :deep(.el-select) {
  width: 100%;
}

.status-filter {
  max-width: 100%;
  overflow-x: auto;
}

.question-empty {
  display: grid;
  grid-template-columns: 48px minmax(220px, 0.8fr) minmax(280px, 1.2fr);
  align-items: start;
  gap: 18px;
  padding: 28px 20px;
}

.question-empty__icon {
  display: grid;
  width: 46px;
  height: 46px;
  place-items: center;
  border-radius: 8px;
  background: var(--color-primary-soft);
  color: var(--color-primary);
  font-size: 22px;
}

.question-empty__content h3,
.question-empty__content p {
  margin: 0;
}

.question-empty__content h3 {
  color: var(--color-text);
  font-size: 16px;
}

.question-empty__content p {
  margin-top: 7px;
  color: var(--color-text-secondary);
  font-size: 13px;
  line-height: 1.65;
}

.question-empty__actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 14px;
}

.question-empty__contexts {
  margin: 0;
  padding: 0;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  list-style: none;
}

.question-empty__contexts li {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: center;
  gap: 12px;
  padding: 11px 13px;
  border-bottom: 1px solid var(--color-border);
}

.question-empty__contexts li:last-child {
  border-bottom: 0;
}

.question-empty__contexts li > div {
  display: grid;
  min-width: 0;
  gap: 4px;
}

.question-empty__contexts strong,
.question-empty__contexts span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.question-empty__contexts strong { color: var(--color-text); font-size: 13px; }
.question-empty__contexts span { color: var(--color-text-muted); font-size: 12px; }

.question-context-link {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
  min-height: 28px;
  padding: 3px 5px;
  border: 0;
  background: transparent;
  color: var(--color-text-secondary);
  font: inherit;
  font-size: 12px;
  cursor: pointer;
  transition: color 160ms ease;
}

.question-context-link:hover {
  color: var(--color-text);
}

.question-context-link:focus-visible {
  border-radius: 4px;
  outline: 2px solid var(--color-primary);
  outline-offset: 2px;
}

.question-context-link .el-icon {
  flex: 0 0 auto;
  font-size: 13px;
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

.question-row__title-line h3 {
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

.answer-item__meta .el-icon { color: var(--color-success); }
.answer-item__meta strong { color: var(--color-text); }
.answer-item__meta time { color: var(--color-text-muted); }
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

.question-row__actions .el-button + .el-button { margin-left: 0; }

.question-pagination {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 14px 20px;
  border-top: 1px solid var(--color-border);
}

.question-pagination > span {
  color: var(--color-text-muted);
  font-size: 12px;
}

.full-width {
  width: 100%;
}

.answer-target {
  margin: -4px 0 18px;
  padding: 12px 14px;
  border-left: 3px solid var(--color-primary);
  background: var(--color-primary-soft);
}

.answer-target strong,
.answer-target p { display: block; margin: 0; }
.answer-target strong { color: var(--color-text); font-size: 14px; overflow-wrap: anywhere; }
.answer-target p {
  margin-top: 6px;
  color: var(--color-text-secondary);
  font-size: 13px;
  line-height: 1.6;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

@media (max-width: 1050px) {
  .question-summary { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .question-filters { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .question-filter--status { grid-column: 1 / -1; }
  .question-empty { grid-template-columns: 46px minmax(0, 1fr); }
  .question-empty__contexts { grid-column: 1 / -1; }
}

@media (max-width: 720px) {
  :deep(.page-heading__actions .question-refresh-action) {
    flex: 0 0 32px;
    width: 32px;
    height: 32px;
    margin-left: auto;
  }

  .question-scope__header,
  .question-panel__header {
    align-items: stretch;
    flex-direction: column;
  }

  .question-scope__header > span { margin-top: 0; }
  .question-filters { grid-template-columns: 1fr; }
  .question-filter--status { grid-column: auto; }
  .question-row__heading { align-items: stretch; flex-direction: column; gap: 10px; }
  .question-row__heading > time { white-space: normal; }
  .question-pagination { align-items: flex-start; flex-direction: column; }
}

@media (max-width: 520px) {
  .question-summary { gap: 8px; }
  .question-summary__item {
    grid-template-columns: 32px minmax(0, 1fr);
    gap: 8px;
    min-height: 86px;
    padding: 11px;
  }
  .question-summary__icon { width: 32px; height: 32px; font-size: 17px; }
  .question-summary__copy strong { font-size: 22px; }
  .question-summary__copy span { display: none; }
  .question-filters,
  .question-row,
  .question-empty,
  .question-scope__header,
  .question-panel__header { padding-right: 16px; padding-left: 16px; }
  .question-empty { grid-template-columns: 1fr; }
  .question-empty__contexts { grid-column: auto; }
  .question-empty__contexts li { align-items: stretch; grid-template-columns: 1fr; }
  .question-context-link { justify-self: start; }
  .question-row__actions { align-items: stretch; flex-direction: column; }
  .question-row__actions .el-button { width: 100%; }
  .question-pagination :deep(.el-pagination) { max-width: 100%; overflow-x: auto; }
}
</style>
