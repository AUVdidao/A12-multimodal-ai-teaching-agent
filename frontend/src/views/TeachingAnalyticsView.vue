<template>
  <section class="page teaching-analytics-page">
    <PageHeader
      eyebrow="教学分析"
      title="我的教学分析"
      description="基于当前项目、任务、版本、发布与问答记录生成。"
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

      <section class="analytics-metrics" aria-label="教学指标">
        <article v-for="metric in metrics" :key="metric.key" class="analytics-metric">
          <span>{{ metric.label }}</span>
          <strong>{{ metric.value }}</strong>
          <p>{{ metric.description }}</p>
          <el-button text type="primary" @click="goTo(metric.destination)">{{ metric.action }}</el-button>
        </article>
      </section>

      <section class="analytics-grid">
        <section class="surface-panel analytics-section" aria-labelledby="project-status-heading">
          <header class="analytics-section__header">
            <div>
              <h2 id="project-status-heading">项目状态分布</h2>
              <p>当前教师归属项目的实际状态。</p>
            </div>
            <el-button text type="primary" @click="goTo('projects')">查看项目</el-button>
          </header>
          <StatePanel
            v-if="failedSources.includes('教学项目')"
            type="error"
            title="项目数据暂时不可用"
            description="刷新后可重新汇总项目状态。"
          />
          <el-empty v-else-if="projectStatusRows.length === 0" description="暂无教学项目" :image-size="64" />
          <div v-else class="distribution-list">
            <div v-for="item in projectStatusRows" :key="item.status" class="distribution-row">
              <span>{{ projectStatusLabel(item.status) }}</span>
              <el-progress :percentage="item.percentage" :show-text="false" :stroke-width="8" />
              <strong>{{ item.count }}</strong>
            </div>
          </div>
        </section>

        <section class="surface-panel analytics-section" aria-labelledby="pending-heading">
          <header class="analytics-section__header">
            <div>
              <h2 id="pending-heading">待处理事项</h2>
              <p>任务、问答和未定稿版本的当前积压。</p>
            </div>
          </header>
          <el-empty v-if="pendingItems.length === 0" description="当前没有待处理事项" :image-size="64" />
          <ul v-else class="pending-list">
            <li v-for="item in pendingItems" :key="item.key">
              <div>
                <el-tag :type="item.type" effect="light" size="small">{{ item.label }}</el-tag>
                <strong>{{ item.title }}</strong>
                <span>{{ item.description }}</span>
              </div>
              <el-button text type="primary" @click="goTo(item.destination)">{{ item.action }}</el-button>
            </li>
          </ul>
        </section>

        <section class="surface-panel analytics-section analytics-section--wide" aria-labelledby="artifact-heading">
          <header class="analytics-section__header">
            <div>
              <h2 id="artifact-heading">成果版本与发布</h2>
              <p>仅展示当前教师项目已有的版本和班级发布记录。</p>
            </div>
            <div class="header-actions">
              <el-button text type="primary" @click="goTo('teacher-publications')">查看发布</el-button>
              <el-button text type="primary" @click="goTo('projects')">查看项目</el-button>
            </div>
          </header>
          <StatePanel
            v-if="failedSources.includes('成果版本') || failedSources.includes('发布记录')"
            type="error"
            title="部分成果数据暂时不可用"
            description="刷新后可重新读取版本或发布记录。"
          />
          <el-empty v-else-if="artifactRows.length === 0" description="暂无成果版本或发布记录" :image-size="64" />
          <ul v-else class="artifact-list">
            <li v-for="row in artifactRows" :key="row.key">
              <div class="artifact-list__identity">
                <strong>{{ row.title }}</strong>
                <span>{{ row.description }}</span>
              </div>
              <div class="artifact-list__facts">
                <span>{{ row.versionLabel }}</span>
                <span>{{ row.publicationLabel }}</span>
              </div>
              <el-button text type="primary" @click="goToProject(row.projectId)">打开项目</el-button>
            </li>
          </ul>
        </section>
      </section>
    </template>
  </section>
</template>

<script setup lang="ts">
import { loadTeacherAnalyticsData, type TeacherAnalyticsData } from '@/api/teachingAnalytics';
import PageHeader from '@/components/PageHeader.vue';
import StatePanel from '@/components/StatePanel.vue';
import { useAuthStore } from '@/stores/auth';
import { formatFullDateTime } from '@/utils/presentation';
import { Refresh } from '@element-plus/icons-vue';
import { computed, ref, watch } from 'vue';
import { useRouter } from 'vue-router';

