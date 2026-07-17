<template>
  <nav class="workspace-nav" aria-label="项目工作区导航">
    <template v-for="item in navItems" :key="item.path">
      <RouterLink
        :class="['workspace-nav__item', { 'is-related-active': isRelatedActive(item.path) }]"
        :to="item.path"
      >
        <el-icon><component :is="item.icon" /></el-icon>
        <span>{{ item.label }}</span>
      </RouterLink>
    </template>
  </nav>
</template>

<script setup lang="ts">
import {
  Aim,
  Collection,
  DocumentChecked,
  Download,
  Files,
  Finished,
  FolderChecked,
  Reading,
  View,
} from '@element-plus/icons-vue';
import { computed } from 'vue';
import { RouterLink, useRoute } from 'vue-router';

const props = defineProps<{
  projectId: string | number;
}>();
const route = useRoute();

const navItems = computed(() => {
  const root = `/projects/${props.projectId}`;
  return [
    { label: '概览', path: root, icon: Collection },
    { label: '教学需求', path: `${root}/requirements`, icon: Reading },
    { label: '需求摘要', path: `${root}/summary`, icon: DocumentChecked },
    { label: '参考资料', path: `${root}/materials`, icon: Files },
    { label: '知识库', path: `${root}/knowledge`, icon: FolderChecked },
    { label: '教学意图', path: `${root}/intent`, icon: Aim },
    { label: '内容生成', path: `${root}/plan`, icon: Finished },
    { label: '成果预览', path: `${root}/preview`, icon: View },
    { label: '成果导出', path: `${root}/export`, icon: Download },
  ];
});

function isRelatedActive(path: string) {
  return route.name === 'project-mode' && path === `/projects/${props.projectId}`;
}
</script>
