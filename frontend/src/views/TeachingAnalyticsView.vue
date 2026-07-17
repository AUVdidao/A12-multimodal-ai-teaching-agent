<template>
  <section class="page teaching-analytics-page">
    <PageHeader
      eyebrow="教学分析"
      title="我的教学分析"
      description="基于当前项目、任务、成果版本、发布与问答记录生成。"
    >
      <template #actions>
        <el-tooltip content="刷新分析" placement="bottom">
          <el-button circle :icon="Refresh" :loading="loading" aria-label="刷新教学分析" @click="loadAnalytics" />
        </el-tooltip>
      </template>
    </PageHeader>

    <StatePanel
      v-if="!isTeacher"
      type="error"
      title="当前身份无法查看教学分析"
      description="请切换为教师身份后查看自己项目范围内的分析。"
    />
    <StatePanel
      v-else-if="loading && !loaded"
      type="loading"
      title="正在汇总教学数据"
      description="正在读取项目、任务、成果版本、发布和问答记录。"
    />
    <StatePanel
      v-else-if="errorMessage && !hasData"
      type="error"
      title="教学分析暂时不可用"
      :description="errorMessage"
    >
      <template #action>
        <el-button type="primary" :icon="Refresh" @click="loadAnalytics">重新加载</el-button>
      </template>
    </StatePanel>

    <template v-else-if="isTeacher">
      <el-alert
        v-if="failedSources.length > 0"
        class="analytics-alert"
        type="warning"
        :title="`部分数据暂时不可用：${failedSources.join('、')}`"
        show-icon
        :closable="false"
      />

      <StatePanel
        v-if="loaded && !hasData"
        type="empty"
        title="还没有可分析的教学数据"
        description="先创建教学项目并推进真实备课流程，项目、任务、成果、发布和问答数据会自动汇总到这里。"
      >
        <template #action>
          <el-button type="primary" :icon="Plus" @click="goTo('project-create')">新建教学项目</el-button>
        </template>
      </StatePanel>

      <template v-else>
        <section class="surface-panel analytics-metrics" aria-label="教学指标">
          <article v-for="metric in metrics" :key="metric.key" class="analytics-metric">
            <span :class="['analytics-metric__icon', `analytics-metric__icon--${metric.tone}`]">
              <el-icon><component :is="metric.icon" /></el-icon>
            </span>
            <div class="analytics-metric__body">
              <span>{{ metric.label }}</span>
              <strong>{{ metric.value }}</strong>
              <small>{{ metric.description }}</small>
            </div>
            <button type="button" class="analytics-link" @click="goTo(metric.destination)">
              {{ metric.action }}
              <el-icon><ArrowRight /></el-icon>
            </button>
          </article>
        </section>

        <section class="analytics-grid">
          <section class="surface-panel analytics-section" aria-labelledby="project-status-heading">
            <header class="analytics-section__header">
              <div>
                <h2 id="project-status-heading">项目状态分布</h2>
                <p>按当前教师归属项目总数计算占比。</p>
              </div>
              <button type="button" class="analytics-link" @click="goTo('projects')">
                查看项目
                <el-icon><ArrowRight /></el-icon>
              </button>
            </header>
            <StatePanel
              v-if="failedSources.includes('教学项目')"
              class="analytics-section__state"
              type="error"
              title="项目数据暂时不可用"
              description="刷新后可重新汇总项目状态。"
            />
            <StatePanel
              v-else-if="projectStatusRows.length === 0"
              class="analytics-section__state"
              type="empty"
              title="暂无教学项目"
              description="创建项目后会按真实流程状态显示分布。"
            >
              <template #action>
                <el-button type="primary" plain :icon="Plus" @click="goTo('project-create')">新建项目</el-button>
              </template>
            </StatePanel>
            <div v-else class="distribution-list">
              <div v-for="item in projectStatusRows" :key="item.status" class="distribution-row">
                <div class="distribution-row__label">
                  <span>{{ projectStatusLabel(item.status) }}</span>
                  <small>{{ item.percentage }}%</small>
                </div>
                <el-progress
                  :percentage="item.percentage"
                  :show-text="false"
                  :stroke-width="8"
                  :color="projectStatusColor(item.status)"
                />
                <strong>{{ item.count }}</strong>
              </div>
            </div>
          </section>

          <section class="surface-panel analytics-section" aria-labelledby="pending-heading">
            <header class="analytics-section__header">
              <div>
                <h2 id="pending-heading">待处理事项</h2>
                <p>问题优先，其次是需修改任务与未定稿版本。</p>
              </div>
              <span class="analytics-section__count">{{ pendingItems.length }} 项</span>
            </header>
            <StatePanel
              v-if="pendingItems.length === 0"
              class="analytics-section__state"
              type="success"
              title="当前没有待处理事项"
              description="任务、学生问题和已有成果版本均没有新的积压。"
            />
            <div v-else class="bounded-list">
              <ul class="pending-list">
                <li v-for="item in pagedPendingItems" :key="item.key">
                  <el-tag :type="item.type" effect="light" size="small">{{ item.label }}</el-tag>
                  <div>
                    <strong>{{ item.title }}</strong>
                    <span>{{ item.description }}</span>
                  </div>
                  <button type="button" class="analytics-link" @click="goTo(item.destination)">
                    {{ item.action }}
                    <el-icon><ArrowRight /></el-icon>
                  </button>
                </li>
              </ul>
              <footer v-if="pendingItems.length > pendingPageSize" class="analytics-pagination">
                <span>第 {{ pendingPage }} / {{ pendingPageCount }} 页</span>
                <el-pagination
                  v-model:current-page="pendingPage"
                  size="small"
                  background
                  :page-size="pendingPageSize"
                  :total="pendingItems.length"
                  layout="prev, pager, next"
                />
              </footer>
            </div>
          </section>

          <section class="surface-panel analytics-section analytics-section--wide" aria-labelledby="artifact-heading">
            <header class="analytics-section__header">
              <div>
                <h2 id="artifact-heading">成果版本与发布</h2>
                <p>每个项目聚合最新成果版本与最近班级发布，避免记录无限散落。</p>
              </div>
              <div class="header-links">
                <button type="button" class="analytics-link" @click="goTo('teacher-publications')">
                  发布记录
                  <el-icon><ArrowRight /></el-icon>
                </button>
                <button type="button" class="analytics-link" @click="goTo('projects')">
                  教学项目
                  <el-icon><ArrowRight /></el-icon>
                </button>
              </div>
            </header>
            <StatePanel
              v-if="failedSources.includes('成果版本') || failedSources.includes('发布记录')"
              class="analytics-section__state"
              type="error"
              title="部分成果数据暂时不可用"
              description="刷新后可重新读取版本或发布记录。"
            />
            <StatePanel
              v-else-if="artifactRows.length === 0"
              class="analytics-section__state"
              type="empty"
              title="暂无成果版本或发布记录"
              description="进入真实教学项目生成并定稿成果后，这里会显示版本和发布状态。"
            >
              <template #action>
                <el-button type="primary" plain :icon="FolderOpened" @click="goTo('projects')">打开项目</el-button>
              </template>
            </StatePanel>
            <div v-else class="bounded-list">
              <div class="artifact-table" role="table" aria-label="成果版本与发布列表">
                <div class="artifact-table__header" role="row">
                  <span role="columnheader">教学项目</span>
                  <span role="columnheader">成果版本</span>
                  <span role="columnheader">最近发布</span>
                  <span role="columnheader">操作</span>
                </div>
                <article v-for="row in pagedArtifactRows" :key="row.key" class="artifact-row" role="row">
                  <div class="artifact-row__identity" role="cell">
                    <strong>{{ row.title }}</strong>
                    <span>{{ row.description }}</span>
                  </div>
                  <span class="artifact-row__fact" role="cell">{{ row.versionLabel }}</span>
                  <span class="artifact-row__fact" role="cell">{{ row.publicationLabel }}</span>
                  <button type="button" class="analytics-link" role="cell" @click="goToProject(row.projectId)">
                    打开项目
                    <el-icon><ArrowRight /></el-icon>
                  </button>
                </article>
              </div>
              <footer v-if="artifactRows.length > artifactPageSize" class="analytics-pagination">
                <span>共 {{ artifactRows.length }} 个项目</span>
                <el-pagination
                  v-model:current-page="artifactPage"
                  size="small"
                  background
                  :page-size="artifactPageSize"
                  :total="artifactRows.length"
                  layout="prev, pager, next"
                />
              </footer>
            </div>
          </section>
        </section>
      </template>
    </template>
  </section>
