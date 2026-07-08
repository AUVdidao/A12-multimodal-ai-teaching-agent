<template>
  <div class="app-header">
    <div>
      <h1>A12 多模态 AI 教学智能体</h1>
      <p>面向教师备课的课件、教案与互动内容生成系统原型</p>
    </div>
    <el-tag effect="plain" type="success">{{ app.mode }}</el-tag>
  </div>
</template>

<script setup lang="ts">
import { useAppStore } from '@/stores/app';

const app = useAppStore();
</script>
