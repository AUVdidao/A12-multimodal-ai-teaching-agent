<template>
  <section class="page">
    <header class="page__header">
      <h2 class="page__title">教学需求输入</h2>
      <p class="page__description">教师将在这里输入课题、年级、课时、教学目标和重点难点。</p>
    </header>
    <StatusCard title="需求输入页面骨架" description="当前仅保留流程入口，真实表单提交和草稿保存由后续任务实现。" />
    <div class="page__actions">
      <el-button type="primary" @click="router.push('/dialog')">下一步：智能澄清对话</el-button>
      <el-button @click="router.push('/')">返回首页</el-button>
    </div>
  </section>
</template>

<script setup lang="ts">
import StatusCard from '@/components/StatusCard.vue';
import { useRouter } from 'vue-router';

const router = useRouter();
</script>