</template>

<script setup lang="ts">
import { loadTeacherAnalyticsData, type TeacherAnalyticsData } from '@/api/teachingAnalytics';
import PageHeader from '@/components/PageHeader.vue';
import StatePanel from '@/components/StatePanel.vue';
import { useAuthStore } from '@/stores/auth';
import { formatFullDateTime } from '@/utils/presentation';
import {
  ArrowRight,
  ChatDotRound,
  Document,
  FolderOpened,
  Plus,
  Promotion,
  Refresh,
} from '@element-plus/icons-vue';
import { computed, ref, watch } from 'vue';
import { useRouter } from 'vue-router';

type PendingTagType = 'warning' | 'danger' | 'info';
type MetricTone = 'blue' | 'amber' | 'green' | 'teal';

interface PendingItem {
  key: string;
  label: string;
  type: PendingTagType;
  title: string;
  description: string;
  action: string;
  destination: string;
  priority: number;
}

const auth = useAuthStore();
const router = useRouter();
const data = ref<TeacherAnalyticsData>(emptyData());
const loading = ref(false);
const loaded = ref(false);
const errorMessage = ref('');
const pendingPage = ref(1);
const artifactPage = ref(1);
const pendingPageSize = 5;
const artifactPageSize = 6;
let requestSequence = 0;

