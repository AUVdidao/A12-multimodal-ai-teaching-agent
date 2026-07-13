<template>
  <nav class="workspace-nav" aria-label="项目工作区导航">
    <RouterLink
      v-for="item in navItems"
      :key="item.path"
      :class="['workspace-nav__item', { 'is-muted': item.future }]"
      :to="item.path"
    >
      <el-icon><component :is="item.icon" /></el-icon>
      <span>{{ item.label }}</span>
      <small v-if="item.future">后续</small>
    </RouterLink>
  </nav>
</template>

<script setup lang="ts">
import {
  Aim,
  Collection,
  DocumentChecked,
  Files,
  Finished,
  FolderChecked,
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
    { label: '概览', path: root, icon: Collection },
    { label: '教学需求', path: `${root}/requirements`, icon: Reading },
    { label: '需求摘要', path: `${root}/summary`, icon: DocumentChecked },
    { label: '参考资料', path: `${root}/materials`, icon: Files },
    { label: '知识库', path: `${root}/knowledge`, icon: FolderChecked },
    { label: '教学意图', path: `${root}/intent`, icon: Aim },
    { label: '内容生成', path: `${root}/plan`, icon: Finished, future: true },
    { label: '预览导出', path: `${root}/preview`, icon: View, future: true },
  ];
});
</script>
