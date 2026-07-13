<template>
  <section class="page" v-loading="loading">
    <template v-if="project">
    <ProjectContextHeader :project="project" />
    <ProjectWorkspaceNav :project-id="project.id" />

    <section class="page-hero">
      <div>
        <h2>教学内容生成</h2>
        <p>M3 内容生成接口尚未实现，当前页面明确保留阶段入口，不展示模拟成果。</p>
      </div>
      <div class="page-actions">
        <el-button @click="router.push(`/projects/${project.id}/intent`)">返回意图</el-button>
        <el-button type="primary" @click="router.push(`/projects/${project.id}/preview`)">查看预览</el-button>
      </div>
    </section>

    <div class="grid cols-3">
      <section v-for="block in blocks" :key="block.title" class="panel">
        <h3>{{ block.title }}</h3>
        <p>{{ block.desc }}</p>
        <ul>
          <li v-for="item in block.items" :key="item">{{ item }}</li>
        </ul>
        <span class="tag-soft warning">后续接入</span>
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
const blocks = [
  { title: 'PPT 课件', desc: '围绕课程导入、概念讲解、案例讨论和课堂总结组织页面。', items: ['课程导入', '核心概念', '典型案例', '课堂练习'] },
  { title: 'Word 教案', desc: '沉淀教学目标、教学流程、活动设计和评价方式。', items: ['教学目标', '重点难点', '教学流程', '评价标准'] },
  { title: '互动内容', desc: '为课堂问答、小组讨论和即时测评预留结构。', items: ['随堂问答', '小组讨论', '知识投票', '课后测评'] },
];

onMounted(async () => {
  try {
    project.value = (await getProjectWorkspaceOverview(projectId.value)).project;
  } finally {
    loading.value = false;
  }
});
</script>