const isTeacher = computed(() => auth.activeRole === 'TEACHER');
const failedSources = computed(() => data.value.failedSources);
const hasData = computed(() => data.value.projects.length > 0
  || data.value.tasks.length > 0
  || data.value.publications.length > 0
  || data.value.questions.length > 0);
const pendingTasks = computed(() => data.value.tasks.filter((task) => ['ASSIGNED', 'IN_PROGRESS', 'REVISION_REQUIRED'].includes(task.taskStatus)));
const openQuestions = computed(() => data.value.questions.filter((question) => question.status === 'OPEN'));
const projectsWithoutFinalVersion = computed(() => data.value.projects.filter((project) => {
  const versions = data.value.versionsByProjectId.get(project.id) || [];
  return versions.length > 0 && !versions.some((version) => version.finalVersion);
}));
const answeredQuestionCount = computed(() => data.value.questions.filter((question) => question.answers.length > 0).length);
const answeredRate = computed(() => data.value.questions.length === 0
  ? '—'
  : `${Math.round((answeredQuestionCount.value / data.value.questions.length) * 100)}%`);
const metrics = computed(() => [
  analyticsMetric(
    'projects',
    '教学项目',
    sourceValue('教学项目', data.value.projects.length),
    '当前归属项目',
    '查看项目',
    'projects',
    FolderOpened,
    'blue',
  ),
  analyticsMetric(
    'pending-tasks',
    '待处理任务',
    sourceValue('教学任务', pendingTasks.value.length),
    '进行中或需修改',
    '查看任务',
    'teacher-teaching-tasks',
    Document,
    'amber',
  ),
  analyticsMetric(
    'published',
    '有效发布',
    sourceValue('发布记录', data.value.publications.filter((item) => item.status === 'PUBLISHED').length),
    '当前班级可见',
    '查看发布',
    'teacher-publications',
    Promotion,
    'green',
  ),
  analyticsMetric(
    'answered-rate',
    '问答已回答率',
    failedSources.value.includes('项目问答') ? '—' : answeredRate.value,
    failedSources.value.includes('项目问答')
      ? '问答数据不可用'
      : `${answeredQuestionCount.value}/${data.value.questions.length} 已回答`,
    '查看问答',
    'teacher-questions',
    ChatDotRound,
    'teal',
  ),
]);
const projectStatusRows = computed(() => {
  const counts = new Map<string, number>();
  data.value.projects.forEach((project) => counts.set(project.status, (counts.get(project.status) || 0) + 1));
  const total = Math.max(data.value.projects.length, 1);
  const statusOrder = ['CREATED', 'REQUIREMENT_CONFIRMED', 'MATERIAL_READY', 'INTENT_CONFIRMED', 'GENERATED', 'FINALIZED'];
  return [...counts.entries()]
    .map(([status, count]) => ({ status, count, percentage: Math.round((count / total) * 100) }))
    .sort((left, right) => statusOrder.indexOf(left.status) - statusOrder.indexOf(right.status));
});
const pendingItems = computed<PendingItem[]>(() => [
  ...openQuestions.value.map((question) => ({
    key: `question:${question.id}`,
    label: '学生提问',
    type: 'danger' as PendingTagType,
    title: question.title,
    description: `${question.studentName} · ${formatFullDateTime(question.updatedAt)}`,
    action: '回答',
    destination: 'teacher-questions',
    priority: 0,
  })),
  ...pendingTasks.value.map((task) => ({
    key: `task:${task.id}`,
    label: task.taskStatus === 'REVISION_REQUIRED' ? '需修改任务' : '教学任务',
    type: 'warning' as PendingTagType,
    title: task.taskName,
    description: `${task.courseName} · ${taskStatusLabel(task.taskStatus)}`,
    action: '处理',
    destination: 'teacher-teaching-tasks',
    priority: task.taskStatus === 'REVISION_REQUIRED' ? 1 : 2,
  })),
  ...projectsWithoutFinalVersion.value.map((project) => ({
    key: `version:${project.id}`,
    label: '成果版本',
    type: 'info' as PendingTagType,
    title: project.projectName,
    description: '已有成果版本尚未定稿',
    action: '查看',
    destination: `project:${project.id}`,
    priority: 3,
  })),
].sort((left, right) => left.priority - right.priority));
const pendingPageCount = computed(() => Math.max(1, Math.ceil(pendingItems.value.length / pendingPageSize)));
const pagedPendingItems = computed(() => {
  const start = (pendingPage.value - 1) * pendingPageSize;
  return pendingItems.value.slice(start, start + pendingPageSize);
});
const artifactRows = computed(() => data.value.projects
  .map((project) => {
    const versions = data.value.versionsByProjectId.get(project.id) || [];
    const publications = data.value.publications.filter((publication) => publication.projectId === project.id);
    if (versions.length === 0 && publications.length === 0) return null;
    const finalVersion = versions.find((version) => version.finalVersion);
    const latestVersion = [...versions].sort((left, right) => right.versionNumber - left.versionNumber)[0];
    const latestPublication = [...publications].sort((left, right) => toTimestamp(right.publishedAt) - toTimestamp(left.publishedAt))[0];
    return {
      key: `artifact:${project.id}`,
      projectId: project.id,
      title: project.projectName,
      description: `${project.courseName} · ${project.chapterTitle}`,
      updatedAt: project.updatedAt,
      versionLabel: latestVersion
        ? `${finalVersion ? '已定稿' : '未定稿'} · 版本 ${latestVersion.versionNumber} · 共 ${versions.length} 个`
        : '暂无成果版本',
      publicationLabel: latestPublication
        ? `${latestPublication.status === 'PUBLISHED' ? '已发布' : '已撤回'} · ${latestPublication.className} · ${formatFullDateTime(latestPublication.publishedAt)}`
        : '暂无班级发布',
    };
  })
  .filter((row): row is NonNullable<typeof row> => row !== null)
  .sort((left, right) => toTimestamp(right.updatedAt) - toTimestamp(left.updatedAt)));