type PendingTagType = 'warning' | 'danger' | 'info';

const auth = useAuthStore();
const router = useRouter();
const data = ref<TeacherAnalyticsData>({
  projects: [],
  tasks: [],
  publications: [],
  questions: [],
  versionsByProjectId: new Map(),
  failedSources: [],
});
const loading = ref(false);
const loaded = ref(false);
const errorMessage = ref('');
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
  const versions = data.value.versionsByProjectId.get(project.id);
  return versions && !versions.some((version) => version.finalVersion);
}));
const answeredQuestionCount = computed(() => data.value.questions.filter((question) => question.answers.length > 0).length);
const answeredRate = computed(() => data.value.questions.length === 0
  ? '—'
  : `${Math.round((answeredQuestionCount.value / data.value.questions.length) * 100)}%`);
const metrics = computed(() => [
  {
    key: 'projects',
    label: '教学项目',
    value: sourceValue('教学项目', data.value.projects.length),
    description: '当前归属项目总数',
    action: '查看项目',
    destination: 'projects',
  },
  {
    key: 'pending-tasks',
    label: '待处理任务',
    value: sourceValue('教学任务', pendingTasks.value.length),
    description: '进行中、待处理或需修改任务',
    action: '查看任务',
    destination: 'teacher-teaching-tasks',
  },
  {
    key: 'published',
    label: '班级发布',
    value: sourceValue('发布记录', data.value.publications.filter((item) => item.status === 'PUBLISHED').length),
    description: '当前仍有效的班级发布记录',
    action: '查看发布',
    destination: 'teacher-publications',
  },
  {
    key: 'answered-rate',
    label: '问答已回答率',
    value: failedSources.value.includes('项目问答') ? '—' : answeredRate.value,
    description: failedSources.value.includes('项目问答')
      ? '项目问答数据暂时不可用'
      : `${answeredQuestionCount.value} / ${data.value.questions.length} 条问题已有教师回答`,
    action: '查看问答',
    destination: 'teacher-questions',
  },
]);
const projectStatusRows = computed(() => {
  const counts = new Map<string, number>();
  data.value.projects.forEach((project) => counts.set(project.status, (counts.get(project.status) || 0) + 1));
  const largest = Math.max(...counts.values(), 1);
  return [...counts.entries()].map(([status, count]) => ({
    status,
    count,
    percentage: Math.round((count / largest) * 100),
  }));
});
const pendingItems = computed(() => [
  ...pendingTasks.value.map((task) => ({
    key: `task:${task.id}`,
    label: '教学任务',
    type: 'warning' as PendingTagType,
    title: task.taskName,
    description: `${task.courseName} · ${task.taskStatus}`,
    action: '处理',
    destination: 'teacher-teaching-tasks',
  })),
  ...openQuestions.value.map((question) => ({
    key: `question:${question.id}`,
    label: '学生提问',
    type: 'danger' as PendingTagType,
    title: question.title,
    description: `问题 #${question.id} · ${question.studentName}`,
    action: '回答',
    destination: 'teacher-questions',
  })),
  ...projectsWithoutFinalVersion.value.map((project) => ({
    key: `version:${project.id}`,
    label: '成果版本',
    type: 'info' as PendingTagType,
    title: project.projectName,
    description: '已有版本尚未定稿',
    action: '查看',
    destination: `project:${project.id}`,
  })),
]);
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
      versionLabel: latestVersion
        ? `${finalVersion ? '已定稿' : '未定稿'} · 版本 ${latestVersion.versionNumber} · ${versions.length} 个版本`
        : '暂无成果版本',
      publicationLabel: latestPublication
        ? `${latestPublication.status === 'PUBLISHED' ? '已发布' : '已撤回'} · ${latestPublication.className} · ${formatFullDateTime(latestPublication.publishedAt)}`
        : '暂无班级发布',
    };
  })
  .filter((row): row is NonNullable<typeof row> => row !== null));

watch(
  () => auth.activeRole,
  () => {
    requestSequence += 1;
    data.value = emptyData();
    loaded.value = false;
    errorMessage.value = '';
    if (isTeacher.value) void loadAnalytics();
  },
  { immediate: true },
);

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
      if (result.failedSources.length === 4) errorMessage.value = '所有分析数据来源均暂时不可用，请稍后重试。';
    }
  } catch {
    if (requestId === requestSequence) errorMessage.value = '教学分析暂时不可用，请稍后重试。';
  } finally {
    if (requestId === requestSequence) loading.value = false;
  }
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
  margin-bottom: 18px;
}

