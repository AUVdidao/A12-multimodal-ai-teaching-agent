<template>
  <section class="page dashboard-page">
    <header class="dashboard-heading">
      <div><span>TEACHING WORKSPACE</span><h1>教师工作台</h1><p>{{ greeting }}，从最近的教学项目继续备课工作。</p></div>
    </header>

    <section class="dashboard-metrics" aria-label="项目数据摘要">
      <article v-for="metric in metrics" :key="metric.label" class="metric-card">
        <span>{{ metric.label }}</span><strong>{{ metric.value }}</strong><small>{{ metric.description }}</small>
      </article>
    </section>

    <section class="dashboard-continue" aria-labelledby="continue-title">
      <div class="dashboard-continue__heading"><span>继续工作</span><h2 id="continue-title">{{ latestProject ? latestProject.projectName : '从一个教学项目开始' }}</h2></div>
      <template v-if="latestProject"><p>{{ projectMeta(latestProject) }}</p><div class="dashboard-continue__footer"><StatusBadge :status="latestProject.status" /><span>更新于 {{ formatDate(latestProject.updatedAt) }}</span><el-button type="primary" @click="openNextTask(latestProject)">{{ nextTask(latestProject) }}</el-button></div></template>
      <template v-else-if="!loading"><p>创建项目后，这里会显示你最近一次未完成的备课任务。</p><el-button type="primary" @click="router.push('/projects/new')">新建教学项目</el-button></template>
      <el-skeleton v-else :rows="2" animated />
    </section>

    <div class="dashboard-grid">
      <section class="dashboard-section" aria-labelledby="tasks-title">
        <div class="section-heading"><div><span>待办事项</span><h2 id="tasks-title">需要继续的工作</h2></div></div>
        <StatePanel v-if="errorMessage" type="error" title="项目读取失败" :description="errorMessage"><template #action><el-button size="small" @click="loadProjects">重新加载</el-button></template></StatePanel>
        <StatePanel v-else-if="!loading && projects.length === 0" type="empty" title="还没有待办项目" description="创建教学项目后，可以在这里继续管理备课进度。" />
        <div v-else class="task-list">
          <article v-for="project in actionableProjects" :key="project.id" class="task-row">
            <div class="task-row__icon"><el-icon><EditPen /></el-icon></div>
            <div><strong>{{ nextTask(project) }}</strong><span>{{ project.projectName }} · {{ project.courseName }}</span></div>
            <el-button link type="primary" @click="openOverview(project)">查看</el-button>
          </article>
        </div>
      </section>

      <section class="dashboard-section dashboard-section--projects" aria-labelledby="recent-title">
        <div class="section-heading"><div><span>最近项目</span><h2 id="recent-title">教学项目</h2></div><el-button link type="primary" @click="router.push('/projects')">查看全部</el-button></div>
        <div class="recent-list">
          <article v-for="project in recentProjects" :key="project.id" class="recent-row" role="button" tabindex="0" @click="openOverview(project)" @keydown.enter="openOverview(project)">
            <div><strong>{{ project.projectName }}</strong><span>{{ projectMeta(project) }}</span></div><div class="recent-row__meta"><StatusBadge :status="project.status" /><time>{{ formatDate(project.updatedAt) }}</time></div>
          </article>
        </div>
      </section>
    </div>

    <footer class="dashboard-status" :class="`dashboard-status--${app.systemStatus}`"><span class="dashboard-status__dot" /><span>{{ app.systemStatusLabel }}</span><el-button text :icon="Refresh" :loading="app.systemStatus === 'checking'" aria-label="重新检查服务状态" @click="app.checkHealth" /></footer>
  </section>
</template>

