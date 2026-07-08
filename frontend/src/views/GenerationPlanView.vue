<template>
  <section class="page">
    <header class="page__header">
      <h2 class="page__title">课件生成方案</h2>
      <p class="page__description">展示即将生成的 PPT 页结构、Word 教案结构和互动内容设计。</p>
    </header>
    <StatusCard title="生成方案骨架" description="当前只呈现流程页面，真实方案生成将在后续 AI 网关任务中接入。" />
    <div class="page__actions">
      <el-button type="primary" @click="router.push('/preview')">下一步：生成结果预览</el-button>
      <el-button @click="router.push('/intent')">返回教学意图</el-button>
    </div>
  </section>
</template>

<script setup lang="ts">
import StatusCard from '@/components/StatusCard.vue';
import { useRouter } from 'vue-router';

const router = useRouter();
</script>