.analytics-metrics {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 14px;
  margin-bottom: 18px;
}

.analytics-metric {
  display: grid;
  align-content: start;
  min-width: 0;
  min-height: 174px;
  padding: 18px;
  border: 1px solid var(--color-border);
  border-top: 3px solid var(--color-primary);
  border-radius: var(--radius-lg);
  background: var(--color-surface);
  box-shadow: var(--shadow-card);
}

.analytics-metric:nth-child(2) { border-top-color: var(--color-warning); }
.analytics-metric:nth-child(3) { border-top-color: var(--color-success); }
.analytics-metric:nth-child(4) { border-top-color: var(--color-ai); }

.analytics-metric > span {
  color: var(--color-text-secondary);
  font-size: 13px;
  font-weight: 700;
}

.analytics-metric > strong {
  margin-top: 14px;
  color: var(--color-text);
  font-size: 30px;
  line-height: 1;
}

.analytics-metric p {
  min-height: 38px;
  margin: 12px 0 2px;
  color: var(--color-text-muted);
  font-size: 12px;
  line-height: 1.55;
}

.analytics-metric .el-button {
  justify-self: start;
  padding: 0;
}

.analytics-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 18px;
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
  padding: 18px 20px;
  border-bottom: 1px solid var(--color-border);
}

.analytics-section__header h2,
.analytics-section__header p {
  margin: 0;
}

.analytics-section__header h2 {
  color: var(--color-text);
  font-size: 17px;
  line-height: 1.4;
}

.analytics-section__header p {
  margin-top: 5px;
  color: var(--color-text-muted);
  font-size: 12px;
  line-height: 1.5;
}

.header-actions {
  display: flex;
  flex: 0 0 auto;
  gap: 8px;
}

.distribution-list {
  display: grid;
  gap: 15px;
  padding: 20px;
}

.distribution-row {
  display: grid;
  grid-template-columns: 92px minmax(0, 1fr) 28px;
  align-items: center;
  gap: 10px;
}

.distribution-row > span {
  overflow: hidden;
  color: var(--color-text-secondary);
  font-size: 13px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.distribution-row strong {
  color: var(--color-text);
  font-size: 13px;
  text-align: right;
}

.pending-list,
.artifact-list {
  margin: 0;
  padding: 0;
  list-style: none;
}

.pending-list li {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: center;
  gap: 16px;
  padding: 15px 20px;
  border-bottom: 1px solid var(--color-border);
}

.pending-list li:last-child,
.artifact-list li:last-child {
  border-bottom: 0;
}

.pending-list li > div {
  display: grid;
  min-width: 0;
  gap: 5px;
}

.pending-list .el-tag {
  justify-self: start;
}

.pending-list strong,
.pending-list span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.pending-list strong {
  color: var(--color-text);
  font-size: 14px;
}

.pending-list span {
  color: var(--color-text-muted);
  font-size: 12px;
}

.artifact-list li {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(220px, 0.7fr) auto;
  align-items: center;
  gap: 16px;
  padding: 15px 20px;
  border-bottom: 1px solid var(--color-border);
}

.artifact-list__identity,
.artifact-list__facts {
  display: grid;
  min-width: 0;
  gap: 5px;
}

.artifact-list strong,
.artifact-list span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.artifact-list strong {
  color: var(--color-text);
  font-size: 14px;
}

.artifact-list span {
  color: var(--color-text-muted);
  font-size: 12px;
}

.artifact-list__facts span:first-child {
  color: var(--color-text-secondary);
}

@media (max-width: 1050px) {
  .analytics-metrics {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 760px) {
  .analytics-grid {
    grid-template-columns: 1fr;
  }

  .analytics-section--wide {
    grid-column: auto;
  }

  .artifact-list li {
    grid-template-columns: 1fr;
    gap: 9px;
  }

  .artifact-list .el-button {
    justify-self: start;
  }
}

@media (max-width: 560px) {
  .analytics-metrics {
    grid-template-columns: 1fr;
  }

  .analytics-section__header,
  .pending-list li {
    align-items: stretch;
    flex-direction: column;
  }

  .analytics-section__header {
    display: flex;
  }

  .header-actions {
    flex-wrap: wrap;
  }

  .pending-list li {
    display: flex;
  }

  .pending-list li > .el-button {
    align-self: flex-start;
  }

  .distribution-row {
    grid-template-columns: 78px minmax(0, 1fr) 24px;
  }
}
</style>
