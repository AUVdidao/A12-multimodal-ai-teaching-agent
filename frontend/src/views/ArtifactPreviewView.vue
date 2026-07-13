<template>
  <section class="page" v-loading="loading">
    <template v-if="project">
    <ProjectContextHeader :project="project" />
    <ProjectWorkspaceNav :project-id="project.id" />

    <section class="page-hero">
      <div>
        <h2>方案预览与修改</h2>
        <p>M3/M4 生成与版本接口尚未实现，本页不展示模拟课件或虚构版本。</p>
      </div>
      <div class="page-actions">
        <el-button @click="router.push(`/projects/${project.id}/plan`)">返回生成计划</el-button>
        <el-button type="primary" @click="router.push(`/projects/${project.id}/export`)">进入导出</el-button>
      </div>
    </section>

    <div class="grid cols-2">
      <section class="panel">
        <h3>PPT 课件预览</h3>
        <el-empty description="尚无生成成果，完成 M3 内容生成后将在这里预览" />
      </section>
      <section class="panel">
        <h3>修改意见</h3>
        <el-input type="textarea" :rows="8" placeholder="例如：增加一个医疗场景案例，降低算法公式比例。" />
        <div class="page-actions">
          <el-button disabled type="primary">提交修改（后续开放）</el-button>
        </div>
      </section>
    </div>
    </template>
  </section>
</template>

<script setup lang="ts">
import ProjectContextHeader from '@/components/ProjectContextHeader.vue';
import ProjectWorkspaceNav from '@/components/ProjectWorkspaceNav.vue';
import { getProjectWorkspaceOverview, type ProjectBrief } from '@/api/workspace';
import { computed, onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';

const route = useRoute();
const router = useRouter();
const projectId = computed(() => Number(route.params.projectId));
const project = ref<ProjectBrief>();
const loading = ref(true);

onMounted(async () => {
  try {
    project.value = (await getProjectWorkspaceOverview(projectId.value)).project;
  } finally {
    loading.value = false;
  }
});
</script>

<style scoped>
</style>
