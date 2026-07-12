<template>
  <section class="page projects-page">
    <header class="projects-heading"><div><h1>教学项目</h1><p>查看、筛选并继续你的备课工作。</p></div><el-button type="primary" :icon="Plus" @click="router.push('/projects/new')">新建教学项目</el-button></header>
    <section class="project-toolbar" aria-label="项目筛选">
      <el-input v-model="keyword" :prefix-icon="Search" clearable placeholder="搜索项目、课程或课题" />
      <el-select v-model="statusFilter" clearable placeholder="全部状态"><el-option v-for="status in availableStatuses" :key="status" :label="statusLabel(status)" :value="status" /></el-select>
      <el-select v-model="sortOrder"><el-option label="最近更新" value="updated" /><el-option label="创建时间" value="created" /></el-select>
      <el-button :icon="Refresh" :loading="loading" @click="loadProjects">刷新</el-button>
    </section>
    <div v-if="loading" class="surface-panel project-loading"><el-skeleton :rows="6" animated /></div>
    <StatePanel v-else-if="errorMessage" type="error" title="项目列表读取失败" :description="errorMessage"><template #action><el-button size="small" @click="loadProjects">重新加载</el-button></template></StatePanel>
    <StatePanel v-else-if="projects.length === 0" type="empty" title="还没有教学项目" description="创建项目后即可开始记录教学需求。"><template #action><el-button size="small" type="primary" @click="router.push('/projects/new')">创建项目</el-button></template></StatePanel>
    <StatePanel v-else-if="filteredProjects.length === 0" type="empty" title="没有匹配的项目" description="换一个关键词或清除筛选条件试试。" />
    <template v-else><div class="project-table-wrap"><el-table :data="filteredProjects" row-key="id" class="project-table" @row-click="openOverview"><el-table-column label="项目名称" min-width="220"><template #default="{ row }"><strong>{{ row.projectName }}</strong><span class="project-table__sub">{{ row.courseName }}</span></template></el-table-column><el-table-column label="学段 / 学科" min-width="170"><template #default="{ row }">{{ row.targetStudents || '待补充' }} · {{ row.courseName }}</template></el-table-column><el-table-column prop="chapterTitle" label="课题" min-width="170" /><el-table-column label="状态" width="130"><template #default="{ row }"><StatusBadge :status="row.status" /></template></el-table-column><el-table-column label="最近更新" width="150"><template #default="{ row }">{{ formatDate(row.updatedAt) }}</template></el-table-column><el-table-column label="操作" width="110" fixed="right"><template #default="{ row }"><el-button link type="primary" @click.stop="openOverview(row)">继续</el-button></template></el-table-column></el-table></div><div class="project-mobile-list"><article v-for="project in filteredProjects" :key="project.id" class="project-mobile-row" @click="openOverview(project)"><div><strong>{{ project.projectName }}</strong><span>{{ projectMeta(project) }}</span></div><StatusBadge :status="project.status" /></article></div></template>
  </section>
</template>

<script setup lang="ts">
import { listProjects, type TeachingProject } from '@/api/projects';
import StatePanel from '@/components/StatePanel.vue';
import StatusBadge from '@/components/StatusBadge.vue';
import { Plus, Refresh, Search } from '@element-plus/icons-vue';
import { computed, onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
const router = useRouter(); const projects = ref<TeachingProject[]>([]); const loading = ref(false); const errorMessage = ref(''); const keyword = ref(''); const statusFilter = ref(''); const sortOrder = ref('updated');
const availableStatuses = computed(() => [...new Set(projects.value.map((item) => item.status))]);
const filteredProjects = computed(() => projects.value.filter((item) => { const term = keyword.value.trim().toLowerCase(); return (!statusFilter.value || item.status === statusFilter.value) && (!term || [item.projectName, item.courseName, item.chapterTitle, item.targetStudents].filter(Boolean).join(' ').toLowerCase().includes(term)); }).sort((a, b) => new Date(sortOrder.value === 'created' ? b.createdAt : b.updatedAt).getTime() - new Date(sortOrder.value === 'created' ? a.createdAt : a.updatedAt).getTime()));
onMounted(loadProjects); async function loadProjects() { loading.value = true; errorMessage.value = ''; try { projects.value = await listProjects(); } catch { errorMessage.value = '暂时无法同步项目数据，请检查后端服务后重试。'; } finally { loading.value = false; } }
function openOverview(project: TeachingProject) { router.push(`/projects/${project.id}/overview`); } function projectMeta(project: TeachingProject) { return [project.targetStudents, project.courseName, project.chapterTitle].filter(Boolean).join(' · '); } function formatDate(value: string) { return new Intl.DateTimeFormat('zh-CN', { year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date(value)); } function statusLabel(status: string) { return ({ CREATED: '已创建', REQUIREMENT_CONFIRMED: '需求已确认', MATERIAL_READY: '资料已就绪', INTENT_CONFIRMED: '意图已确认' } as Record<string, string>)[status] || status; }
</script>

<style scoped>
.projects-heading, .project-toolbar, .project-mobile-row { display: flex; align-items: center; justify-content: space-between; gap: 16px; }.projects-heading { margin-bottom: 22px; }.projects-heading h1, .projects-heading p { margin: 0; }.projects-heading h1 { font-size: 27px; }.projects-heading p { margin-top: 4px; color: var(--color-text-secondary); }
.project-toolbar { display: grid; grid-template-columns: minmax(240px, 1fr) 160px 130px auto; margin-bottom: 16px; }.project-loading { padding: 24px; }.project-table-wrap { border: 1px solid var(--color-border); border-radius: var(--radius-lg); overflow: hidden; background: var(--color-surface); }.project-table :deep(.el-table__cell) { height: var(--row-height); }.project-table strong, .project-table__sub { display: block; }.project-table__sub { margin-top: 3px; color: var(--color-text-muted); font-size: 11px; }.project-mobile-list { display: none; }
@media (max-width: 900px) { .project-toolbar { grid-template-columns: minmax(200px, 1fr) 140px auto; }.project-toolbar .el-select:last-of-type { display: none; } } @media (max-width: 680px) { .projects-heading { align-items: stretch; flex-direction: column; }.project-toolbar { grid-template-columns: 1fr auto; }.project-toolbar .el-select { display: none; }.project-table-wrap { display: none; }.project-mobile-list { display: grid; gap: 8px; }.project-mobile-row { padding: 14px; border: 1px solid var(--color-border); border-radius: var(--radius-md); background: var(--color-surface); }.project-mobile-row strong, .project-mobile-row span { display: block; }.project-mobile-row span { margin-top: 4px; color: var(--color-text-muted); font-size: 11px; } }
</style>
