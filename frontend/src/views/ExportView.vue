<template>
  <section class="page" v-loading="loading">
    <template v-if="project">
    <ProjectContextHeader :project="project" />
    <ProjectWorkspaceNav :project-id="project.id" />

    <section class="page-hero">
      <div>
        <h2>版本与导出</h2>
        <p>M4 文件打包、版本记录和下载接口尚未实现，本页不展示模拟导出记录。</p>
      </div>
      <el-button @click="router.push(`/projects/${project.id}`)">返回项目概览</el-button>
    </section>

    <div class="grid cols-3">
      <section v-for="item in exports" :key="item.name" class="panel">
        <h3>{{ item.name }}</h3>
        <p>{{ item.desc }}</p>
        <span class="tag-soft warning">未导出</span>
        <div class="page-actions">
          <el-button disabled>下载（后续开放）</el-button>
        </div>
      </section>
    </div>

    <section class="panel" style="margin-top: 16px">
      <h3>版本记录</h3>
      <el-table :data="[]">
        <el-table-column prop="version" label="版本" width="120" />
        <el-table-column prop="desc" label="说明" />
        <el-table-column prop="time" label="时间" width="180" />
      </el-table>
      <el-empty description="尚无真实版本记录" :image-size="72" />
    </section>
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
const exports = [
  { name: 'PPTX', desc: '教学课件文件' },
  { name: 'DOCX', desc: 'Word 教案文件' },
  { name: '互动内容包', desc: '课堂互动与测评资源' },
];

onMounted(async () => {
  try {
    project.value = (await getProjectWorkspaceOverview(projectId.value)).project;
  } finally {
    loading.value = false;
  }
});
</script>
