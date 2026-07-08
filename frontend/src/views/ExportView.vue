<template>
  <section class="page">
    <header class="page__header">
      <h2 class="page__title">文件导出</h2>
      <p class="page__description">后续将在这里下载 PPTX、DOCX 和互动内容包，完成比赛演示闭环。</p>
    </header>
    <StatusCard title="导出阶段骨架" description="当前仅展示导出入口，不生成真实文件。" />
    <div class="page__actions">
      <el-button type="primary" @click="router.push('/')">返回首页</el-button>
      <el-button @click="router.push('/preview')">返回结果预览</el-button>
    </div>
  </section>
</template>

<script setup lang="ts">
import StatusCard from '@/components/StatusCard.vue';
import { useRouter } from 'vue-router';

const router = useRouter();
</script>
