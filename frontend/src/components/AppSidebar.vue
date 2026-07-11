<template>
  <aside class="app-sidebar">
    <div class="app-sidebar__context">
      <span>M1 演示闭环</span>
      <strong>{{ projectId ? `当前项目 #${projectId}` : '尚未选择项目' }}</strong>
      <small>{{ projectId ? '需求澄清工作流进行中' : '从项目列表开始备课' }}</small>
    </div>

    <el-menu :default-active="activePath" router class="app-sidebar__menu" @select="emit('navigate')">
      <li class="app-sidebar__group">工作台</li>
      <el-menu-item index="/">
        <el-icon><House /></el-icon><span>教师工作台</span>
      </el-menu-item>
      <el-menu-item index="/projects">
        <el-icon><Folder /></el-icon><span>教学项目</span>
      </el-menu-item>

      <li class="app-sidebar__group">当前项目</li>
      <el-menu-item :index="modePath" :disabled="!projectId">
        <el-icon><Operation /></el-icon><span>生成模式</span>
      </el-menu-item>
      <el-menu-item :index="requirementsPath" :disabled="!projectId">
        <el-icon><EditPen /></el-icon><span>需求与 AI 澄清</span>
      </el-menu-item>
      <el-menu-item :index="summaryPath" :disabled="!summaryAvailable">
        <el-icon><DocumentChecked /></el-icon><span>需求摘要</span>
      </el-menu-item>

      <li class="app-sidebar__group app-sidebar__group--locked">下一阶段</li>
      <el-menu-item index="/materials" disabled>
        <el-icon><Files /></el-icon><span>资料与知识库</span><Lock class="app-sidebar__lock" />
      </el-menu-item>
      <el-menu-item index="/plan" disabled>
        <el-icon><Tickets /></el-icon><span>内容生成</span><Lock class="app-sidebar__lock" />
      </el-menu-item>
    </el-menu>

    <div class="app-sidebar__footer">
      <span class="app-sidebar__provider">Mock AI</span>
      <p>当前演示使用确定性模拟服务</p>
    </div>
  </aside>
</template>

<script setup lang="ts">
import { DocumentChecked, EditPen, Files, Folder, House, Lock, Operation, Tickets } from '@element-plus/icons-vue';
import { computed } from 'vue';
import { useRoute } from 'vue-router';

const emit = defineEmits<{ navigate: [] }>();
const route = useRoute();
const projectId = computed(() => {
  const value = route.params.projectId || route.query.projectId;
  return Array.isArray(value) ? value[0] : value;
});
const modePath = computed(() => projectId.value ? `/projects/${projectId.value}/mode` : '/projects');
const requirementsPath = computed(() => projectId.value ? `/projects/${projectId.value}/requirements` : '/projects');
const summaryPath = computed(() => projectId.value ? `/projects/${projectId.value}/requirement-summary` : '/projects');
const summaryAvailable = computed(() => Boolean(projectId.value && route.path.includes('/requirement-summary')));
const activePath = computed(() => {
  if (route.path.includes('/requirement-summary')) return summaryPath.value;
  if (route.path.includes('/requirements') || route.path === '/dialog') return requirementsPath.value;
  if (route.path.includes('/mode')) return modePath.value;
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

.app-sidebar__context {
  margin: 0 4px 14px;
  padding: 13px 14px;
  border: 1px solid var(--color-primary-border);
  border-radius: var(--radius-lg);
  background: var(--color-primary-soft);
}

.app-sidebar__context span,
.app-sidebar__context strong,
.app-sidebar__context small {
  display: block;
}

.app-sidebar__context span {
  color: var(--color-primary);
  font-size: 10px;
  font-weight: 800;
}

.app-sidebar__context strong {
  margin-top: 5px;
  color: var(--color-text);
  font-size: 13px;
}

.app-sidebar__context small {
  margin-top: 3px;
  color: var(--color-text-secondary);
  font-size: 10px;
}

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

.app-sidebar__group--locked {
  margin-top: 22px;
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

.app-sidebar__lock {
  width: 13px;
  margin-left: auto;
}

.app-sidebar__footer {
  margin: 16px 4px 0;
  padding: 12px;
  border-top: 1px solid var(--color-border);
}

.app-sidebar__provider {
  display: inline-block;
  padding: 3px 7px;
  border-radius: var(--radius-sm);
  background: var(--color-ai-soft);
  color: var(--color-ai);
  font-size: 10px;
  font-weight: 800;
}

.app-sidebar__footer p {
  margin: 6px 0 0;
  color: var(--color-text-muted);
  font-size: 10px;
  line-height: 1.5;
}
</style>
