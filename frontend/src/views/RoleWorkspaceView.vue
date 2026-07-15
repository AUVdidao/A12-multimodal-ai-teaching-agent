<template>
  <section class="role-workspace">
    <template v-if="isLeader">
      <PageHeader
        eyebrow="教研负责人"
        :title="`${displayName}，教研工作概览`"
        description="查看当前教学任务进展，并前往任务或课程工作区继续管理。"
      >
        <template #actions>
          <RouterLink
            class="workspace-action workspace-action--primary"
            :to="{ name: 'leader-teaching-tasks' }"
          >
            <el-icon><List /></el-icon>
            <span>管理教学任务</span>
          </RouterLink>
          <RouterLink class="workspace-action" :to="{ name: 'leader-courses' }">
            <el-icon><Collection /></el-icon>
            <span>课程与班级</span>
          </RouterLink>
        </template>
      </PageHeader>

      <StatePanel
        v-if="loading"
        type="loading"
        title="正在汇总教学任务"
        description="正在读取当前负责人创建的教学任务与最新进展。"
      />
      <StatePanel
        v-else-if="errorMessage"
        type="error"
        title="教学任务读取失败"
        :description="errorMessage"
      >
        <template #action>
          <el-button type="primary" :icon="Refresh" @click="loadTasks">重新加载</el-button>
        </template>
      </StatePanel>

      <template v-else>
        <section class="workspace-metrics" aria-label="教学任务统计">
          <article
            v-for="metric in workspaceMetrics"
            :key="metric.key"
            :class="['workspace-metric', `workspace-metric--${metric.tone}`]"
          >
            <div class="workspace-metric__heading">
              <span>{{ metric.label }}</span>
              <el-icon><component :is="metric.icon" /></el-icon>
            </div>
            <strong>{{ metric.value }}</strong>
            <p>{{ metric.description }}</p>
          </article>
        </section>

        <section class="recent-tasks surface-panel" aria-labelledby="recent-task-heading">
          <header class="recent-tasks__header">
            <div>
              <h2 id="recent-task-heading">最近任务</h2>
              <p>按最近更新时间排列，显示最新的 {{ recentTaskLimit }} 项任务。</p>
            </div>
            <RouterLink class="recent-tasks__link" :to="{ name: 'leader-teaching-tasks' }">
              <span>查看全部</span>
              <el-icon><ArrowRight /></el-icon>
            </RouterLink>
          </header>

          <ul v-if="recentTasks.length > 0" class="recent-tasks__list">
            <li v-for="task in recentTasks" :key="task.id">
              <div class="recent-task__identity">
                <div class="recent-task__title">
                  <strong>{{ task.taskName }}</strong>
                  <el-tag :type="statusTagType(task.taskStatus)" effect="light" size="small">
                    {{ statusLabel(task.taskStatus) }}
                  </el-tag>
                  <el-tag v-if="task.overdue" type="danger" effect="dark" size="small">
                    已逾期
                  </el-tag>
                </div>
                <p>{{ task.courseName }} · {{ task.chapterTitle }}</p>
              </div>
              <div class="recent-task__meta">
                <span>
                  <el-icon><User /></el-icon>
                  {{ task.assigneeName }}
                </span>
                <time :datetime="task.dueAt" :class="{ 'is-overdue': task.overdue }">
                  <el-icon><Calendar /></el-icon>
                  截止 {{ formatFullDateTime(task.dueAt) }}
                </time>
              </div>
            </li>
          </ul>

          <div v-else class="recent-tasks__empty" role="status">
            <el-icon><List /></el-icon>
            <div>
              <strong>暂无教学任务</strong>
              <p>创建并分配任务后，最新进展将在这里显示。</p>
            </div>
          </div>
        </section>

        <section class="recent-publications surface-panel" aria-labelledby="recent-publication-heading">
          <header class="recent-tasks__header">
            <div>
              <h2 id="recent-publication-heading">最近发布</h2>
              <p>当前负责人真实发布记录，共 {{ publications.length }} 条。</p>
            </div>
            <RouterLink class="recent-tasks__link" to="/leader/publications">
              <span>管理发布</span>
              <el-icon><ArrowRight /></el-icon>
            </RouterLink>
          </header>

          <StatePanel
            v-if="publicationsLoading && publications.length === 0"
            type="loading"
            title="正在读取发布记录"
            description="正在读取当前负责人发布到班级的成果。"
          />
          <StatePanel
            v-else-if="publicationsError && publications.length === 0"
            type="error"
            title="发布记录读取失败"
            :description="publicationsError"
          >
            <template #action>
              <el-button type="primary" :icon="Refresh" @click="loadPublications">重新加载</el-button>
            </template>
          </StatePanel>
          <ul v-else-if="recentPublications.length" class="recent-tasks__list" v-loading="publicationsLoading">
            <li v-for="publication in recentPublications" :key="publication.id">
              <div class="recent-task__identity">
                <div class="recent-task__title">
                  <strong>{{ publication.title }}</strong>
                  <el-tag :type="publication.status === 'PUBLISHED' ? 'success' : 'info'" effect="light" size="small">
                    {{ publication.status === 'PUBLISHED' ? '已发布' : '已撤回' }}
                  </el-tag>
                </div>
                <p>{{ publication.courseName }} · {{ publication.className }} · {{ publication.projectName }}</p>
              </div>
              <div class="recent-task__meta">
                <time :datetime="publication.publishedAt">
                  <el-icon><Calendar /></el-icon>
                  {{ formatFullDateTime(publication.publishedAt) }}
                </time>
              </div>
            </li>
          </ul>
          <StatePanel
            v-else
            type="empty"
            title="暂无真实发布记录"
            description="审批通过并发布到班级的成果会显示在这里。"
          />
        </section>
      </template>
    </template>

    <template v-else>
      <PageHeader
        eyebrow="学生"
        :title="`${displayName}，学习空间`"
        description="教师发布的课程与学习内容会集中显示在这里。"
      />
      <StatePanel
        v-if="studentLearningLoading && studentLearningTasks.length === 0"
        type="loading"
        title="正在读取学习内容"
        description="正在读取你所在班级的最新发布。"
      />
      <StatePanel
        v-else-if="studentLearningError && studentLearningTasks.length === 0"
        type="error"
        title="学习内容读取失败"
        :description="studentLearningError"
      >
        <template #action>
          <el-button type="primary" :icon="Refresh" @click="loadStudentLearning">重新加载</el-button>
        </template>
      </StatePanel>
      <section v-else class="student-learning-preview surface-panel" v-loading="studentLearningLoading">
        <header class="student-learning-preview__header">
          <div>
            <h2>已发布学习内容</h2>
            <p>当前可访问 {{ studentLearningTasks.length }} 项学习内容。</p>
          </div>
          <RouterLink class="recent-tasks__link" to="/student/learning">
            <span>进入学习详情</span>
            <el-icon><ArrowRight /></el-icon>
          </RouterLink>
        </header>
        <StatePanel
          v-if="studentLearningTasks.length === 0"
          type="empty"
          title="暂无已发布的学习内容"
          description="你所在的班级暂时没有可学习的已发布内容。教师发布后，内容会自动出现在这里。"
        />
        <ul v-else class="student-learning-list">
          <li v-for="item in recentLearningTasks" :key="item.publicationId">
            <div>
              <strong>{{ item.title }}</strong>
              <p>{{ item.courseName }} · {{ item.className }}</p>
            </div>
            <RouterLink
              class="student-learning-list__link"
              :to="{ path: '/student/learning', query: { publicationId: String(item.publicationId) } }"
            >
              查看内容
              <el-icon><ArrowRight /></el-icon>
            </RouterLink>
          </li>
        </ul>
      </section>
      <el-alert
        v-if="studentLearningError && studentLearningTasks.length"
        class="inline-alert"
        type="error"
        :title="studentLearningError"
        show-icon
        :closable="false"
      />
    </template>
  </section>
