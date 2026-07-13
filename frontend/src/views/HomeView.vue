<template>
  <section class="page">
    <header class="page-hero">
      <div>
        <h2>{{ greeting }}，教师</h2>
        <p>今天是 {{ todayLabel }}，系统已根据真实项目进度整理下一步工作。</p>
      </div>
      <div class="page-actions">
        <el-button @click="checkHealth" :loading="checking">检查服务</el-button>
        <el-button type="primary" @click="router.push('/projects/new')">新建教学项目</el-button>
      </div>
    </header>

    <div class="grid cols-4 dashboard-metrics">
      <UiMetricCard
        label="教学项目"
        :value="metrics.projectCount"
        :note="`进行中 ${metrics.activeProjectCount} · 已定稿 ${finishedCount}`"
        tone="purple"
        icon="folder"
        variant="shortcut"
        clickable
        @click="router.push('/projects')"
      />
      <UiMetricCard
        label="待完成任务"
        :value="metrics.pendingTaskCount"
        note="按项目当前阶段自动派生"
        tone="blue"
        icon="document"
        variant="shortcut"
        clickable
        @click="openFirstTask"
      />
      <UiMetricCard
        label="资料总数"
        :value="metrics.materialCount"
        note="当前项目已上传资料"
        tone="green"
        icon="book"
        variant="shortcut"
        clickable
        @click="openFirstProject('materials')"
      />
      <UiMetricCard
        label="教学意图"
        :value="metrics.confirmedIntentCount"
        note="已确认教学意图"
        tone="orange"
        icon="lightbulb"
        variant="shortcut"
        clickable
        @click="openFirstProject('intent')"
      />
    </div>

    <div class="grid cols-2" style="margin-top: 16px">
      <section class="panel project-continuation-panel">
        <div class="panel__header project-list-heading">
          <h3>继续你的项目</h3>
          <el-button class="project-view-all" @click="router.push('/projects')">
            查看全部
            <span class="project-view-all__arrow" aria-hidden="true">&rarr;</span>
          </el-button>
        </div>
        <div class="project-list" role="table" aria-label="继续你的项目">
          <div class="project-list__head" role="row">
            <span role="columnheader">项目名称</span>
            <span role="columnheader">进度</span>
            <span role="columnheader">下一步待办</span>
            <span class="project-list__updated" role="columnheader">更新时间</span>
            <span aria-hidden="true" />
          </div>

          <div
            v-for="project in projects.slice(0, 5)"
            :key="project.id"
            class="project-list__row"
            role="row"
            tabindex="0"
            @click="openProject(project)"
            @keydown.enter="openProject(project)"
            @keydown.space.prevent="openProject(project)"
          >
            <div class="project-list__identity" role="cell">
              <UiSubjectIcon :icon="projectIcon(project.id)" :tone="projectTone(project.id)" />
              <div>
                <strong>{{ project.projectName }}</strong>
                <span>{{ project.subtitle || project.chapterTitle }}</span>
              </div>
            </div>

            <div class="project-list__progress" role="cell" :aria-label="`进度 ${project.progress}%`">
              <span class="project-list__track">
                <i :class="projectTone(project.id)" :style="{ width: `${project.progress}%` }" />
              </span>
              <strong>{{ project.progress }}%</strong>
            </div>

            <div class="project-list__next" role="cell">
              <i :class="projectTone(project.id)" aria-hidden="true" />
              <span>{{ project.nextAction }}</span>
            </div>

            <time class="project-list__updated" role="cell" :datetime="project.updatedAt">
              {{ formatRelativeTime(project.updatedAt) }}
            </time>

            <div class="project-list__menu" role="cell" @click.stop @keydown.stop>
              <el-dropdown trigger="click" @command="handleProjectCommand($event, project.id)">
                <button
                  class="project-row-menu"
                  type="button"
                  title="更多操作"
                  :aria-label="`${project.projectName}的更多操作`"
                >
                  <el-icon><MoreFilled /></el-icon>
                </button>
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item command="overview">打开项目</el-dropdown-item>
                    <el-dropdown-item command="materials">查看资料</el-dropdown-item>
                    <el-dropdown-item command="intent">教学意图</el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
            </div>
          </div>
        </div>
        <el-empty v-if="!loading && projects.length === 0" description="暂无进行中的教学项目" :image-size="72" />
      </section>

      <aside class="grid">
        <section class="panel">
          <div class="panel__header">
            <h3>今日待办</h3>
            <el-button text type="primary" :disabled="pendingTasks.length === 0" @click="openFirstTask">进入任务</el-button>
          </div>
          <button
            v-for="task in visiblePendingTasks"
            :key="task.code"
            class="today-task-row today-task-row--button"
            type="button"
            @click="router.push(task.actionPath)"
          >
            <span>{{ task.title }}</span>
            <span class="tag-soft" :class="priorityClass(task.priority)">{{ priorityLabel(task.priority) }}</span>
          </button>
          <el-empty v-if="!loading && pendingTasks.length === 0" description="当前没有待办" :image-size="58" />
        </section>

        <UiAiSuggestionCard
          :title="primarySuggestion?.title || '当前项目进展顺利'"
          :description="primarySuggestion?.description || '创建新项目后，系统会在这里给出下一步建议。'"
          :action-label="primarySuggestion ? '立即处理' : '新建项目'"
          @action="router.push(primarySuggestion?.actionPath || '/projects/new')"
        />
      </aside>
    </div>

    <el-alert v-if="healthMessage" :title="healthMessage" :type="healthType" show-icon :closable="false" style="margin-top: 16px" />
  </section>
