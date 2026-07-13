<template>
  <section class="page">
    <ProjectContextHeader :project="project" />
    <ProjectWorkspaceNav :project-id="project.id" />

    <section class="page-hero">
      <div>
        <h2>版本与导出</h2>
        <p>当前仅完成前端页面壳层。真实文件打包、版本记录和下载接口在 M4 接入。</p>
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
      <el-table :data="versions">
        <el-table-column prop="version" label="版本" width="120" />
        <el-table-column prop="desc" label="说明" />
        <el-table-column prop="time" label="时间" width="180" />
      </el-table>
    </section>
  </section>
</template>

<script setup lang="ts">
import ProjectContextHeader from '@/components/ProjectContextHeader.vue';
import ProjectWorkspaceNav from '@/components/ProjectWorkspaceNav.vue';
import { getDemoProject } from '@/mock/demo';
import { useRoute, useRouter } from 'vue-router';

const route = useRoute();
const router = useRouter();
const project = getDemoProject(route.params.projectId as string);
const exports = [
  { name: 'PPTX', desc: '教学课件文件' },
  { name: 'DOCX', desc: 'Word 教案文件' },
  { name: '互动内容包', desc: '课堂互动与测评资源' },
];
const versions = [
  { version: 'v0.1', desc: '前端预览壳层版本', time: '2026-07-13 15:00' },
  { version: 'v0.2', desc: '后端合并后生成真实版本', time: '待接入' },
];
</script>
