<template>
  <section class="project-workspace-layout">
    <StatePanel
      v-if="loading"
      class="project-workspace-layout__state"
      type="loading"
      title="正在加载项目工作区"
      description="正在读取项目概览与当前阶段信息。"
    />

    <StatePanel
      v-else-if="!overview"
      class="project-workspace-layout__state"
      type="error"
      title="项目工作区加载失败"
      :description="errorMessage"
    >
      <template #action>
        <el-button type="primary" :icon="Refresh" @click="loadOverview">重新加载</el-button>
      </template>
    </StatePanel>

    <template v-else>
      <ProjectContextHeader :project="overview.project" />
      <ProjectWorkspaceNav :project-id="overview.project.id" />
      <div class="project-workspace-layout__view">
        <RouterView v-slot="{ Component }">
          <component :is="Component" :key="`${String(route.name)}:${projectId}`" />
        </RouterView>
      </div>
    </template>
  </section>
</template>

<script setup lang="ts">
import { getProjectWorkspaceOverview, type ProjectOverview } from '@/api/workspace';
import ProjectContextHeader from '@/components/ProjectContextHeader.vue';
import ProjectWorkspaceNav from '@/components/ProjectWorkspaceNav.vue';
import StatePanel from '@/components/StatePanel.vue';
import { Refresh } from '@element-plus/icons-vue';
import { computed, ref, watch } from 'vue';
import { RouterView, useRoute } from 'vue-router';

const route = useRoute();
const projectId = computed(() => String(route.params.projectId || ''));
const overview = ref<ProjectOverview>();
const loading = ref(true);
const errorMessage = ref('');
let requestSequence = 0;

async function loadOverview(silent = false) {
  const requestId = ++requestSequence;
  const requestedProjectId = projectId.value;
  if (!silent) {
    overview.value = undefined;
    errorMessage.value = '';
    loading.value = true;
  }

  if (!requestedProjectId) {
    errorMessage.value = '缺少项目编号，无法读取项目工作区。';
    loading.value = false;
    return;
  }

  try {
    const result = await getProjectWorkspaceOverview(requestedProjectId);
    if (requestId === requestSequence) overview.value = result;
  } catch (error) {
    if (!silent && requestId === requestSequence) {
      errorMessage.value = resolveError(error, '暂时无法读取项目工作区，请稍后重试。');
    }
  } finally {
    if (!silent && requestId === requestSequence) loading.value = false;
  }
}

function resolveError(error: unknown, fallback: string) {
  const message = (error as { response?: { data?: { message?: string } } }).response?.data?.message;
  return message && !/(Exception|java\.|Axios)/i.test(message) ? message : fallback;
}

watch(projectId, () => void loadOverview(), { immediate: true });
watch(
  () => route.name,
  (currentRoute, previousRoute) => {
    if (previousRoute !== undefined && currentRoute !== previousRoute) void loadOverview(true);
  },
);
</script>
