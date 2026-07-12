<template>
  <aside class="app-sidebar">
    <div class="app-sidebar__brand">
      <span class="app-sidebar__brand-mark">A</span>
      <div><strong>A12 教学智能体</strong><small>多模态 AI 互动式教学</small></div>
    </div>

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
      <div>
        <span :class="['app-sidebar__footer-dot', `is-${app.systemStatus}`]" aria-hidden="true" />
        <p>{{ app.systemStatusLabel }}</p>
      </div>
      <el-button text :icon="Refresh" :loading="app.systemStatus === 'checking'" aria-label="重新检查服务状态" title="重新检查服务状态" @click="app.checkHealth" />
    </div>
  </aside>
</template>

<script setup lang="ts">
import { Folder, House, Refresh } from '@element-plus/icons-vue';
import { computed, onMounted } from 'vue';
import { useRoute } from 'vue-router';
import { useAppStore } from '@/stores/app';

const emit = defineEmits<{ navigate: [] }>();
const route = useRoute();
const app = useAppStore();
const activePath = computed(() => {
  if (route.path.startsWith('/projects')) return '/projects';
  return route.path;
});
onMounted(() => app.checkHealth());
</script>

<style scoped>
.app-sidebar {
  display: flex;
  min-height: 100%;
  flex-direction: column;
  padding: 18px 14px 16px;
}

.app-sidebar__brand { display: flex; align-items: center; gap: 10px; margin: 0 6px 22px; color: var(--color-text); }
.app-sidebar__brand-mark { display: grid; width: 34px; height: 34px; place-items: center; border-radius: 11px; background: var(--color-primary); color: #fff; font-size: 19px; font-weight: 800; box-shadow: 0 7px 16px rgba(91, 69, 246, .22); }
.app-sidebar__brand strong, .app-sidebar__brand small { display: block; }
.app-sidebar__brand strong { font-size: 14px; letter-spacing: 0; }
.app-sidebar__brand small { margin-top: 2px; color: var(--color-text-muted); font-size: 10px; }

.app-sidebar__menu {
  flex: 1;
  border-right: 0;
}

.app-sidebar__group {
  margin: 14px 10px 6px;
  color: var(--color-text-muted);
  font-size: 10px;
  font-weight: 800;
  list-style: none;
}

.app-sidebar__menu :deep(.el-menu-item) {
  height: 44px;
  margin: 3px 0;
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
  font-weight: 750;
  box-shadow: inset 3px 0 0 var(--color-primary);
}

.app-sidebar__menu :deep(.el-menu-item.is-disabled) {
  opacity: 0.58;
}

.app-sidebar__footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 7px;
  margin: 16px 4px 0;
  padding: 13px 8px 0;
  border-top: 1px solid var(--color-border);
}

.app-sidebar__footer > div { display: flex; align-items: center; gap: 8px; min-width: 0; }

.app-sidebar__footer p {
  margin: 0;
  color: var(--color-text-muted);
  font-size: 10px;
  line-height: 1.5;
}
.app-sidebar__footer-dot { width: 7px; height: 7px; border-radius: 50%; background: var(--color-text-muted); }
.app-sidebar__footer-dot.is-healthy { background: var(--color-success); }
.app-sidebar__footer-dot.is-unavailable { background: var(--color-danger); }
</style>