const pagedArtifactRows = computed(() => {
  const start = (artifactPage.value - 1) * artifactPageSize;
  return artifactRows.value.slice(start, start + artifactPageSize);
});

watch(
  () => auth.activeRole,
  () => {
    requestSequence += 1;
    data.value = emptyData();
    loaded.value = false;
    errorMessage.value = '';
    pendingPage.value = 1;
    artifactPage.value = 1;
    if (isTeacher.value) void loadAnalytics();
  },
  { immediate: true },
);
watch(() => pendingItems.value.length, (length) => {
  const maxPage = Math.max(1, Math.ceil(length / pendingPageSize));
  if (pendingPage.value > maxPage) pendingPage.value = maxPage;
});
watch(() => artifactRows.value.length, (length) => {
  const maxPage = Math.max(1, Math.ceil(length / artifactPageSize));
  if (artifactPage.value > maxPage) artifactPage.value = maxPage;
});

async function loadAnalytics() {
  if (!isTeacher.value) return;
  const requestId = ++requestSequence;
  loading.value = true;
  errorMessage.value = '';
  try {
    const result = await loadTeacherAnalyticsData();
    if (requestId === requestSequence) {
      data.value = result;
      loaded.value = true;
      pendingPage.value = 1;
      artifactPage.value = 1;
      if (!hasData.value && result.failedSources.length >= 4) {
        errorMessage.value = '所有分析数据来源均暂时不可用，请稍后重试。';
      }
    }
  } catch {
    if (requestId === requestSequence) errorMessage.value = '教学分析暂时不可用，请稍后重试。';
  } finally {
    if (requestId === requestSequence) loading.value = false;
  }
}

