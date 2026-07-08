<template>
  <section class="page">
    <header class="page__header">
      <h2 class="page__title">生成结果预览</h2>
      <p class="page__description">预览 PPT、Word 教案和互动内容，后续支持自然语言修改反馈。</p>
    </header>
    <StatusCard title="结果预览骨架" description="当前不生成真实内容，只保留预览区域和导出前跳转。" />
    <div class="page__actions">
      <el-button type="primary" @click="router.push('/export')">下一步：文件导出</el-button>
      <el-button @click="router.push('/plan')">返回生成方案</el-button>
    </div>
  </section>
</template>

<script setup lang="ts">
import StatusCard from '@/components/StatusCard.vue';
import { useRouter } from 'vue-router';

const router = useRouter();
</script>