</template>

<script setup lang="ts">
import { listLearningTasks, type LearningTaskSummary } from '@/api/publications';
import { listPublications, type Publication } from '@/api/publications';
import { listTeachingTasks, type TeachingTask, type TeachingTaskStatus } from '@/api/teachingTasks';
import PageHeader from '@/components/PageHeader.vue';
import StatePanel from '@/components/StatePanel.vue';
import { useAuthStore } from '@/stores/auth';
import { formatFullDateTime } from '@/utils/presentation';
import {
  ArrowRight,
  Calendar,
  CircleCheck,
  Clock,
  Collection,
  List,
  Refresh,
  UploadFilled,
  User,
  WarningFilled,
} from '@element-plus/icons-vue';
import { computed, ref, watch } from 'vue';
import { RouterLink } from 'vue-router';

type TagType = 'primary' | 'success' | 'warning' | 'danger' | 'info';

const recentTaskLimit = 5;
const activeStatuses: TeachingTaskStatus[] = ['ASSIGNED', 'IN_PROGRESS', 'REVISION_REQUIRED'];
const statusLabels: Record<TeachingTaskStatus, string> = {
  DRAFT: '草稿',
  ASSIGNED: '已分配',
  IN_PROGRESS: '进行中',
  SUBMITTED: '已提交',
  REVISION_REQUIRED: '需修改',
  COMPLETED: '已完成',
  CANCELLED: '已取消',
};