function analyticsMetric(
  key: string,
  label: string,
  value: string,
  description: string,
  action: string,
  destination: string,
  icon: typeof FolderOpened,
  tone: MetricTone,
) {
  return { key, label, value, description, action, destination, icon, tone };
}

function sourceValue(source: string, value: number) {
  return failedSources.value.includes(source) ? '—' : String(value);
}

function projectStatusLabel(status: string) {
  return ({
    CREATED: '已创建',
    REQUIREMENT_CONFIRMED: '需求已确认',
    MATERIAL_READY: '资料就绪',
    INTENT_CONFIRMED: '意图已确认',
    GENERATED: '已生成',
    FINALIZED: '已定稿',
  })[status] || status;
}

function projectStatusColor(status: string) {
  return ({
    CREATED: '#6b7a90',
    REQUIREMENT_CONFIRMED: '#3f7df6',
    MATERIAL_READY: '#23a6a6',
    INTENT_CONFIRMED: '#7357e8',
    GENERATED: '#f29b38',
    FINALIZED: '#23b26d',
  })[status] || '#6b7a90';
}

function taskStatusLabel(status: string) {
  return ({
    ASSIGNED: '待开始',
    IN_PROGRESS: '进行中',
    REVISION_REQUIRED: '需要修改',
  })[status] || status;
}

function goTo(destination: string) {
  if (destination.startsWith('project:')) {
    goToProject(Number(destination.slice('project:'.length)));
    return;
  }
  void router.push({ name: destination });
}

function goToProject(projectId: number) {
  void router.push({ name: 'project-overview', params: { projectId } });
}

function toTimestamp(value?: string | null) {
  const timestamp = value ? new Date(value).getTime() : 0;
  return Number.isNaN(timestamp) ? 0 : timestamp;
}

function emptyData(): TeacherAnalyticsData {
  return { projects: [], tasks: [], publications: [], questions: [], versionsByProjectId: new Map(), failedSources: [] };
}
</script>

