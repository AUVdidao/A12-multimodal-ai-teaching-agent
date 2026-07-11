<template>
  <nav class="workspace-nav" aria-label="项目工作区导航">
    <router-link v-for="item in items" :key="item.key" :to="item.path" class="workspace-nav__item" :class="{ 'is-active': isActive(item.key) }">
      <el-icon><component :is="item.icon" /></el-icon><span>{{ item.label }}</span>
    </router-link>
  </nav>
</template>

<script setup lang="ts">
import { Aim, Collection, DocumentChecked, EditPen, Files } from '@element-plus/icons-vue';
import { computed } from 'vue';
import { useRoute } from 'vue-router';

const props = defineProps<{ projectId: number }>();
const route = useRoute();
const items = computed(() => [
  { key: 'overview', label: '项目概览', icon: Collection, path: `/projects/${props.projectId}/overview` },
  { key: 'requirements', label: '教学需求', icon: EditPen, path: `/projects/${props.projectId}/requirements` },
  { key: 'materials', label: '参考资料', icon: Files, path: `/projects/${props.projectId}/materials` },
  { key: 'knowledge', label: '知识库', icon: DocumentChecked, path: `/projects/${props.projectId}/knowledge` },
  { key: 'intent', label: '教学意图', icon: Aim, path: `/projects/${props.projectId}/teaching-intent` },
]);
function isActive(key: string) {
  const path = route.path;
  return (key === 'overview' && path.endsWith('/overview'))
    || (key === 'requirements' && (path.includes('/requirements') || path.includes('/requirement-summary') || path.endsWith('/mode')))
    || (key === 'materials' && path.includes('/materials'))
    || (key === 'knowledge' && path.includes('/knowledge'))
    || (key === 'intent' && path.includes('/teaching-intent'));
}
</script>

<style scoped>
.workspace-nav { display: flex; gap: 4px; overflow-x: auto; padding: 12px 0 18px; scrollbar-width: thin; }
.workspace-nav__item { display: inline-flex; flex: 0 0 auto; align-items: center; gap: 7px; min-height: 34px; padding: 0 10px; border-radius: var(--radius-md); color: var(--color-text-secondary); font-size: 12px; font-weight: 650; text-decoration: none; transition: background-color var(--transition-fast), color var(--transition-fast); }
.workspace-nav__item:hover { background: var(--color-surface-subtle); color: var(--color-primary); }
.workspace-nav__item.is-active { background: var(--color-primary-soft); color: var(--color-primary); }
</style>