const auth = useAuthStore();
const tasks = ref<TeachingTask[]>([]);
const loading = ref(false);
const errorMessage = ref('');
const publications = ref<Publication[]>([]);
const publicationsLoading = ref(false);
const publicationsError = ref('');
const studentLearningTasks = ref<LearningTaskSummary[]>([]);
const studentLearningLoading = ref(false);
const studentLearningError = ref('');
let requestSequence = 0;
let publicationSequence = 0;
let studentLearningSequence = 0;

const isLeader = computed(() => auth.activeRole === 'LEADER');
const displayName = computed(() => auth.user?.displayName || (isLeader.value ? '教研负责人' : '同学'));

const workspaceMetrics = computed(() => [
  {
    key: 'active',
    label: '活跃任务',
    value: tasks.value.filter((task) => activeStatuses.includes(task.taskStatus)).length,
    description: '已分配、进行中与需修改',
    icon: Clock,
    tone: 'primary',
  },
  {
    key: 'submitted',
    label: '已提交',
    value: tasks.value.filter((task) => task.taskStatus === 'SUBMITTED').length,
    description: '当前状态为已提交',
    icon: UploadFilled,
    tone: 'success',
  },
  {
    key: 'completed',
    label: '已完成',
    value: tasks.value.filter((task) => task.taskStatus === 'COMPLETED').length,
    description: '当前状态为已完成',
    icon: CircleCheck,
    tone: 'neutral',
  },
  {
    key: 'overdue',
    label: '已逾期',
    value: tasks.value.filter((task) => task.overdue).length,
    description: '超过截止时间且尚未结束',
    icon: WarningFilled,
    tone: 'danger',
  },
]);

const recentTasks = computed(() => [...tasks.value]
  .sort((left, right) => taskTimestamp(right) - taskTimestamp(left))
  .slice(0, recentTaskLimit));
const recentPublications = computed(() => [...publications.value]
  .sort((left, right) => publicationTimestamp(right) - publicationTimestamp(left))
  .slice(0, 4));
const recentLearningTasks = computed(() => [...studentLearningTasks.value]
  .sort((left, right) => publicationTimestamp(right) - publicationTimestamp(left))
  .slice(0, 4));

watch(
  () => auth.activeRole,
  (role) => {
    if (role === 'LEADER') {
      void loadTasks();
      void loadPublications();
      return;
    }
    requestSequence += 1;
    tasks.value = [];
    loading.value = false;
    errorMessage.value = '';
    publicationSequence += 1;
    publications.value = [];
    publicationsLoading.value = false;
    publicationsError.value = '';
    if (role === 'STUDENT') {
      void loadStudentLearning();
      return;
    }
    studentLearningSequence += 1;
    studentLearningTasks.value = [];
    studentLearningLoading.value = false;
    studentLearningError.value = '';
  },
  { immediate: true },
);

async function loadTasks() {
  if (!isLeader.value) return;

  const requestId = ++requestSequence;
  loading.value = true;
  errorMessage.value = '';
  try {
    const result = await listTeachingTasks();
    if (requestId === requestSequence && isLeader.value) tasks.value = result;
  } catch (error) {
    if (requestId === requestSequence && isLeader.value) {
      errorMessage.value = resolveError(error, '暂时无法读取教学任务，请稍后重试。');
    }
  } finally {
    if (requestId === requestSequence) loading.value = false;
  }
}

