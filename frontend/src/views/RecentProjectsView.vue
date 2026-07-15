<template>
  <section class="page recent-projects-page">
    <PageHeader
      eyebrow="项目管理"
      title="最近访问"
      description="按最近打开时间展示当前教师访问过的项目。"
    >
      <template #actions>
        <el-tooltip content="刷新最近访问" placement="bottom">
          <el-button circle :icon="Refresh" :loading="loading" aria-label="刷新最近访问" @click="loadRecentProjects" />
        </el-tooltip>
      </template>
    </PageHeader>

    <StatePanel
      v-if="!isTeacher"
      type="error"
      title="当前身份无法查看最近访问"
      description="请切换为教师身份后查看自己的项目访问记录。"
    />
    <StatePanel
      v-else-if="loading && recentProjects.length === 0"
      type="loading"
      title="正在读取最近访问"
      description="正在读取你的项目访问记录。"
    />
    <StatePanel
      v-else-if="errorMessage && recentProjects.length === 0"
      type="error"
      title="最近访问读取失败"
      :description="errorMessage"
    >
      <template #action>
        <el-button type="primary" :icon="Refresh" @click="loadRecentProjects">重新加载</el-button>
      </template>
    </StatePanel>

    <section v-else-if="isTeacher" class="surface-panel recent-panel" v-loading="loading">
      <el-alert
        v-if="errorMessage"
        class="recent-panel__alert"
        type="error"
        :title="errorMessage"
        show-icon
        :closable="false"
      />
      <el-empty v-if="recentProjects.length === 0" description="暂无最近访问的项目" :image-size="72" />
      <ul v-else class="recent-list">
        <li v-for="item in recentProjects" :key="item.project.id">
          <div class="recent-list__identity">
            <strong>{{ item.project.projectName }}</strong>
            <span>{{ item.project.courseName }} · {{ item.project.chapterTitle }}</span>
          </div>
          <div class="recent-list__facts">
            <span>最近访问 {{ formatFullDateTime(item.lastVisitedAt) }}</span>
            <span>已访问 {{ item.visitCount }} 次</span>
          </div>
          <el-button type="primary" plain @click="openProject(item.project.id)">打开项目</el-button>
        </li>
      </ul>
    </section>
  </section>
</template>

<script setup lang="ts">
import { listRecentProjects, type RecentProject } from '@/api/projects';
import PageHeader from '@/components/PageHeader.vue';
import StatePanel from '@/components/StatePanel.vue';
import { useAuthStore } from '@/stores/auth';
import { formatFullDateTime } from '@/utils/presentation';
import { Refresh } from '@element-plus/icons-vue';
import { computed, ref, watch } from 'vue';
import { useRouter } from 'vue-router';

const auth = useAuthStore();
const router = useRouter();
const recentProjects = ref<RecentProject[]>([]);
const loading = ref(false);
const errorMessage = ref('');
let requestSequence = 0;

const isTeacher = computed(() => auth.activeRole === 'TEACHER');

watch(
  () => auth.activeRole,
  () => {
    requestSequence += 1;
    recentProjects.value = [];
    errorMessage.value = '';
    if (isTeacher.value) void loadRecentProjects();
  },
  { immediate: true },
);

async function loadRecentProjects() {
  if (!isTeacher.value) return;
  const requestId = ++requestSequence;
  loading.value = true;
  errorMessage.value = '';
  try {
    const result = await listRecentProjects();
    if (requestId === requestSequence) recentProjects.value = result;
  } catch (error) {
    if (requestId === requestSequence) errorMessage.value = resolveError(error, '暂时无法读取最近访问，请稍后重试。');
  } finally {
    if (requestId === requestSequence) loading.value = false;
  }
}

function openProject(projectId: number) {
  void router.push({ name: 'project-overview', params: { projectId } });
}

function resolveError(error: unknown, fallback: string) {
  const message = (error as { response?: { data?: { message?: string } } }).response?.data?.message;
  return message && !/(Exception|java\.|Axios)/i.test(message) ? message : fallback;
}
</script>

<style scoped>
.recent-panel {
  overflow: hidden;
}

.recent-panel__alert {
  margin: 16px 20px 0;
}

.recent-list {
  margin: 0;
  padding: 0;
  list-style: none;
}

.recent-list li {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(250px, 0.7fr) auto;
  align-items: center;
  gap: 18px;
  padding: 18px 20px;
  border-bottom: 1px solid var(--color-border);
}

.recent-list li:last-child {
  border-bottom: 0;
}

.recent-list__identity,
.recent-list__facts {
  display: grid;
  min-width: 0;
  gap: 5px;
}

.recent-list__identity strong,
.recent-list__identity span,
.recent-list__facts span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.recent-list__identity strong {
  color: var(--color-text);
  font-size: 15px;
  line-height: 1.45;
}

.recent-list__identity span,
.recent-list__facts span {
  color: var(--color-text-muted);
  font-size: 13px;
  line-height: 1.5;
}

.recent-list__facts span:first-child {
  color: var(--color-text-secondary);
}

@media (max-width: 720px) {
  .recent-list li {
    grid-template-columns: 1fr auto;
  }

  .recent-list__facts {
    grid-column: 1 / -1;
  }
}

@media (max-width: 520px) {
  .recent-list li {
    display: flex;
    align-items: stretch;
    flex-direction: column;
    gap: 12px;
    padding: 16px;
  }

  .recent-list .el-button {
    width: 100%;
  }
}
</style>
