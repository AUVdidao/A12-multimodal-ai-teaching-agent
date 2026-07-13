<template>
  <section class="page">
    <ProjectContextHeader :project="project" />
    <ProjectWorkspaceNav :project-id="project.id" />

    <section class="page-hero">
      <div>
        <h2>方案预览与修改</h2>
        <p>当前为前端预览壳层。真实 PPT、教案和互动内容生成将在后端合并后接入。</p>
      </div>
      <div class="page-actions">
        <el-button @click="router.push(`/projects/${project.id}/plan`)">返回生成计划</el-button>
        <el-button type="primary" @click="router.push(`/projects/${project.id}/export`)">进入导出</el-button>
      </div>
    </section>

    <div class="grid cols-2">
      <section class="panel">
        <h3>PPT 课件预览</h3>
        <div class="preview-frame">
          <strong>人工智能基础概念与应用</strong>
          <p>1. 导入：生活中的 AI 场景</p>
          <p>2. 概念：什么是人工智能</p>
          <p>3. 案例：机器学习如何工作</p>
        </div>
      </section>
      <section class="panel">
        <h3>修改意见</h3>
        <el-input type="textarea" :rows="8" placeholder="例如：增加一个医疗场景案例，降低算法公式比例。" />
        <div class="page-actions">
          <el-button disabled type="primary">提交修改（后续开放）</el-button>
        </div>
      </section>
    </div>
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
</script>

<style scoped>
.preview-frame {
  display: grid;
  min-height: 320px;
  align-content: center;
  gap: 14px;
  padding: 34px;
  border: 1px solid var(--ui-border);
  border-radius: 12px;
  background: linear-gradient(135deg, #f8fafd, #eef4ff);
}
</style>