async function loadPublications() {
  if (!isLeader.value) return;
  const sequence = ++publicationSequence;
  publicationsLoading.value = true;
  publicationsError.value = '';
  try {
    const result = await listPublications();
    if (sequence === publicationSequence && isLeader.value) publications.value = result;
  } catch (error) {
    if (sequence === publicationSequence && isLeader.value) {
      publicationsError.value = resolveError(error, '暂时无法读取发布记录，请稍后重试。');
    }
  } finally {
    if (sequence === publicationSequence) publicationsLoading.value = false;
  }
}

async function loadStudentLearning() {
  if (auth.activeRole !== 'STUDENT') return;
  const sequence = ++studentLearningSequence;
  studentLearningLoading.value = true;
  studentLearningError.value = '';
  try {
    const result = await listLearningTasks();
    if (sequence === studentLearningSequence && auth.activeRole === 'STUDENT') {
      studentLearningTasks.value = result;
    }
  } catch (error) {
    if (sequence === studentLearningSequence && auth.activeRole === 'STUDENT') {
      studentLearningError.value = resolveError(error, '暂时无法读取学习内容，请稍后重试。');
    }
  } finally {
    if (sequence === studentLearningSequence) studentLearningLoading.value = false;
  }
}

function taskTimestamp(task: TeachingTask) {
  const value = task.updatedAt || task.createdAt || task.dueAt;
  const timestamp = new Date(value).getTime();
  return Number.isNaN(timestamp) ? 0 : timestamp;
}

function publicationTimestamp(publication: Publication | LearningTaskSummary) {
  const timestamp = new Date(publication.publishedAt).getTime();
  return Number.isNaN(timestamp) ? 0 : timestamp;
}

function statusLabel(status: TeachingTaskStatus) {
  return statusLabels[status];
}

function statusTagType(status: TeachingTaskStatus): TagType {
  if (status === 'COMPLETED' || status === 'SUBMITTED') return 'success';
  if (status === 'REVISION_REQUIRED') return 'danger';
  if (status === 'IN_PROGRESS' || status === 'DRAFT') return 'warning';
  if (status === 'ASSIGNED') return 'primary';
  return 'info';
}

function resolveError(error: unknown, fallback: string) {
  const message = (error as { response?: { data?: { message?: string } } }).response?.data?.message;
  return message && !/(Exception|java\.|Axios)/i.test(message) ? message : fallback;
}
</script>

<style scoped>
.role-workspace {
  min-width: 0;
}

.workspace-action {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  min-height: var(--control-height);
  padding: 0 14px;
  border: 1px solid var(--color-border-strong);
  border-radius: var(--radius-md);
  background: var(--color-surface);
  color: var(--color-text-secondary);
  font-size: 14px;
  font-weight: 700;
  text-decoration: none;
  transition: border-color var(--transition-fast), color var(--transition-fast), background var(--transition-fast);
}

.workspace-action:hover {
  border-color: var(--color-primary-border);
  color: var(--color-primary);
}

.workspace-action--primary {
  border-color: var(--color-primary);
  background: var(--color-primary);
  color: #fff;
}

.workspace-action--primary:hover {
  border-color: var(--color-primary-hover);
  background: var(--color-primary-hover);
  color: #fff;
}

.workspace-action:focus-visible,
.recent-tasks__link:focus-visible {
  outline: 3px solid var(--color-primary-border);
  outline-offset: 2px;
}

.workspace-metrics {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 14px;
  margin-bottom: 20px;
}

.workspace-metric {
  min-width: 0;
  min-height: 150px;
  padding: 18px;
  border: 1px solid var(--color-border);
  border-top: 3px solid var(--color-border-strong);
  border-radius: var(--radius-lg);
  background: var(--color-surface);
  box-shadow: var(--shadow-card);
}

.workspace-metric--primary {
  border-top-color: var(--color-primary);
}

.workspace-metric--success {
  border-top-color: var(--color-success);
}

.workspace-metric--danger {
  border-top-color: var(--color-danger);
}

.workspace-metric__heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  color: var(--color-text-secondary);
  font-size: 13px;
  font-weight: 700;
}

.workspace-metric__heading .el-icon {
  flex: 0 0 auto;
  color: var(--color-text-muted);
  font-size: 20px;
}

.workspace-metric--primary .workspace-metric__heading .el-icon {
  color: var(--color-primary);
}

.workspace-metric--success .workspace-metric__heading .el-icon {
  color: var(--color-success);
}

