<template>
  <section class="page projects-page">
    <PageHeader eyebrow="教学项目" title="项目列表" description="查看项目状态并从安全的业务节点继续备课流程。">
      <template #actions><el-button type="primary" :icon="Plus" @click="router.push('/projects/new')">新建项目</el-button></template>
    </PageHeader>

    <div class="project-toolbar">
      <div><strong>{{ projects.length }}</strong><span>个本地教学项目</span></div>
      <el-button :icon="Refresh" text :loading="loading" @click="loadProjects">刷新</el-button>
    </div>

    <div v-if="loading" class="surface-panel project-loading" aria-live="polite">
      <el-skeleton :rows="5" animated />
    </div>
    <StatePanel v-else-if="errorMessage" type="error" title="项目列表读取失败" :description="errorMessage">
      <template #action><el-button size="small" type="primary" @click="loadProjects">重新加载</el-button></template>
    </StatePanel>
    <StatePanel v-else-if="projects.length === 0" type="empty" title="还没有教学项目" description="创建项目后即可选择生成模式并开始需求澄清。">
      <template #action><el-button size="small" type="primary" @click="router.push('/projects/new')">创建第一个项目</el-button></template>
    </StatePanel>

    <div v-else class="project-grid">
      <article v-for="project in projects" :key="project.id" class="project-card">
        <header>
          <StatusBadge :status="project.status" />
          <span class="project-card__id">项目 #{{ project.id }}</span>
        </header>
        <h2>{{ project.projectName }}</h2>
        <p>{{ project.courseName }} · {{ project.chapterTitle }}</p>
        <dl>
          <div><dt>授课对象</dt><dd>{{ project.targetStudents || '待补充' }}</dd></div>
          <div><dt>生成模式</dt><dd>{{ formatMode(project.modelMode) }}</dd></div>
          <div><dt>最近更新</dt><dd>{{ formatDate(project.updatedAt) }}</dd></div>
        </dl>
        <footer>
          <span>{{ nextStepLabel(project) }}</span>
          <el-button type="primary" plain :icon="Right" @click="continueProject(project)">继续项目</el-button>
        </footer>
      </article>
    </div>
  </section>
</template>

<script setup lang="ts">
import { listProjects, type TeachingProject } from '@/api/projects';
import PageHeader from '@/components/PageHeader.vue';
import StatePanel from '@/components/StatePanel.vue';
import StatusBadge from '@/components/StatusBadge.vue';
import { Plus, Refresh, Right } from '@element-plus/icons-vue';
import { onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';

const router = useRouter();
const loading = ref(false);
const projects = ref<TeachingProject[]>([]);
const errorMessage = ref('');

onMounted(loadProjects);

async function loadProjects() {
  loading.value = true;
  errorMessage.value = '';
  try {
    projects.value = await listProjects();
  } catch {
    projects.value = [];
    errorMessage.value = '暂时无法同步项目数据，请检查后端服务后重试。';
  } finally {
    loading.value = false;
  }
}

function continueProject(project: TeachingProject) {
  router.push(project.status === 'REQUIREMENT_CONFIRMED'
    ? `/projects/${project.id}/requirement-summary`
    : `/projects/${project.id}/mode`);
}

function nextStepLabel(project: TeachingProject) {
  return project.status === 'REQUIREMENT_CONFIRMED' ? '继续查看确认摘要' : '继续完善项目需求';
}

function formatMode(mode: string) {
  return ({ STANDARD: '标准模式', QUALITY: '高质量模式', ECONOMY: '经济模式' } as Record<string, string>)[mode] || '标准模式';
}

function formatDate(value: string) {
  if (!value) return '-';
  return new Intl.DateTimeFormat('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }).format(new Date(value));
}
</script>

<style scoped>
.project-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--color-border);
}

.project-toolbar strong {
  margin-right: 7px;
  font-size: 18px;
}

.project-toolbar span {
  color: var(--color-text-muted);
  font-size: 12px;
}

.project-loading {
  padding: 24px;
}

.project-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
}

.project-card {
  min-width: 0;
  padding: 20px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  background: var(--color-surface);
  box-shadow: var(--shadow-card);
  transition: border-color var(--transition-fast), box-shadow var(--transition-fast), transform var(--transition-fast);
}

.project-card:hover {
  border-color: var(--color-primary-border);
  box-shadow: var(--shadow-float);
  transform: translateY(-2px);
}

.project-card header,
.project-card footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.project-card__id {
  color: var(--color-text-muted);
  font-size: 11px;
}

.project-card h2 {
  margin: 18px 0 0;
  font-size: 18px;
  overflow-wrap: anywhere;
}

.project-card > p {
  margin: 6px 0 0;
  color: var(--color-text-secondary);
}

.project-card dl {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
  margin: 20px 0;
  padding: 15px 0;
  border-top: 1px solid var(--color-border);
  border-bottom: 1px solid var(--color-border);
}

.project-card dt,
.project-card dd {
  margin: 0;
}

.project-card dt {
  color: var(--color-text-muted);
  font-size: 10px;
}

.project-card dd {
  margin-top: 4px;
  color: var(--color-text);
  font-size: 12px;
  font-weight: 700;
  overflow-wrap: anywhere;
}

.project-card footer > span {
  color: var(--color-text-muted);
  font-size: 11px;
}

@media (max-width: 860px) {
  .project-grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 520px) {
  .project-card dl {
    grid-template-columns: 1fr 1fr;
  }

  .project-card footer {
    align-items: stretch;
    flex-direction: column;
  }
}
</style>
