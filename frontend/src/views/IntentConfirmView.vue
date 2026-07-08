<template>
  <section class="page">
    <header class="page__header">
      <h2 class="page__title">教学意图确认</h2>
      <p class="page__description">融合需求摘要和资料检索结果，确认生成前的教学意图。</p>
    </header>
    <StatusCard title="RAG 增强确认骨架" description="后续将展示资料命中片段、用途说明和生成约束。" />
    <div class="page__actions">
      <el-button type="primary" @click="router.push('/plan')">下一步：课件生成方案</el-button>
      <el-button @click="router.push('/materials')">返回资料上传</el-button>
    </div>
  </section>
</template>

<script setup lang="ts">
import StatusCard from '@/components/StatusCard.vue';
import { useRouter } from 'vue-router';

const router = useRouter();
</script>
