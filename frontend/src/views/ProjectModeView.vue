<template>
  <section class="page">
    <header class="page__header">
      <h2 class="page__title">生成模式选择</h2>
      <p class="page__description">
        为当前课件项目选择生成质量、速度和成本的平衡方式，保存后进入教学需求输入。
      </p>
    </header>

    <StatusCard
      title="Mock AI 模式准备就绪"
      description="当前仅保存生成模式，不接入真实 Dify。后续教学需求页面会继续沿用 TA-005 的 Mock AI Workflow 契约。"
    />

    <el-card class="page-card" shadow="never">
      <div v-if="project" class="project-summary">
        <div>
          <span>项目</span>
          <strong>{{ project.projectName }}</strong>
        </div>
        <div>
          <span>课程</span>
          <strong>{{ project.courseName }}</strong>
        </div>
        <div>
          <span>章节</span>
          <strong>{{ project.chapterTitle }}</strong>
        </div>
      </div>

      <el-radio-group v-model="selectedMode" class="mode-grid">
        <el-radio-button
          v-for="mode in modes"
          :key="mode.code"
          :label="mode.code"
          class="mode-option"
        >
          <strong>{{ mode.name }}</strong>
          <span>{{ mode.description }}</span>
        </el-radio-button>
      </el-radio-group>

      <el-alert
        v-if="errorMessage"
        class="inline-alert"
        :title="errorMessage"
        type="warning"
        show-icon
        :closable="false"
      />

      <div class="page__actions">
        <el-button type="primary" :loading="saving" @click="handleSaveMode">
          保存并进入教学需求输入
        </el-button>
        <el-button @click="router.push('/projects')">返回项目列表</el-button>
      </div>
    </el-card>
  </section>
</template>

<script setup lang="ts">
import StatusCard from '@/components/StatusCard.vue';
import {
  getProject,
  getProjectModelMode,
  listModelModes,
  saveProjectModelMode,
  type ModelModeOption,
  type TeachingProject,
} from '@/api/projects';
import { ElMessage } from 'element-plus';
import { onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';

const route = useRoute();
const router = useRouter();
const projectId = route.params.projectId as string;

const project = ref<TeachingProject>();
const modes = ref<ModelModeOption[]>([]);
const selectedMode = ref<ModelModeOption['code']>('STANDARD');
const saving = ref(false);
const errorMessage = ref('');

onMounted(loadInitialData);

async function loadInitialData() {
  errorMessage.value = '';

  try {
    const [projectResult, modeOptions, savedMode] = await Promise.all([
      getProject(projectId),
      listModelModes(),
      getProjectModelMode(projectId),
    ]);
    project.value = projectResult;
    modes.value = modeOptions;
    selectedMode.value = savedMode.mode || 'STANDARD';
  } catch (error) {
    errorMessage.value = '生成模式信息读取失败，请确认后端服务已启动。';
  }
}

async function handleSaveMode() {
  saving.value = true;
  errorMessage.value = '';

  try {
    await saveProjectModelMode(projectId, selectedMode.value);
    ElMessage.success('生成模式已保存');
    router.push({ path: '/requirements', query: { projectId } });
  } catch (error) {
    errorMessage.value = '生成模式保存失败，请稍后重试。';
  } finally {
    saving.value = false;
  }
}
</script>
