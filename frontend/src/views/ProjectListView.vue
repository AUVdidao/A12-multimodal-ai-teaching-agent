<template>
  <section class="page">
    <header class="page__header">
      <h2 class="page__title">项目列表</h2>
      <p class="page__description">集中查看教师创建的备课项目，后续将承载项目状态、生成模式和最近更新时间。</p>
    </header>
    <StatusCard title="项目管理骨架" description="当前仅提供页面入口和演示占位，不读取真实项目数据。" />
    <div class="page__actions">
      <el-button type="primary" @click="router.push('/projects/new')">下一步：新建课件项目</el-button>
      <el-button @click="router.push('/')">返回首页</el-button>
    </div>
  </section>
</template>

<script setup lang="ts">
import StatusCard from '@/components/StatusCard.vue';
import { useRouter } from 'vue-router';

const router = useRouter();
</script>
