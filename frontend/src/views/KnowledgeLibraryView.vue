<template>
  <section class="page knowledge-library-page">
    <header class="page-hero">
      <div>
        <h2>知识库</h2>
        <p>查看本人项目中已建立的知识索引，并进入对应项目执行真实检索。</p>
      </div>
      <el-button :loading="loading" @click="loadLibrary">刷新统计</el-button>
    </header>

    <StatePanel
      v-if="!canAccess"
      type="info"
      title="当前角色无权访问知识库"
      description="知识库仅对教师角色开放，请切换为教师角色后查看本人项目的知识索引。"
    />
    <StatePanel v-else-if="loading" type="loading" title="正在读取知识库统计" description="正在汇总本人项目的资料和知识索引状态。" />
    <StatePanel v-else-if="errorMessage" type="error" title="知识库读取失败" :description="errorMessage">
      <template #action><el-button type="primary" @click="loadLibrary">重新加载</el-button></template>
    </StatePanel>

    <template v-else>
      <StatePanel v-if="entries.length === 0" type="empty" title="还没有教学项目" description="创建教学项目并完成资料解析后，可在这里查看知识索引入口。" />
      <template v-else>
        <section class="knowledge-library__metrics">
          <article><span>教学项目</span><strong>{{ entries.length }}</strong><small>本人可访问项目</small></article>
          <article><span>已索引资料</span><strong>{{ indexedMaterialCount }}</strong><small>可用于知识检索</small></article>
          <article><span>知识片段</span><strong>{{ knowledgeChunkCount }}</strong><small>来自真实项目统计</small></article>
        </section>

        <section class="panel knowledge-library__workspace">
          <div class="knowledge-library__controls">
            <div>
              <span>项目知识检索</span>
              <h3>选择项目进入知识检索</h3>
            </div>
            <el-select v-model="selectedProjectId" placeholder="选择教学项目">
              <el-option v-for="entry in entries" :key="entry.id" :label="entry.projectName" :value="entry.id" />
            </el-select>
            <el-button type="primary" :disabled="!selectedProject" @click="openKnowledge">进入项目检索</el-button>
          </div>
          <div v-if="selectedProject" class="knowledge-library__selected">
            <strong>{{ selectedProject.projectName }}</strong>
            <span>{{ selectedProject.courseName }} · {{ selectedProject.chapterTitle }}</span>
            <span>已索引 {{ selectedProject.indexedMaterialCount }} 份资料，知识片段 {{ selectedProject.knowledgeChunkCount }}</span>
          </div>
        </section>

        <section class="panel knowledge-library__table">
          <el-table :data="entries" table-layout="fixed" @row-click="selectProject">
            <el-table-column label="教学项目" min-width="250">
              <template #default="{ row }"><strong>{{ row.projectName }}</strong><span class="knowledge-library__chapter">{{ row.courseName }} · {{ row.chapterTitle }}</span></template>
            </el-table-column>
            <el-table-column label="已索引资料" width="130"><template #default="{ row }">{{ row.indexedMaterialCount }}</template></el-table-column>
            <el-table-column label="知识片段" width="120"><template #default="{ row }"><strong>{{ row.knowledgeChunkCount }}</strong></template></el-table-column>
            <el-table-column label="检索状态" width="130"><template #default="{ row }"><span :class="['tag-soft', row.knowledgeChunkCount ? 'success' : 'warning']">{{ row.knowledgeChunkCount ? '可进入检索' : '暂无片段' }}</span></template></el-table-column>
            <el-table-column label="操作" width="130" fixed="right"><template #default="{ row }"><el-button text type="primary" @click.stop="openKnowledge(row.id)">进入检索</el-button></template></el-table-column>
          </el-table>
        </section>
      </template>
    </template>
  </section>
</template>

<script setup lang="ts">
import { listProjects } from '@/api/projects';
import { getProjectWorkspaceOverview } from '@/api/workspace';
import StatePanel from '@/components/StatePanel.vue';
import { useAuthStore } from '@/stores/auth';
import { computed, ref, watch } from 'vue';
import { useRouter } from 'vue-router';

