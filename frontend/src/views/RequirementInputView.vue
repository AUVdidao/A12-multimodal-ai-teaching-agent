<template>
  <section class="page">
    <header class="page__header">
      <h2 class="page__title">教学需求输入</h2>
      <p class="page__description">
        教师将在这里输入课题、教学目标、重难点和互动偏好。当前页面只承接 TA-006 主流程入口。
      </p>
    </header>

    <StatusCard
      title="已进入需求输入阶段"
      :description="stageDescription"
    />

    <div class="page__actions">
      <el-button type="primary" @click="router.push(dialogRoute)">
        下一步：智能澄清对话
      </el-button>
      <el-button @click="router.push('/projects')">返回项目列表</el-button>
      <el-button @click="router.push('/')">返回首页</el-button>
    </div>
  </section>
</template>

<script setup lang="ts">
import StatusCard from '@/components/StatusCard.vue';
import { computed } from 'vue';
import { useRoute, useRouter } from 'vue-router';

const route = useRoute();
const router = useRouter();

const stageDescription = computed(() => {
  const projectId = route.query.projectId;
  if (projectId) {
    return `项目 ${projectId} 已完成生成模式保存，可继续接入 TA-005 Mock AI Workflow 进行需求澄清。`;
  }
  return '当前仅保留流程入口，真实表单提交与草稿保存由后续任务实现。';
});

const dialogRoute = computed(() => {
  const projectId = route.query.projectId;
  if (!projectId) {
    return { path: '/dialog' };
  }
  return {
    path: '/dialog',
    query: { projectId },
  };
});
</script>
