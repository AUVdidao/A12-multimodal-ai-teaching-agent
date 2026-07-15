<template>
  <section class="page recycle-bin-page">
    <PageHeader
      eyebrow="项目管理"
      title="回收站"
      description="仅展示当前教师移入回收站的项目，可恢复到项目列表。"
    >
      <template #actions>
        <el-tooltip content="刷新回收站" placement="bottom">
          <el-button circle :icon="Refresh" :loading="loading" aria-label="刷新回收站" @click="loadRecycleBin" />
        </el-tooltip>
      </template>
    </PageHeader>

    <StatePanel
      v-if="!isTeacher"
      type="error"
      title="当前身份无法查看回收站"
      description="请切换为教师身份后查看和恢复自己的项目。"
    />
    <StatePanel
      v-else-if="loading && projects.length === 0"
      type="loading"
      title="正在读取回收站"
      description="正在读取你已移入回收站的项目。"
    />
    <StatePanel
      v-else-if="errorMessage && projects.length === 0"
      type="error"
      title="回收站读取失败"
      :description="errorMessage"
    >
      <template #action>
        <el-button type="primary" :icon="Refresh" @click="loadRecycleBin">重新加载</el-button>
      </template>
    </StatePanel>

    <section v-else-if="isTeacher" class="surface-panel recycle-panel" v-loading="loading">
      <el-alert
        v-if="errorMessage"
        class="recycle-panel__alert"
        type="error"
        :title="errorMessage"
        show-icon
        :closable="false"
      />
      <el-empty v-if="projects.length === 0" description="回收站为空" :image-size="72" />
      <ul v-else class="recycle-list">
        <li v-for="project in projects" :key="project.id">
          <div class="recycle-list__identity">
            <strong>{{ project.projectName }}</strong>
            <span>{{ project.courseName }} · {{ project.chapterTitle }}</span>
          </div>
          <time :datetime="project.deletedAt || undefined">删除于 {{ formatFullDateTime(project.deletedAt || undefined) }}</time>
          <el-button
            type="primary"
            plain
            :loading="restoringProjectId === project.id"
            @click="restore(project.id, project.projectName)"
          >
            恢复项目
          </el-button>
        </li>
      </ul>
    </section>
  </section>
</template>

<script setup lang="ts">
import { listRecycleBinProjects, restoreProject, type TeachingProject } from '@/api/projects';
import PageHeader from '@/components/PageHeader.vue';
import StatePanel from '@/components/StatePanel.vue';
import { useAuthStore } from '@/stores/auth';
import { formatFullDateTime } from '@/utils/presentation';
import { Refresh } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { computed, ref, watch } from 'vue';

const auth = useAuthStore();
const projects = ref<TeachingProject[]>([]);
const loading = ref(false);
const errorMessage = ref('');
const restoringProjectId = ref<number | null>(null);
let requestSequence = 0;

const isTeacher = computed(() => auth.activeRole === 'TEACHER');

watch(
  () => auth.activeRole,
  () => {
    requestSequence += 1;
    projects.value = [];
    errorMessage.value = '';
    if (isTeacher.value) void loadRecycleBin();
  },
  { immediate: true },
);

async function loadRecycleBin() {
  if (!isTeacher.value) return;
  const requestId = ++requestSequence;
  loading.value = true;
  errorMessage.value = '';
  try {
    const result = await listRecycleBinProjects();
    if (requestId === requestSequence) projects.value = result;
  } catch (error) {
    if (requestId === requestSequence) errorMessage.value = resolveError(error, '暂时无法读取回收站，请稍后重试。');
  } finally {
    if (requestId === requestSequence) loading.value = false;
  }
}

async function restore(projectId: number, projectName: string) {
  if (restoringProjectId.value !== null) return;
  try {
    await ElMessageBox.confirm(`确认恢复“${projectName}”吗？`, '恢复项目', {
      confirmButtonText: '恢复项目',
      cancelButtonText: '取消',
      type: 'warning',
    });
  } catch {
    return;
  }

  restoringProjectId.value = projectId;
  try {
    await restoreProject(projectId);
    projects.value = projects.value.filter((project) => project.id !== projectId);
    ElMessage.success('项目已恢复到项目列表');
  } catch (error) {
    ElMessage.error(resolveError(error, '恢复项目失败，请稍后重试。'));
  } finally {
    restoringProjectId.value = null;
  }
}

function resolveError(error: unknown, fallback: string) {
  const message = (error as { response?: { data?: { message?: string } } }).response?.data?.message;
  return message && !/(Exception|java\.|Axios)/i.test(message) ? message : fallback;
}
</script>

<style scoped>
.recycle-panel {
  overflow: hidden;
}

.recycle-panel__alert {
  margin: 16px 20px 0;
}

.recycle-list {
  margin: 0;
  padding: 0;
  list-style: none;
}

.recycle-list li {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(180px, auto) auto;
  align-items: center;
  gap: 18px;
  padding: 18px 20px;
  border-bottom: 1px solid var(--color-border);
}

.recycle-list li:last-child {
  border-bottom: 0;
}

.recycle-list__identity {
  display: grid;
  min-width: 0;
  gap: 5px;
}

.recycle-list__identity strong,
.recycle-list__identity span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.recycle-list__identity strong {
  color: var(--color-text);
  font-size: 15px;
  line-height: 1.45;
}

.recycle-list__identity span,
.recycle-list time {
  color: var(--color-text-muted);
  font-size: 13px;
  line-height: 1.5;
}

.recycle-list time {
  white-space: nowrap;
}

@media (max-width: 640px) {
  .recycle-list li {
    grid-template-columns: 1fr auto;
  }

  .recycle-list time {
    grid-column: 1 / -1;
  }
}

@media (max-width: 480px) {
  .recycle-list li {
    display: flex;
    align-items: stretch;
    flex-direction: column;
    gap: 12px;
    padding: 16px;
  }

  .recycle-list .el-button {
    width: 100%;
  }
}
</style>