interface KnowledgeLibraryEntry {
  id: number;
  projectName: string;
  courseName: string;
  chapterTitle: string;
  indexedMaterialCount: number;
  knowledgeChunkCount: number;
}

const auth = useAuthStore();
const router = useRouter();
const entries = ref<KnowledgeLibraryEntry[]>([]);
const selectedProjectId = ref<number>();
const loading = ref(false);
const errorMessage = ref('');
const canAccess = computed(() => auth.activeRole === 'TEACHER');
const selectedProject = computed(() => entries.value.find((entry) => entry.id === selectedProjectId.value));
const indexedMaterialCount = computed(() => entries.value.reduce((total, entry) => total + entry.indexedMaterialCount, 0));
const knowledgeChunkCount = computed(() => entries.value.reduce((total, entry) => total + entry.knowledgeChunkCount, 0));

watch(canAccess, (allowed) => {
  if (allowed) loadLibrary();
  else {
    entries.value = [];
    selectedProjectId.value = undefined;
  }
}, { immediate: true });

async function loadLibrary() {
  if (!canAccess.value) return;
  loading.value = true;
  errorMessage.value = '';
  try {
    const projects = await listProjects();
    const overviews = await Promise.all(projects.map((project) => getProjectWorkspaceOverview(project.id)));
    entries.value = overviews.map((overview) => ({
      id: overview.project.id,
      projectName: overview.project.projectName,
      courseName: overview.project.courseName,
      chapterTitle: overview.project.chapterTitle,
      indexedMaterialCount: overview.metrics.indexedMaterialCount,
      knowledgeChunkCount: overview.metrics.knowledgeChunkCount,
    }));
    if (!entries.value.some((entry) => entry.id === selectedProjectId.value)) selectedProjectId.value = entries.value[0]?.id;
  } catch (error) {
    errorMessage.value = resolveError(error, '暂时无法读取本人项目的知识库统计，请稍后重试。');
  } finally {
    loading.value = false;
  }
}

function selectProject(entry: KnowledgeLibraryEntry) {
  selectedProjectId.value = entry.id;
}

function openKnowledge(projectId = selectedProjectId.value) {
  if (projectId) router.push({ name: 'project-knowledge', params: { projectId } });
}

function resolveError(error: unknown, fallback: string) {
  const message = (error as { response?: { data?: { message?: string } } }).response?.data?.message;
  return message && !/(Exception|java\.|Axios)/i.test(message) ? message : fallback;
}
</script>

<style scoped>
.knowledge-library__metrics { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 14px; margin-bottom: 16px; }
.knowledge-library__metrics article { padding: 17px 18px; border: 1px solid var(--ui-border); border-radius: 8px; background: var(--ui-panel); }
.knowledge-library__metrics span, .knowledge-library__metrics strong, .knowledge-library__metrics small { display: block; }
.knowledge-library__metrics span, .knowledge-library__metrics small { color: var(--ui-muted); font-size: 12px; }
.knowledge-library__metrics strong { margin: 5px 0; color: var(--ui-primary); font-size: 25px; }
.knowledge-library__workspace { margin-bottom: 16px; }
.knowledge-library__controls { display: grid; grid-template-columns: minmax(200px, 1fr) minmax(220px, 0.85fr) auto; align-items: end; gap: 14px; }
.knowledge-library__controls span { color: var(--ui-primary); font-size: 12px; font-weight: 700; }
.knowledge-library__controls h3 { margin: 5px 0 0; }
.knowledge-library__selected { display: grid; gap: 4px; margin-top: 18px; padding-top: 15px; border-top: 1px solid var(--ui-border); color: var(--ui-muted); font-size: 13px; }
.knowledge-library__selected strong { color: var(--ui-text); }
.knowledge-library__chapter { display: block; margin-top: 4px; color: var(--ui-muted); font-size: 12px; }
@media (max-width: 760px) { .knowledge-library__metrics { grid-template-columns: 1fr; } .knowledge-library__controls { grid-template-columns: 1fr; align-items: stretch; } .knowledge-library__controls .el-button { width: 100%; } .knowledge-library__table { overflow-x: auto; } .knowledge-library__table :deep(.el-table) { min-width: 720px; } }
</style>
