<template>
  <el-card class="status-card" shadow="never">
    <div class="status-card__label">当前阶段</div>
    <div class="status-card__title">{{ title }}</div>
    <p>{{ description }}</p>
  </el-card>
</template>

<script setup lang="ts">
defineProps<{
  title: string;
  description: string;
}>();
</script>
