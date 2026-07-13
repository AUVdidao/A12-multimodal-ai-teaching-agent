<template>
  <section class="page" v-loading="loading">
    <ProjectContextHeader v-if="project" :project="project" />

    <section v-if="project" class="panel">
      <div class="panel__header">
        <div>
          <h3>选择生成模式</h3>
          <p>该设置会影响后续 AI 工作流的成本、速度和质量。</p>
        </div>
      </div>

      <el-radio-group v-model="mode" class="grid cols-3" style="width: 100%">
        <el-radio-button v-for="item in modes" :key="item.code" :label="item.code">
          <strong>{{ item.name }}</strong>
          <span>{{ item.description }}</span>
        </el-radio-button>
      </el-radio-group>

      <div class="page-actions">
        <el-button type="primary" :loading="saving" @click="saveMode">保存并进入教学需求</el-button>
        <el-button @click="router.push(`/projects/${project.id}`)">返回项目概览</el-button>
      </div>
    </section>
  </section>
</template>

<script setup lang="ts">
import ProjectContextHeader from '@/components/ProjectContextHeader.vue';
import { getProjectWorkspaceOverview, type ProjectBrief } from '@/api/workspace';
import { listModelModes, saveProjectModelMode, type ModelModeOption } from '@/api/projects';
import { ElMessage } from 'element-plus';
import { computed, onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';

const route = useRoute();
const router = useRouter();
const projectId = computed(() => Number(route.params.projectId));
const project = ref<ProjectBrief>();
const mode = ref<ModelModeOption['code']>('STANDARD');
const modes = ref<ModelModeOption[]>([]);
const loading = ref(true);
const saving = ref(false);

async function loadPage() {
  loading.value = true;
  try {
    const [overview, options] = await Promise.all([getProjectWorkspaceOverview(projectId.value), listModelModes()]);
    project.value = overview.project;
    modes.value = options;
    mode.value = (overview.project.modelMode as ModelModeOption['code']) || 'STANDARD';
  } finally {
    loading.value = false;
  }
}

async function saveMode() {
  saving.value = true;
  try {
    await saveProjectModelMode(projectId.value, mode.value);
    ElMessage.success('生成模式已保存');
    router.push(`/projects/${projectId.value}/requirements`);
  } finally {
    saving.value = false;
  }
}

onMounted(loadPage);
</script>

<style scoped>
:deep(.el-radio-button__inner) {
  width: 100%;
  min-height: 120px;
  padding: 20px;
  border-radius: 10px !important;
  text-align: left;
  white-space: normal;
}

:deep(.el-radio-button__inner span),
:deep(.el-radio-button__inner strong) {
  display: block;
}

:deep(.el-radio-button__inner span) {
  margin-top: 8px;
  color: var(--ui-muted);
  line-height: 1.55;
}
</style>
