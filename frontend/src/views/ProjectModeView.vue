<template>
  <section class="page mode-page">
    <PageHeader eyebrow="M1 · 第 2 步" title="选择生成模式" description="根据备课场景选择质量、速度与成本的平衡方式，保存后进入教学需求。" :project-label="project ? project.projectName : `项目 #${projectId}`" />
    <M1ProgressSteps :current-step="1" :unlocked-step="1" :completed-through="0" :project-id="projectId" />

    <StatePanel v-if="loading" type="loading" title="正在读取模式" description="同步项目和可用生成模式，请稍候。" />
    <StatePanel v-else-if="errorMessage && modes.length === 0" type="error" title="生成模式读取失败" :description="errorMessage">
      <template #action><el-button size="small" type="primary" @click="loadInitialData">重新加载</el-button></template>
    </StatePanel>

    <div v-else class="surface-panel mode-surface">
      <div v-if="project" class="project-summary">
        <div><span>课程</span><strong>{{ project.courseName }}</strong></div>
        <div><span>章节</span><strong>{{ project.chapterTitle }}</strong></div>
        <div><span>授课对象</span><strong>{{ project.targetStudents || '待补充' }}</strong></div>
      </div>

      <div class="mode-heading">
        <div><span>可用策略</span><h2>选择本次教学共创的工作方式</h2></div>
        <span class="mode-heading__hint">模式来自后端配置</span>
      </div>

      <div class="mode-grid" role="radiogroup" aria-label="生成模式">
        <button
          v-for="mode in modes"
          :key="mode.code"
          type="button"
          role="radio"
          :aria-checked="selectedMode === mode.code"
          :class="['mode-option', { 'is-selected': selectedMode === mode.code }]"
          @click="selectedMode = mode.code"
        >
          <span class="mode-option__icon"><el-icon><Operation /></el-icon></span>
          <span class="mode-option__check"><el-icon><Check /></el-icon></span>
          <strong>{{ mode.name }}</strong>
          <p>{{ mode.description }}</p>
          <small><b>适合：</b>{{ modeScenario(mode.code) }}</small>
        </button>
      </div>

      <el-alert v-if="errorMessage" class="inline-alert" :title="errorMessage" type="warning" show-icon :closable="false" />

      <PrimaryActionBar>
        <template #info>当前选择会保存到项目中，刷新页面后仍会正确回显。</template>
        <template #secondary><el-button @click="router.push('/projects')">返回项目列表</el-button></template>
        <el-button type="primary" :icon="Right" :loading="saving" :disabled="saving || !selectedMode" @click="handleSaveMode">保存并进入教学需求</el-button>
      </PrimaryActionBar>
    </div>
  </section>
</template>

<script setup lang="ts">
import { getProject, getProjectModelMode, listModelModes, saveProjectModelMode, type ModelModeOption, type TeachingProject } from '@/api/projects';
import M1ProgressSteps from '@/components/M1ProgressSteps.vue';
import PageHeader from '@/components/PageHeader.vue';
import PrimaryActionBar from '@/components/PrimaryActionBar.vue';
import StatePanel from '@/components/StatePanel.vue';
import { Check, Operation, Right } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import { onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';

const route = useRoute();
const router = useRouter();
const projectId = route.params.projectId as string;
const project = ref<TeachingProject>();
const modes = ref<ModelModeOption[]>([]);
const selectedMode = ref<ModelModeOption['code']>('STANDARD');
const loading = ref(false);
const saving = ref(false);
const errorMessage = ref('');

onMounted(loadInitialData);

async function loadInitialData() {
  loading.value = true;
  errorMessage.value = '';
  try {
    const [projectResult, modeOptions, savedMode] = await Promise.all([getProject(projectId), listModelModes(), getProjectModelMode(projectId)]);
    project.value = projectResult;
    modes.value = modeOptions;
    selectedMode.value = savedMode.mode || 'STANDARD';
  } catch {
    errorMessage.value = '暂时无法读取模式信息，请检查服务后重试。';
  } finally {
    loading.value = false;
  }
}

async function handleSaveMode() {
  if (saving.value) return;
  saving.value = true;
  errorMessage.value = '';
  try {
    await saveProjectModelMode(projectId, selectedMode.value);
    ElMessage.success('生成模式已保存');
    router.push({ name: 'project-requirements', params: { projectId } });
  } catch {
    errorMessage.value = '生成模式保存失败，请稍后重试。';
  } finally {
    saving.value = false;
  }
}

function modeScenario(code: ModelModeOption['code']) {
  return ({ STANDARD: '常规备课与稳定演示', QUALITY: '重点课程与精细打磨', ECONOMY: '快速形成可讨论初稿' } as Record<string, string>)[code] || '当前教学项目';
}
</script>

<style scoped>
.mode-surface {
  padding: 26px;
}

.mode-heading {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 20px;
  margin: 24px 0 16px;
}

.mode-heading span:first-child {
  color: var(--color-primary);
  font-size: 11px;
  font-weight: 800;
}

.mode-heading h2 {
  margin: 5px 0 0;
  font-size: 18px;
}

.mode-heading__hint {
  color: var(--color-text-muted);
  font-size: 11px;
}

.mode-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 14px;
}

.mode-option {
  position: relative;
  min-height: 205px;
  padding: 20px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  background: var(--color-surface);
  color: var(--color-text);
  cursor: pointer;
  text-align: left;
  transition: border-color var(--transition-fast), box-shadow var(--transition-fast), transform var(--transition-fast);
}

.mode-option:hover {
  border-color: var(--color-primary-border);
  box-shadow: var(--shadow-card);
  transform: translateY(-2px);
}

.mode-option.is-selected {
  border-color: var(--color-primary);
  background: var(--color-primary-soft);
  box-shadow: 0 0 0 2px rgba(36, 87, 214, 0.08);
}

.mode-option__icon {
  display: grid;
  width: 38px;
  height: 38px;
  place-items: center;
  border-radius: var(--radius-md);
  background: var(--color-surface-subtle);
  color: var(--color-primary);
  font-size: 19px;
}

.is-selected .mode-option__icon {
  background: var(--color-primary);
  color: #ffffff;
}

.mode-option__check {
  position: absolute;
  top: 16px;
  right: 16px;
  display: none;
  color: var(--color-primary);
  font-size: 20px;
}

.is-selected .mode-option__check {
  display: block;
}

.mode-option strong,
.mode-option p,
.mode-option small {
  display: block;
}

.mode-option strong {
  margin-top: 16px;
  font-size: 16px;
}

.mode-option p {
  min-height: 44px;
  margin: 7px 0 15px;
  color: var(--color-text-secondary);
  font-size: 12px;
  line-height: 1.6;
}

.mode-option small {
  padding-top: 12px;
  border-top: 1px solid var(--color-border);
  color: var(--color-text-muted);
  font-size: 10px;
  line-height: 1.5;
}

.mode-option small b {
  color: var(--color-text-secondary);
}

@media (max-width: 900px) {
  .mode-grid {
    grid-template-columns: 1fr;
  }

  .mode-option {
    min-height: 0;
  }
}

@media (max-width: 640px) {
  .mode-surface {
    padding: 18px;
  }

  .mode-heading {
    align-items: flex-start;
    flex-direction: column;
    gap: 6px;
  }
}
</style>
