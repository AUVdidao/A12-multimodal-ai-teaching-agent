<template>
  <section class="page">
    <header class="page__header">
      <h2 class="page__title">新建课件项目</h2>
      <p class="page__description">选择学科、年级和生成模式，为后续教学需求输入准备项目上下文。</p>
    </header>
    <StatusCard title="待接入项目创建接口" description="本任务只展示创建入口，真实保存逻辑将在后续任务完成。" />
    <div class="page__actions">
      <el-button type="primary" @click="router.push('/requirements')">下一步：教学需求输入</el-button>
      <el-button @click="router.push('/projects')">返回项目列表</el-button>
    </div>
  </section>
</template>

<script setup lang="ts">
import StatusCard from '@/components/StatusCard.vue';
import { useRouter } from 'vue-router';

const router = useRouter();
</script>
