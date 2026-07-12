<template>
  <section class="page dashboard-page">
    <header class="dashboard-greeting">
      <div><h1>{{ greeting }}，教师</h1><p>今天是 {{ todayLabel }}，继续处理最近的教学项目。</p></div>
    </header>

    <section class="dashboard-metrics" aria-label="项目数据摘要">
      <article v-for="metric in metrics" :key="metric.label" class="metric-card">
        <el-icon class="metric-card__icon"><component :is="metric.icon" /></el-icon>
        <div><span>{{ metric.label }}</span><strong>{{ metric.value }}</strong><small>{{ metric.description }}</small></div>
      </article>
    </section>

    <section class="dashboard-continue surface-panel" aria-labelledby="continue-title">
      <div class="dashboard-continue__heading"><span>继续项目</span><h2 id="continue-title">{{ latestProject ? latestProject.projectName : '从一个教学项目开始' }}</h2></div>
      <template v-if="latestProject"><p>{{ projectMeta(latestProject) }}</p><div class="dashboard-continue__footer"><StatusBadge :status="latestProject.status" /><span>更新于 {{ formatDate(latestProject.updatedAt) }}</span><el-button type="primary" @click="openNextTask(latestProject)">{{ nextTask(latestProject) }}</el-button></div></template>
      <template v-else-if="!loading"><p>创建项目后，这里会显示你最近一次未完成的备课任务。</p><el-button type="primary" @click="router.push('/projects/new')">新建教学项目</el-button></template>
      <el-skeleton v-else :rows="2" animated />
    </section>

    <div class="dashboard-grid">
      <section class="dashboard-section surface-panel" aria-labelledby="tasks-title">
        <div class="section-heading"><div><h2 id="tasks-title">待继续项目</h2><span>按项目当前状态整理</span></div></div>
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

      <section class="dashboard-section dashboard-section--projects surface-panel" aria-labelledby="recent-title">
        <div class="section-heading"><div><h2 id="recent-title">最近项目</h2><span>最近更新的教学项目</span></div><el-button link type="primary" @click="router.push('/projects')">查看全部</el-button></div>
        <div class="recent-list">
          <article v-for="project in recentProjects" :key="project.id" class="recent-row" role="button" tabindex="0" @click="openOverview(project)" @keydown.enter="openOverview(project)">
            <div><strong>{{ project.projectName }}</strong><span>{{ projectMeta(project) }}</span></div><div class="recent-row__meta"><StatusBadge :status="project.status" /><time>{{ formatDate(project.updatedAt) }}</time></div>
          </article>
        </div>
      </section>
    </div>

  </section>
</template>

