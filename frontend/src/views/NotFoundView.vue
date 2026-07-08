<template>
  <section class="page">
    <header class="page__header">
      <h2 class="page__title">404 页面不存在</h2>
      <p class="page__description">当前访问的页面不在 MVP 原型路由中，请返回首页继续演示流程。</p>
    </header>
    <StatusCard title="路由兜底" description="用于避免演示时错误地址出现空白页面。" />
    <div class="page__actions">
      <el-button type="primary" @click="router.push('/')">返回首页</el-button>
    </div>
  </section>
</template>

<script setup lang="ts">
import StatusCard from '@/components/StatusCard.vue';
import { useRouter } from 'vue-router';

const router = useRouter();
</script>
