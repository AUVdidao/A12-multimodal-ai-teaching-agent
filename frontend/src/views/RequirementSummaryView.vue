<template>
  <section class="page">
    <header class="page__header">
      <h2 class="page__title">需求摘要确认</h2>
      <p class="page__description">确认结构化教学需求摘要，作为资料上传和知识库增强的输入。</p>
    </header>
    <StatusCard title="摘要确认骨架" description="后续将展示学段、学科、目标、重点难点和输出类型。" />
    <div class="page__actions">
      <el-button type="primary" @click="router.push('/materials')">下一步：资料上传</el-button>
      <el-button @click="router.push('/dialog')">返回智能澄清</el-button>
    </div>
  </section>
</template>

<script setup lang="ts">
import StatusCard from '@/components/StatusCard.vue';
import { useRouter } from 'vue-router';

const router = useRouter();
</script>