<script setup lang="ts">
import { listProjects, type TeachingProject } from '@/api/projects';
import StatePanel from '@/components/StatePanel.vue';
import StatusBadge from '@/components/StatusBadge.vue';
import { CircleCheck, EditPen, FolderOpened, Timer } from '@element-plus/icons-vue';
import { computed, onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { getProjectListNextAction } from '@/utils/projectNextAction';

const router = useRouter();
const projects = ref<TeachingProject[]>([]);
const loading = ref(false);
const errorMessage = ref('');
const recentProjects = computed(() => projects.value.slice(0, 5));
const activeProjects = computed(() => projects.value.filter((project) => !['FINALIZED', 'INTENT_CONFIRMED'].includes(project.status)));
const latestProject = computed(() => activeProjects.value[0] || projects.value[0]);
const actionableProjects = computed(() => activeProjects.value.slice(0, 4));
const metrics = computed(() => [
  { label: '教学项目', value: projects.value.length, description: '全部备课项目', icon: FolderOpened },
  { label: '待继续项目', value: activeProjects.value.length, description: '仍有步骤待完成', icon: Timer },
  { label: '已确认需求', value: projects.value.filter((project) => ['REQUIREMENT_CONFIRMED', 'MATERIAL_READY', 'INTENT_CONFIRMED', 'GENERATED', 'FINALIZED'].includes(project.status)).length, description: '已进入资料增强', icon: CircleCheck },
]);
const greeting = computed(() => {
  const hour = new Date().getHours();
  if (hour < 12) return '上午好';
  if (hour < 18) return '下午好';
  return '晚上好';
});
const todayLabel = computed(() => new Intl.DateTimeFormat('zh-CN', { year: 'numeric', month: 'long', day: 'numeric', weekday: 'long' }).format(new Date()));

onMounted(loadProjects);
async function loadProjects() { loading.value = true; errorMessage.value = ''; try { projects.value = await listProjects(); } catch { errorMessage.value = '暂时无法读取项目，请检查服务后重试。'; } finally { loading.value = false; } }
function openOverview(project: TeachingProject) { router.push(`/projects/${project.id}/overview`); }
function openNextTask(project: TeachingProject) { router.push(getProjectListNextAction(project.id, project.status).path); }
function nextTask(project: TeachingProject) { return getProjectListNextAction(project.id, project.status).label; }
function projectMeta(project: TeachingProject) { return [project.targetStudents, project.courseName, project.chapterTitle].filter(Boolean).join(' · '); }
function formatDate(value: string) { return new Intl.DateTimeFormat('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }).format(new Date(value)); }
</script>

<style scoped>
.dashboard-greeting, .section-heading, .dashboard-continue__footer, .recent-row, .task-row { display: flex; align-items: center; justify-content: space-between; gap: 16px; }
.dashboard-greeting { margin: 2px 0 18px; }
.dashboard-greeting h1, .dashboard-greeting p, h2, p { margin: 0; }
.dashboard-greeting h1 { font-size: 24px; }
.dashboard-greeting p { margin-top: 5px; color: var(--color-text-secondary); font-size: 13px; }
.dashboard-metrics { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 16px; margin-bottom: 16px; }
.metric-card { display: flex; align-items: center; min-height: 108px; gap: 14px; padding: 16px 18px; border: 1px solid var(--color-border); border-radius: var(--radius-lg); background: var(--color-surface); box-shadow: var(--shadow-card); }
.metric-card__icon { flex: 0 0 auto; width: 42px; height: 42px; border-radius: var(--radius-lg); background: var(--color-primary-soft); color: var(--color-primary); font-size: 21px; }
.metric-card span, .metric-card strong, .metric-card small { display: block; }
.metric-card span, .metric-card small { color: var(--color-text-muted); font-size: 11px; }
.metric-card strong { margin: 4px 0; color: var(--color-text); font-size: 28px; line-height: 1; }
.dashboard-continue { min-height: 156px; padding: 20px 22px; border-color: var(--color-primary-border); border-left: 3px solid var(--color-primary); }
.dashboard-continue__heading > span { color: var(--color-primary); font-size: 11px; font-weight: 750; }
.dashboard-continue h2 { margin-top: 5px; font-size: 19px; }
.dashboard-continue > p { margin-top: 7px; color: var(--color-text-secondary); }
.dashboard-continue__footer { margin-top: 18px; }
.dashboard-continue__footer > span { margin-right: auto; color: var(--color-text-muted); font-size: 11px; }
.dashboard-grid { display: grid; grid-template-columns: minmax(0, .9fr) minmax(0, 1.1fr); gap: 16px; margin-top: 16px; }
.dashboard-section { min-width: 0; padding: 18px 20px; }
.section-heading { margin-bottom: 10px; }
.section-heading h2 { font-size: 16px; }
.section-heading span { display: block; margin-top: 3px; color: var(--color-text-muted); font-size: 11px; }
.task-list, .recent-list { border-top: 1px solid var(--color-border); }
.task-row, .recent-row { min-height: 58px; padding: 9px 0; border-bottom: 1px solid var(--color-border); }
.task-row:last-child, .recent-row:last-child { border-bottom: 0; }
.task-row__icon { display: grid; width: 30px; height: 30px; place-items: center; border-radius: var(--radius-md); background: var(--color-ai-soft); color: var(--color-ai); }
.task-row > div:nth-child(2) { margin-right: auto; }
.task-row strong, .task-row span, .recent-row strong, .recent-row span { display: block; }
.task-row span, .recent-row span, time { margin-top: 3px; color: var(--color-text-muted); font-size: 11px; }
.recent-row { cursor: pointer; }
.recent-row:hover { color: var(--color-primary); }
.recent-row__meta { display: flex; align-items: flex-end; flex-direction: column; gap: 4px; }
@media (max-width: 860px) { .dashboard-grid { grid-template-columns: 1fr; } .dashboard-metrics { grid-template-columns: 1fr; } }
@media (max-width: 560px) { .dashboard-greeting, .dashboard-continue__footer { align-items: stretch; flex-direction: column; } .dashboard-continue__footer > span { margin-right: 0; } .recent-row__meta { align-items: flex-start; } .recent-row { align-items: flex-start; flex-direction: column; } }
</style>
