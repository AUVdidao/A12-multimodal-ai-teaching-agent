<template>
  <nav class="workspace-nav" aria-label="项目工作区导航">
    <template v-for="item in navItems" :key="item.path">
      <RouterLink class="workspace-nav__item" :to="item.path">
        <el-icon><component :is="item.icon" /></el-icon>
        <span>{{ item.label }}</span>
      </RouterLink>
    </template>
  </nav>
</template>

<script setup lang="ts">
import {
  Aim,
  Files,
  Finished,
  Reading,
  View,
} from '@element-plus/icons-vue';
import { computed } from 'vue';
import { RouterLink } from 'vue-router';

const props = defineProps<{
  projectId: string | number;
}>();

const navItems = computed(() => {
  const root = `/projects/${props.projectId}`;
  return [
    { label: '教学需求', path: `${root}/requirements`, icon: Reading },
    { label: '资料中心', path: `${root}/materials`, icon: Files },
    { label: '课程大纲', path: `${root}/outline`, icon: Aim },
    { label: '教案设计', path: `${root}/lesson-plan`, icon: Finished },
    { label: 'PPT成果', path: `${root}/ppt`, icon: View },
  ];
});
</script>