<style scoped>
.analytics-alert {
  margin-bottom: 16px;
}

.analytics-metrics {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  margin-bottom: 16px;
  overflow: hidden;
}

.analytics-metric {
  display: grid;
  grid-template-columns: 36px minmax(0, 1fr);
  grid-template-rows: 1fr auto;
  gap: 8px 11px;
  min-width: 0;
  min-height: 118px;
  padding: 15px 16px 13px;
  border-right: 1px solid var(--color-border);
}

.analytics-metric:last-child {
  border-right: 0;
}

.analytics-metric__icon {
  display: grid;
  width: 36px;
  height: 36px;
  place-items: center;
  border-radius: 8px;
  background: #edf4ff;
  color: #3f7df6;
  font-size: 18px;
}

.analytics-metric__icon--amber { background: #fff4e7; color: #d98220; }
.analytics-metric__icon--green { background: #e9f8f0; color: #168e55; }
.analytics-metric__icon--teal { background: #e8f8f8; color: #187f82; }

.analytics-metric__body {
  display: grid;
  min-width: 0;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 3px 8px;
}

.analytics-metric__body > span,
.analytics-metric__body > small {
  overflow: hidden;
  color: var(--color-text-muted);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.analytics-metric__body > strong {
  grid-row: 1 / span 2;
  grid-column: 2;
  align-self: center;
  color: var(--color-text);
  font-size: 26px;
  line-height: 1;
}

.analytics-link {
  display: inline-flex;
  align-items: center;
  justify-content: flex-start;
  gap: 4px;
  width: fit-content;
  padding: 0;
  border: 0;
  background: transparent;
  color: var(--color-primary);
  font: inherit;
  font-size: 12px;
  font-weight: 700;
  cursor: pointer;
}

.analytics-link:hover {
  color: var(--color-primary-dark);
}

.analytics-link:focus-visible {
  outline: 2px solid var(--color-primary);
  outline-offset: 3px;
  border-radius: 2px;
}

.analytics-metric > .analytics-link {
  grid-column: 1 / -1;
}

.analytics-grid {
  display: grid;
  grid-template-columns: minmax(0, 0.85fr) minmax(0, 1.15fr);
  gap: 16px;
}

.analytics-section {
  min-width: 0;
  overflow: hidden;
}

.analytics-section--wide {
  grid-column: 1 / -1;
}

.analytics-section__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  min-height: 70px;
  padding: 15px 18px;
  border-bottom: 1px solid var(--color-border);
}

.analytics-section__header h2,
.analytics-section__header p {
  margin: 0;
}

.analytics-section__header h2 {
  color: var(--color-text);
  font-size: 16px;
  line-height: 1.4;
}

.analytics-section__header p {
  margin-top: 4px;
  color: var(--color-text-muted);
  font-size: 12px;
  line-height: 1.5;
}

.analytics-section__count {
  flex: 0 0 auto;
  padding: 4px 8px;
  border: 1px solid var(--color-border);
  border-radius: 6px;
  color: var(--color-text-muted);
  font-size: 12px;
}

.analytics-section__state {
  margin: 18px;
}

.header-links {
  display: flex;
  flex: 0 0 auto;
  flex-wrap: wrap;
  gap: 12px;
}

.distribution-list {
  display: grid;
  gap: 14px;
  padding: 18px;
}

.distribution-row {
  display: grid;
  grid-template-columns: 108px minmax(0, 1fr) 28px;
  align-items: center;
  gap: 10px;
}

.distribution-row__label {
  display: flex;
  min-width: 0;
  align-items: center;
  justify-content: space-between;
  gap: 6px;
}

.distribution-row__label span {
  overflow: hidden;
  color: var(--color-text-secondary);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.distribution-row__label small {
  color: var(--color-text-muted);
  font-size: 11px;
}

.distribution-row > strong {
  color: var(--color-text);
  font-size: 13px;
  text-align: right;
}

.bounded-list {
  min-width: 0;
}

.pending-list {
  margin: 0;
  padding: 0;
  list-style: none;
}

.pending-list li {
  display: grid;
  grid-template-columns: 88px minmax(0, 1fr) auto;
  align-items: center;
  gap: 12px;
  min-height: 66px;
  padding: 11px 18px;
  border-bottom: 1px solid var(--color-border);
}

.pending-list li > div {
  display: grid;
  min-width: 0;
  gap: 4px;
}

.pending-list strong,
.pending-list span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.pending-list strong { color: var(--color-text); font-size: 13px; }
.pending-list span { color: var(--color-text-muted); font-size: 12px; }

.analytics-pagination {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  min-height: 50px;
  padding: 10px 18px;
  border-top: 1px solid var(--color-border);
  background: var(--color-surface-subtle);
}

.analytics-pagination > span {
  color: var(--color-text-muted);
  font-size: 12px;
}

.artifact-table {
  min-width: 0;
}

.artifact-table__header,
.artifact-row {
  display: grid;
  grid-template-columns: minmax(220px, 1.15fr) minmax(190px, 0.85fr) minmax(260px, 1.15fr) 86px;
  align-items: center;
  gap: 16px;
  padding: 0 18px;
}

.artifact-table__header {
  min-height: 38px;
  border-bottom: 1px solid var(--color-border);
  background: var(--color-surface-subtle);
  color: var(--color-text-muted);
  font-size: 11px;
  font-weight: 700;
}

.artifact-row {
  min-height: 68px;
  border-bottom: 1px solid var(--color-border);
}

.artifact-row:last-child {
  border-bottom: 0;
}

.artifact-row__identity {
  display: grid;
  min-width: 0;
  gap: 4px;
}

.artifact-row__identity strong,
.artifact-row__identity span,
.artifact-row__fact {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.artifact-row__identity strong { color: var(--color-text); font-size: 13px; }
.artifact-row__identity span,
.artifact-row__fact { color: var(--color-text-muted); font-size: 12px; }
.artifact-row__fact:first-of-type { color: var(--color-text-secondary); }

@media (max-width: 1100px) {
  .analytics-metrics { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .analytics-metric:nth-child(2) { border-right: 0; }
  .analytics-metric:nth-child(-n + 2) { border-bottom: 1px solid var(--color-border); }
  .analytics-grid { grid-template-columns: 1fr; }
  .analytics-section--wide { grid-column: auto; }
  .artifact-table__header,
  .artifact-row { grid-template-columns: minmax(200px, 1fr) minmax(180px, 0.8fr) minmax(220px, 1fr) 82px; }
}

@media (max-width: 760px) {
  .artifact-table__header { display: none; }
  .artifact-row {
    grid-template-columns: 1fr;
    gap: 8px;
    padding: 14px 18px;
  }
  .artifact-row .analytics-link { margin-top: 2px; }
  .analytics-pagination { align-items: flex-start; flex-direction: column; }
}

@media (max-width: 560px) {
  .analytics-metrics { grid-template-columns: 1fr; }
  .analytics-metric,
  .analytics-metric:nth-child(2) {
    border-right: 0;
    border-bottom: 1px solid var(--color-border);
  }
  .analytics-metric:last-child { border-bottom: 0; }
  .analytics-section__header { align-items: stretch; flex-direction: column; }
  .analytics-section__count { align-self: flex-start; }
  .header-links { gap: 14px; }
  .pending-list li { grid-template-columns: 1fr; gap: 7px; }
  .pending-list .el-tag { justify-self: start; }
  .pending-list .analytics-link { margin-top: 2px; }
  .distribution-row { grid-template-columns: 94px minmax(0, 1fr) 24px; }
  .analytics-pagination :deep(.el-pagination) { max-width: 100%; overflow-x: auto; }
}
</style>
