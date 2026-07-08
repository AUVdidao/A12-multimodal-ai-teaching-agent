<template>
  <section class="page">
    <header class="page__header">
      <h2 class="page__title">资料上传与用途绑定</h2>
      <p class="page__description">教师将在这里上传教材、讲义和案例资料，并标记资料用途。</p>
    </header>
    <StatusCard title="资料增强入口" description="当前不上传真实文件，只保留后续接入上传接口的页面位置。" />
    <div class="page__actions">
      <el-button type="primary" @click="router.push('/intent')">下一步：教学意图确认</el-button>
      <el-button @click="router.push('/summary')">返回摘要确认</el-button>
    </div>
  </section>
</template>

<script setup lang="ts">
import StatusCard from '@/components/StatusCard.vue';
import { useRouter } from 'vue-router';

const router = useRouter();
</script>