</template>

<script setup lang="ts">
import { checkBackendHealth } from '@/api/health';
import { getTeacherWorkspace, type TeacherWorkspace } from '@/api/workspace';
import UiAiSuggestionCard from '@/components/ui/UiAiSuggestionCard.vue';
import UiMetricCard from '@/components/ui/UiMetricCard.vue';
import UiSubjectIcon from '@/components/ui/UiSubjectIcon.vue';
import { formatRelativeTime, projectIcon, projectTone } from '@/utils/presentation';
import { MoreFilled } from '@element-plus/icons-vue';
import { computed, onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';

const router = useRouter();
const workspace = ref<TeacherWorkspace>();
const loading = ref(true);
const checking = ref(false);
const healthMessage = ref('');
const healthType = ref<'success' | 'warning'>('success');

const projects = computed(() => workspace.value?.continueProjects || []);
const pendingTasks = computed(() => workspace.value?.pendingTasks || []);
const visiblePendingTasks = computed(() => pendingTasks.value.slice(0, 5));
const metrics = computed(() => workspace.value?.metrics || {
  projectCount: 0,
  activeProjectCount: 0,
  pendingTaskCount: 0,
  materialCount: 0,
  confirmedIntentCount: 0,
  generatedArtifactCount: 0,
});
const finishedCount = computed(() => Math.max(0, metrics.value.projectCount - metrics.value.activeProjectCount));
const primarySuggestion = computed(() => workspace.value?.suggestions?.[0]);
const todayLabel = new Intl.DateTimeFormat('zh-CN', { year: 'numeric', month: 'long', day: 'numeric', weekday: 'long' }).format(new Date());
const greeting = computed(() => {
  const hour = new Date().getHours();
  if (hour < 11) return '上午好';
  if (hour < 14) return '中午好';
  if (hour < 18) return '下午好';
  return '晚上好';
});

function openProject(row: { id: number }) {
  router.push(`/projects/${row.id}`);
}

function handleProjectCommand(command: string, projectId: number) {
  const routes: Record<string, string> = {
    overview: `/projects/${projectId}`,
    materials: `/projects/${projectId}/materials`,
    intent: `/projects/${projectId}/intent`,
  };
  router.push(routes[command] || routes.overview);
}

function openFirstTask() {
  router.push(pendingTasks.value[0]?.actionPath || '/projects');
}

function openFirstProject(section: 'materials' | 'intent') {
  const project = projects.value[0];
  router.push(project ? `/projects/${project.id}/${section}` : '/projects');
}

function priorityLabel(priority: string) {
  const labels: Record<string, string> = { HIGH: '高', MEDIUM: '中', LOW: '低' };
  return labels[priority] || priority;
}

function priorityClass(priority: string) {
  if (priority === 'MEDIUM') return 'warning';
  if (priority === 'LOW') return 'info';
  return '';
}

async function checkHealth() {
  checking.value = true;
  healthMessage.value = '';
  try {
    const result = await checkBackendHealth();
    healthType.value = result.data.status === 'UP' ? 'success' : 'warning';
    healthMessage.value = `后端状态：${result.data.status} / 服务：${result.data.service}`;
  } catch (error) {
    healthType.value = 'warning';
    healthMessage.value = '后端服务暂不可用，请确认 Docker 或本地服务已启动。';
  } finally {
    checking.value = false;
  }
}

async function loadWorkspace() {
  loading.value = true;
  try {
    workspace.value = await getTeacherWorkspace();
  } catch {
    healthType.value = 'warning';
    healthMessage.value = '工作台数据加载失败，请检查后端服务。';
  } finally {
    loading.value = false;
  }
}

onMounted(loadWorkspace);
</script>

<style scoped>
.project-continuation-panel {
  overflow: hidden;
}

.project-list-heading {
  margin-bottom: 4px;
}

.project-view-all {
  gap: 6px;
  height: 32px;
  padding: 0 12px;
  border-color: #dfe4ee;
  color: #3d465e;
  font-weight: 600;
}

.project-view-all__arrow {
  font-size: 15px;
  line-height: 1;
}

.project-list {
  width: 100%;
}

.project-list__head,
.project-list__row {
  display: grid;
  grid-template-columns: minmax(220px, 2fr) minmax(130px, 0.95fr) minmax(150px, 1.15fr) 104px 28px;
  align-items: center;
  column-gap: 16px;
}

.project-list__head {
  min-height: 38px;
  border-bottom: 1px solid #dfe4ed;
  color: #66708c;
  font-size: 13px;
  font-weight: 600;
}

.project-list__row {
  min-height: 63px;
  border-bottom: 1px solid #e8ebf2;
  color: #273149;
  cursor: pointer;
  transition: background-color 150ms ease;
}

.project-list__row:last-child {
  border-bottom: 0;
}

.project-list__row:hover {
  background: #fafbff;
}

.project-list__row:focus-visible {
  outline: 2px solid rgba(91, 69, 246, 0.3);
  outline-offset: -2px;
}

.project-list__identity {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 12px;
}

.project-list__identity > div {
  min-width: 0;
}

.project-list__identity > div > strong,
.project-list__identity > div > span {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.project-list__identity > div > strong {
  color: #222b3f;
  font-size: 14px;
  line-height: 20px;
}

.project-list__identity > div > span {
  margin-top: 2px;
  color: #77809a;
  font-size: 12px;
  line-height: 17px;
}

.project-list__progress {
  display: grid;
  grid-template-columns: minmax(72px, 1fr) 40px;
  align-items: center;
  gap: 10px;
}

.project-list__progress strong {
  color: #4d5670;
  font-size: 13px;
}

.project-list__track {
  display: block;
  overflow: hidden;
  height: 7px;
  border-radius: 999px;
  background: #edf0f5;
}

.project-list__track i {
  display: block;
  height: 100%;
  border-radius: inherit;
}

.project-list__next {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 9px;
  font-size: 13px;
}

.project-list__next > i {
  width: 8px;
  height: 8px;
  flex: 0 0 auto;
  border-radius: 50%;
}

.project-list__next span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.project-list__updated {
  color: #66708a;
  font-size: 13px;
  white-space: nowrap;
}

.project-list__menu {
  display: grid;
  place-items: center;
}

.project-row-menu {
  display: grid;
  width: 28px;
  height: 28px;
  padding: 0;
  place-items: center;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: #31405f;
  cursor: pointer;
}

.project-row-menu:hover,
.project-row-menu:focus-visible {
  background: #f0f2f7;
  outline: none;
}

.project-list__track .purple,
.project-list__next .purple {
  background: #635bff;
}

.project-list__track .green,
.project-list__next .green {
  background: #18aa55;
}

.project-list__track .orange,
.project-list__next .orange {
  background: #ff941f;
}

.project-list__track .blue,
.project-list__next .blue {
  background: #3f91f7;
}

.project-list__track .red,
.project-list__next .red {
  background: #ff6278;
}

.today-task-row {
  display: flex;
  min-height: 47px;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  border-bottom: 1px solid var(--ui-border);
}

.today-task-row--button {
  width: 100%;
  padding: 0;
  border: 0;
  border-bottom: 1px solid var(--ui-border);
  background: transparent;
  color: var(--ui-text);
  cursor: pointer;
  font: inherit;
  text-align: left;
}

.today-task-row--button:hover {
  color: var(--ui-primary);
}

.today-task-row:last-child {
  border-bottom: 0;
}

.today-task-checkbox {
  min-width: 0;
  flex: 1;
}

.today-task-checkbox :deep(.el-checkbox__inner) {
  width: 18px;
  height: 18px;
  border-color: #c7cedd;
  border-radius: 4px;
}

.today-task-checkbox :deep(.el-checkbox__inner::after) {
  top: 2px;
  left: 6px;
  width: 4px;
  height: 8px;
}

.today-task-checkbox :deep(.el-checkbox__input.is-checked .el-checkbox__inner) {
  border-color: var(--ui-primary);
  background: var(--ui-primary);
}

.today-task-checkbox :deep(.el-checkbox__label) {
  overflow: hidden;
  padding-left: 10px;
  color: #1f2940;
  font-size: 14px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.today-task-row.is-completed .today-task-checkbox :deep(.el-checkbox__label) {
  color: #8a92a8;
  text-decoration: line-through;
}

.today-task-row.is-completed .tag-soft {
  opacity: 0.55;
}

@media (max-width: 1450px) and (min-width: 761px) {
  .project-list__head,
  .project-list__row {
    grid-template-columns: minmax(220px, 2fr) minmax(120px, 0.9fr) minmax(145px, 1.1fr) 28px;
  }

  .project-list__updated {
    display: none;
  }
}

@media (max-width: 1180px) and (min-width: 761px) {
  .dashboard-metrics {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 760px) {
  .project-list__head {
    display: none;
  }

  .project-list__row {
    grid-template-areas:
      'identity menu'
      'progress progress'
      'next updated';
    grid-template-columns: minmax(0, 1fr) auto;
    row-gap: 10px;
    min-height: 0;
    padding: 12px 0;
  }

  .project-list__identity {
    grid-area: identity;
  }

  .project-list__progress {
    grid-area: progress;
    grid-template-columns: minmax(0, 1fr) 40px;
  }

  .project-list__next {
    grid-area: next;
  }

  .project-list__updated {
    display: block;
    grid-area: updated;
  }

  .project-list__menu {
    grid-area: menu;
  }
}
</style>