.workspace-metric--danger .workspace-metric__heading .el-icon {
  color: var(--color-danger);
}

.workspace-metric > strong {
  display: block;
  margin-top: 14px;
  color: var(--color-text);
  font-size: 30px;
  line-height: 1;
}

.workspace-metric p {
  margin: 12px 0 0;
  color: var(--color-text-muted);
  font-size: 12px;
  line-height: 1.5;
}

.recent-tasks {
  overflow: hidden;
}

.recent-tasks__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  padding: 18px 20px;
  border-bottom: 1px solid var(--color-border);
}

.recent-tasks__header h2,
.recent-tasks__header p {
  margin: 0;
}

.recent-tasks__header h2 {
  color: var(--color-text);
  font-size: 17px;
  line-height: 1.35;
}

.recent-tasks__header p {
  margin-top: 4px;
  color: var(--color-text-muted);
  font-size: 12px;
  line-height: 1.5;
}

.recent-tasks__link {
  display: inline-flex;
  flex: 0 0 auto;
  align-items: center;
  gap: 5px;
  color: var(--color-primary);
  font-size: 13px;
  font-weight: 700;
  text-decoration: none;
}

.recent-tasks__link:hover {
  color: var(--color-primary-hover);
}

.recent-tasks__list {
  margin: 0;
  padding: 0;
  list-style: none;
}

.recent-tasks__list li {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: center;
  gap: 20px;
  min-height: 82px;
  padding: 14px 20px;
  border-bottom: 1px solid var(--color-border);
}

.recent-tasks__list li:last-child {
  border-bottom: 0;
}

.recent-task__identity {
  min-width: 0;
}

.recent-task__title {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.recent-task__title strong {
  overflow: hidden;
  color: var(--color-text);
  font-size: 14px;
  line-height: 20px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.recent-task__title .el-tag {
  flex: 0 0 auto;
}

.recent-task__identity p {
  overflow: hidden;
  margin: 5px 0 0;
  color: var(--color-text-muted);
  font-size: 12px;
  line-height: 18px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.recent-task__meta {
  display: grid;
  justify-items: end;
  gap: 7px;
  color: var(--color-text-secondary);
  font-size: 12px;
}

.recent-task__meta span,
.recent-task__meta time {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  white-space: nowrap;
}

.recent-task__meta .el-icon {
  color: var(--color-text-muted);
}

.recent-task__meta .is-overdue,
.recent-task__meta .is-overdue .el-icon {
  color: var(--color-danger);
}

.recent-tasks__empty {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 14px;
  min-height: 180px;
  padding: 28px;
  color: var(--color-text-muted);
}

.recent-tasks__empty > .el-icon {
  flex: 0 0 auto;
  font-size: 30px;
}

.recent-tasks__empty strong,
.recent-tasks__empty p {
  display: block;
  margin: 0;
}

.recent-tasks__empty strong {
  color: var(--color-text);
  font-size: 15px;
}

.recent-tasks__empty p {
  margin-top: 5px;
  font-size: 13px;
  line-height: 1.6;
}

.student-pending {
  min-height: 260px;
  align-items: center;
  padding: 36px;
  background: var(--color-surface);
  box-shadow: var(--shadow-card);
}

.student-pending :deep(.state-panel__icon) {
  display: grid;
  width: 44px;
  height: 44px;
  margin-top: 0;
  place-items: center;
  border-radius: var(--radius-md);
  background: var(--color-primary-soft);
  font-size: 24px;
}

.student-pending :deep(.state-panel__content strong) {
  font-size: 18px;
}

.student-pending :deep(.state-panel__content p) {
  max-width: 640px;
  margin-top: 8px;
  font-size: 14px;
}

@media (max-width: 1000px) {
  .workspace-metrics {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 720px) {
  .workspace-action {
    width: 100%;
  }

  .recent-tasks__list li {
    grid-template-columns: 1fr;
    gap: 10px;
  }

  .recent-task__meta {
    justify-items: start;
  }
}

@media (max-width: 560px) {
  .workspace-metrics {
    grid-template-columns: 1fr;
  }

  .recent-tasks__header {
    align-items: flex-start;
  }

  .recent-task__title {
    flex-wrap: wrap;
  }

  .student-pending {
    align-items: flex-start;
    min-height: 220px;
    padding: 24px 20px;
  }
}
</style>
