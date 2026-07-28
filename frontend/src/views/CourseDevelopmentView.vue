<template>
  <section class="page workspace-overview">
    <header class="page-hero">
      <div>
        <h2>开发概览</h2>
        <p>围绕教学需求、参考资料和内容生成，推进当前课程开发工作。</p>
      </div>
      <div class="page-actions">
        <el-button type="primary" :icon="Plus" @click="router.push({ name: 'project-create' })">新建教学项目</el-button>
      </div>
    </header>

    <section class="panel">
      <div class="panel__header">
        <div>
          <h3>最近项目</h3>
          <p>从后端返回的真实项目进度继续下一项工作。</p>
        </div>
        <el-button text type="primary" @click="router.push({ name: 'projects' })">查看全部</el-button>
      </div>
      <StatePanel
        v-if="loading"
        type="loading"
        title="正在加载课程开发项目"
        description="正在读取项目进度与下一步操作。"
      />
      <StatePanel
        v-else-if="errorMessage"
        type="error"
        title="课程开发项目读取失败"
        :description="errorMessage"
      >
        <template #action>
          <el-button type="primary" :icon="Refresh" @click="loadWorkspace">重新加载</el-button>
        </template>
      </StatePanel>
      <div v-else-if="projects.length" class="workspace-overview__list">
        <button
          v-for="project in projects"
          :key="project.id"
          class="workspace-project"
          type="button"
          @click="openProject(project)"
        >
          <span>
            <strong>{{ project.projectName }}</strong>
            <small>{{ project.subtitle || project.chapterTitle }}</small>
          </span>
          <span class="workspace-project__next">
            <small>下一步</small>
            {{ project.nextAction || '查看项目详情' }}
            <b aria-hidden="true">→</b>
          </span>
        </button>
      </div>
      <el-empty v-else description="暂时没有可继续的课程开发项目" :image-size="74">
        <el-button type="primary" @click="router.push({ name: 'project-create' })">新建教学项目</el-button>
      </el-empty>
    </section>
  </section>
</template>

<script setup lang="ts">
import { getTeacherWorkspace, type ProjectBrief, type TeacherWorkspace } from '@/api/workspace';
import StatePanel from '@/components/StatePanel.vue';
import { Plus, Refresh } from '@element-plus/icons-vue';
import { computed, onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';

const router = useRouter();
const workspace = ref<TeacherWorkspace>();
const loading = ref(true);
const errorMessage = ref('');
const projects = computed(() => workspace.value?.continueProjects.slice(0, 5) || []);

function openProject(project: ProjectBrief) {
  if (project.actionPath) {
    void router.push(project.actionPath);
    return;
  }
  void router.push({ name: 'project-overview', params: { projectId: project.id } });
}

async function loadWorkspace() {
  loading.value = true;
  errorMessage.value = '';
  try {
    workspace.value = await getTeacherWorkspace();
  } catch (error) {
    workspace.value = undefined;
    errorMessage.value = resolveError(error, '暂时无法读取课程开发项目，请稍后重试。');
  } finally {
    loading.value = false;
  }
}

function resolveError(error: unknown, fallback: string) {
  const message = (error as { response?: { data?: { message?: string } } }).response?.data?.message;
  return message && !/(Exception|java\.|Axios)/i.test(message) ? message : fallback;
}

onMounted(loadWorkspace);
</script>

<style scoped>
.panel__header > div { min-width: 0; }
.panel__header h3, .panel__header p { margin: 0; }
.panel__header p { margin-top: 5px; color: var(--color-text-muted); font-size: 13px; }
.workspace-overview__list { display: grid; }
.workspace-project { display: grid; grid-template-columns: minmax(0, 1fr) minmax(180px, auto); align-items: center; gap: 24px; padding: 17px 0; border: 0; border-bottom: 1px solid var(--color-border); background: transparent; text-align: left; cursor: pointer; }
.workspace-project:last-child { border-bottom: 0; }
.workspace-project strong, .workspace-project small { display: block; }
.workspace-project strong { color: var(--color-text); font-size: 15px; }
.workspace-project small { margin-top: 5px; color: var(--color-text-secondary); font-size: 13px; }
.workspace-project__next { color: var(--color-primary); font-size: 14px; font-weight: 600; text-align: right; }
.workspace-project__next small { margin: 0 0 3px; color: var(--color-text-muted); font-size: 11px; font-weight: 500; }
.workspace-project__next b { margin-left: 7px; }

@media (max-width: 620px) {
  .workspace-project { grid-template-columns: minmax(0, 1fr); gap: 10px; }
  .workspace-project__next { text-align: left; }
}
</style>
