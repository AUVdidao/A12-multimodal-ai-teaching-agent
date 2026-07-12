<template>
  <aside class="app-sidebar">
    <div class="app-sidebar__brand"><span class="app-sidebar__brand-mark">A12</span><strong>教学智能体</strong></div>

    <el-menu :default-active="activePath" router class="app-sidebar__menu" @select="emit('navigate')">
      <li class="app-sidebar__group">工作区</li>
      <el-menu-item index="/">
        <el-icon><House /></el-icon><span>教师工作台</span>
      </el-menu-item>
      <el-menu-item index="/projects">
        <el-icon><Folder /></el-icon><span>教学项目</span>
      </el-menu-item>
    </el-menu>

    <div class="app-sidebar__footer">
      <p>V1.0 原型</p>
    </div>
  </aside>
</template>

<script setup lang="ts">
import { Folder, House } from '@element-plus/icons-vue';
import { computed } from 'vue';
import { useRoute } from 'vue-router';

const emit = defineEmits<{ navigate: [] }>();
const route = useRoute();
const activePath = computed(() => {
  if (route.path.startsWith('/projects')) return '/projects';
  return route.path;
});
</script>

<style scoped>
.app-sidebar {
  display: flex;
  min-height: 100%;
  flex-direction: column;
  padding: 18px 12px 16px;
}

.app-sidebar__brand { display: flex; align-items: center; gap: 8px; margin: 0 8px 18px; color: var(--color-text); font-size: 13px; }
.app-sidebar__brand-mark { display: grid; width: 28px; height: 28px; place-items: center; border-radius: var(--radius-md); background: var(--color-primary); color: #fff; font-size: 10px; font-weight: 800; }

.app-sidebar__menu {
  flex: 1;
  border-right: 0;
}

.app-sidebar__group {
  margin: 14px 12px 5px;
  color: var(--color-text-muted);
  font-size: 10px;
  font-weight: 800;
  list-style: none;
}

.app-sidebar__menu :deep(.el-menu-item) {
  height: 42px;
  margin: 2px 0;
  border-radius: var(--radius-md);
  color: var(--color-text-secondary);
}

.app-sidebar__menu :deep(.el-menu-item:hover) {
  background: var(--color-primary-soft);
  color: var(--color-primary);
}

.app-sidebar__menu :deep(.el-menu-item.is-active) {
  background: var(--color-primary-soft);
  color: var(--color-primary);
  font-weight: 700;
}

.app-sidebar__menu :deep(.el-menu-item.is-disabled) {
  opacity: 0.58;
}

.app-sidebar__footer {
  margin: 16px 4px 0;
  padding: 12px;
  border-top: 1px solid var(--color-border);
}

.app-sidebar__footer p {
  margin: 0;
  color: var(--color-text-muted);
  font-size: 10px;
  line-height: 1.5;
}
</style>
