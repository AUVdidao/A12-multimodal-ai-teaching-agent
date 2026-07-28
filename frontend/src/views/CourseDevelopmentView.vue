<template>
  <section class="page workspace-overview">
    <header class="page-hero">
      <div>
        <h2>开发概览</h2>
        <p>围绕教学需求、参考资料和内容生成，推进当前课程开发工作。</p>
      </div>
      <div class="page-actions">
        <el-button type="primary" @click="router.push({ name: 'project-create' })">新建教学项目</el-button>
      </div>
    </header>

    <section class="panel">
      <div class="panel__header">
        <h3>最近项目</h3>
        <el-button text type="primary" @click="router.push({ name: 'projects' })">查看全部</el-button>
      </div>
      <div v-if="loading" class="workspace-overview__loading">正在加载项目…</div>
      <div v-else-if="projects.length" class="workspace-overview__list">
        <button v-for="project in projects" :key="project.id" class="workspace-project" type="button" @click="openProject(project.id)">
          <span>
            <strong>{{ project.projectName }}</strong>
            <small>{{ project.subtitle || project.chapterTitle || '继续完善教学项目' }}</small>
          </span>
          <span class="workspace-project__next">{{ project.nextAction || '继续项目' }} <b aria-hidden="true">→</b></span>
        </button>
      </div>
      <el-empty v-else description="暂时没有可继续的课程开发项目" :image-size="74">
        <el-button type="primary" @click="router.push({ name: 'project-create' })">新建教学项目</el-button>
      </el-empty>
    </section>
  </section>
</template>

<script setup lang="ts">
import { getTeacherWorkspace, type TeacherWorkspace } from '@/api/workspace';
import { computed, onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';

const router = useRouter();
const workspace = ref<TeacherWorkspace>();
const loading = ref(true);
const projects = computed(() => workspace.value?.continueProjects.slice(0, 5) || []);

function openProject(projectId: number) {
  void router.push({ name: 'project-overview', params: { projectId } });
}

onMounted(async () => {
  try {
    workspace.value = await getTeacherWorkspace();
  } finally {
    loading.value = false;
  }
});
</script>

<style scoped>
.workspace-overview__loading { color: var(--color-text-secondary); padding: 18px 0; }
.workspace-overview__list { display: grid; }
.workspace-project { display: flex; justify-content: space-between; gap: 24px; padding: 17px 0; border: 0; border-bottom: 1px solid var(--color-border); background: transparent; text-align: left; cursor: pointer; }
.workspace-project:last-child { border-bottom: 0; }
.workspace-project strong, .workspace-project small { display: block; }
.workspace-project strong { color: var(--color-text); font-size: 15px; }
.workspace-project small { margin-top: 5px; color: var(--color-text-secondary); font-size: 13px; }
.workspace-project__next { color: var(--color-primary); font-size: 14px; font-weight: 600; white-space: nowrap; }
.workspace-project__next b { margin-left: 7px; }
</style>
