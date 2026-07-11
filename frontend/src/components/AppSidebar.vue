<template>
  <aside class="app-sidebar">
    <div class="app-sidebar__context">
      <span>{{ inM2 ? 'M2 资料增强闭环' : 'M1 需求澄清闭环' }}</span>
      <strong>{{ projectId ? `当前项目 #${projectId}` : '尚未选择项目' }}</strong>
      <small>{{ projectId ? (inM2 ? '资料、知识与教学意图' : '需求澄清工作流进行中') : '从项目列表开始备课' }}</small>
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

      <li class="app-sidebar__group">M2 资料增强</li>
      <el-menu-item :index="materialsPath" :disabled="!m2Available">
        <el-icon><Files /></el-icon><span>资料与解析</span><Lock v-if="!m2Available" class="app-sidebar__lock" />
      </el-menu-item>
      <el-menu-item :index="knowledgePath" :disabled="!m2Available">
        <el-icon><Search /></el-icon><span>本地知识检索</span><Lock v-if="!m2Available" class="app-sidebar__lock" />
      </el-menu-item>
      <el-menu-item :index="intentPath" :disabled="!m2Available">
        <el-icon><Aim /></el-icon><span>教学意图确认</span><Lock v-if="!m2Available" class="app-sidebar__lock" />
      </el-menu-item>

      <li class="app-sidebar__group app-sidebar__group--locked">下一阶段</li>
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
import { getLatestRequirementSummary } from '@/api/requirementSummaries';
import { Aim, DocumentChecked, EditPen, Files, Folder, House, Lock, Operation, Search, Tickets } from '@element-plus/icons-vue';
import { computed, ref, watch } from 'vue';
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
const materialsPath = computed(() => projectId.value ? `/projects/${projectId.value}/materials` : '/projects');
const knowledgePath = computed(() => projectId.value ? `/projects/${projectId.value}/knowledge` : '/projects');
const intentPath = computed(() => projectId.value ? `/projects/${projectId.value}/teaching-intent` : '/projects');
const summaryConfirmed = ref(false);
const summaryAvailable = computed(() => Boolean(projectId.value && (summaryConfirmed.value || route.path.includes('/requirement-summary'))));
const m2Available = computed(() => Boolean(projectId.value && summaryConfirmed.value));
const inM2 = computed(() => ['/materials', '/knowledge', '/teaching-intent'].some((segment) => route.path.includes(segment)));
watch(() => [projectId.value, route.fullPath] as const, async ([value]) => {
  summaryConfirmed.value = false;
  if (!value) return;
  const numericId = Number(value);
  if (!Number.isInteger(numericId) || numericId <= 0) return;
  try { summaryConfirmed.value = (await getLatestRequirementSummary(numericId))?.status === 'CONFIRMED'; } catch { summaryConfirmed.value = false; }
}, { immediate: true });
const activePath = computed(() => {
  if (route.path.includes('/teaching-intent')) return intentPath.value;
  if (route.path.includes('/knowledge')) return knowledgePath.value;
  if (route.path.includes('/materials')) return materialsPath.value;
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