<script setup lang="ts">
import { listProjects, type TeachingProject } from '@/api/projects';
import StatePanel from '@/components/StatePanel.vue';
import StatusBadge from '@/components/StatusBadge.vue';
import { EditPen, Refresh } from '@element-plus/icons-vue';
import { computed, onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { useAppStore } from '@/stores/app';
import { getProjectListNextAction } from '@/utils/projectNextAction';

const router = useRouter();
const projects = ref<TeachingProject[]>([]);
const loading = ref(false);
const errorMessage = ref('');
const app = useAppStore();
const recentProjects = computed(() => projects.value.slice(0, 5));
const latestProject = computed(() => projects.value[0]);
const actionableProjects = computed(() => projects.value.slice(0, 3));
const activeProjects = computed(() => projects.value.filter((project) => !['FINALIZED', 'INTENT_CONFIRMED'].includes(project.status)));
const metrics = computed(() => [
  { label: '教学项目', value: projects.value.length, description: '当前已创建项目' },
  { label: '待继续项目', value: activeProjects.value.length, description: '按真实项目状态派生' },
  { label: '已确认需求', value: projects.value.filter((project) => ['REQUIREMENT_CONFIRMED', 'MATERIAL_READY', 'INTENT_CONFIRMED', 'GENERATED', 'FINALIZED'].includes(project.status)).length, description: '可进入资料增强的项目' },
]);
const greeting = computed(() => {
  const hour = new Date().getHours();
  if (hour < 12) return '上午好';
  if (hour < 18) return '下午好';
  return '晚上好';
});

onMounted(loadProjects);
async function loadProjects() { loading.value = true; errorMessage.value = ''; try { projects.value = await listProjects(); } catch { errorMessage.value = '暂时无法读取项目，请检查服务后重试。'; } finally { loading.value = false; } }
function openOverview(project: TeachingProject) { router.push(`/projects/${project.id}/overview`); }
function openNextTask(project: TeachingProject) { router.push(getProjectListNextAction(project.id, project.status).path); }
function nextTask(project: TeachingProject) { return getProjectListNextAction(project.id, project.status).label; }
function projectMeta(project: TeachingProject) { return [project.targetStudents, project.courseName, project.chapterTitle].filter(Boolean).join(' · '); }
function formatDate(value: string) { return new Intl.DateTimeFormat('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }).format(new Date(value)); }
</script>

<style scoped>
.dashboard-heading, .section-heading, .dashboard-continue__footer, .recent-row, .task-row, .dashboard-status { display: flex; align-items: center; justify-content: space-between; gap: 16px; }
.dashboard-heading { margin-bottom: 20px; }.dashboard-heading span { color: var(--color-primary); font-size: 11px; font-weight: 800; }.dashboard-heading h1, .dashboard-heading p, h2, p { margin: 0; }.dashboard-heading h1 { margin-top: 5px; font-size: 28px; }.dashboard-heading p { margin-top: 5px; color: var(--color-text-secondary); }
.dashboard-metrics { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 16px; margin-bottom: 18px; }.metric-card { min-height: 112px; padding: 18px 20px; border: 1px solid var(--color-border); border-radius: var(--radius-lg); background: var(--color-surface); box-shadow: var(--shadow-card); }.metric-card span, .metric-card strong, .metric-card small { display: block; }.metric-card span, .metric-card small { color: var(--color-text-muted); font-size: 11px; }.metric-card strong { margin: 8px 0 4px; color: var(--color-text); font-size: 29px; line-height: 1; }
.dashboard-continue { padding: 22px 24px; border: 1px solid var(--color-primary-border); border-radius: var(--radius-lg); background: linear-gradient(100deg, var(--color-primary-soft), #f8faff 68%); }.dashboard-continue__heading > span, .section-heading span { color: var(--color-primary); font-size: 11px; font-weight: 750; }.dashboard-continue h2 { margin-top: 5px; font-size: 20px; }.dashboard-continue > p { margin-top: 7px; color: var(--color-text-secondary); }.dashboard-continue__footer { margin-top: 18px; }.dashboard-continue__footer > span { margin-right: auto; color: var(--color-text-muted); font-size: 11px; }
.dashboard-grid { display: grid; grid-template-columns: minmax(0, .9fr) minmax(0, 1.1fr); gap: 28px; margin-top: 28px; }.dashboard-section { min-width: 0; }.section-heading { margin-bottom: 12px; }.section-heading h2 { margin-top: 3px; font-size: 17px; }
.task-list, .recent-list { border-top: 1px solid var(--color-border); }.task-row, .recent-row { min-height: 64px; padding: 10px 2px; border-bottom: 1px solid var(--color-border); }.task-row__icon { display: grid; width: 32px; height: 32px; place-items: center; border-radius: var(--radius-md); background: var(--color-ai-soft); color: var(--color-ai); }.task-row > div:nth-child(2) { margin-right: auto; }.task-row strong, .task-row span, .recent-row strong, .recent-row span { display: block; }.task-row span, .recent-row span, time { margin-top: 3px; color: var(--color-text-muted); font-size: 11px; }.recent-row { cursor: pointer; }.recent-row:hover { color: var(--color-primary); }.recent-row__meta { display: flex; align-items: flex-end; flex-direction: column; gap: 4px; }
.dashboard-status { justify-content: flex-start; margin-top: 28px; color: var(--color-text-muted); font-size: 11px; }.dashboard-status__dot { width: 7px; height: 7px; border-radius: 50%; background: currentColor; }.dashboard-status--healthy { color: var(--color-success); }.dashboard-status--unavailable { color: var(--color-danger); }
@media (max-width: 860px) { .dashboard-grid { grid-template-columns: 1fr; } .dashboard-metrics { grid-template-columns: 1fr; } } @media (max-width: 560px) { .dashboard-heading, .dashboard-continue__footer { align-items: stretch; flex-direction: column; }.dashboard-continue__footer > span { margin-right: 0; }.recent-row__meta { align-items: flex-start; }.recent-row { align-items: flex-start; flex-direction: column; } }
</style>
