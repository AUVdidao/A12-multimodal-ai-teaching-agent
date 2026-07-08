<template>
  <section class="page">
    <header class="page__header">
      <h2 class="page__title">智能澄清对话</h2>
      <p class="page__description">系统将在这里围绕缺失字段主动追问，帮助教师补齐生成所需信息。</p>
    </header>
    <StatusCard title="Mock AI 澄清阶段" description="当前只提供页面骨架，不调用 Dify 或真实 AI 工作流。" />
    <div class="page__actions">
      <el-button type="primary" @click="router.push('/summary')">下一步：需求摘要确认</el-button>
      <el-button @click="router.push('/requirements')">返回需求输入</el-button>
    </div>
  </section>
</template>

<script setup lang="ts">
import StatusCard from '@/components/StatusCard.vue';
import { useRouter } from 'vue-router';

const router = useRouter();
</script>
