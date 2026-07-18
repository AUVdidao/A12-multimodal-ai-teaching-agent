<template>
  <section class="page knowledge-retrieval-page">
    <StatePanel
      v-if="!validProjectId"
      type="error"
      title="项目参数无效"
      description="当前地址缺少有效的数字项目 ID，请从知识库项目列表重新进入。"
    >
      <template #action>
        <el-button type="primary" @click="router.push({ name: 'knowledge-library' })">返回知识库</el-button>
      </template>
    </StatePanel>

    <StatePanel
      v-else-if="loading"
      type="loading"
      title="正在加载项目知识库"
      description="正在读取项目概况、索引资料数量和知识片段。"
    />

    <StatePanel
      v-else-if="pageError"
      type="error"
      :title="pageError.title"
      :description="pageError.description"
    >
      <template #action>
        <div class="state-actions">
          <el-button v-if="pageError.retryable" type="primary" @click="loadPage">重新加载</el-button>
          <el-button @click="router.push({ name: 'knowledge-library' })">返回知识库</el-button>
        </div>
      </template>
    </StatePanel>

    <template v-else-if="projectOverview && knowledgeOverview">
      <ProjectContextHeader
        :project="projectOverview.project"
        back-to="/resources/knowledge"
        back-label="返回知识库"
      />
      <ProjectWorkspaceNav :project-id="projectOverview.project.id" />

      <section class="knowledge-summary" aria-label="知识索引概况">
        <article class="stat-card">
          <small>已索引资料</small>
          <strong>{{ knowledgeOverview.indexedMaterialCount }}</strong>
          <p class="muted">当前项目可检索来源</p>
        </article>
        <article class="stat-card">
          <small>知识片段</small>
          <strong>{{ knowledgeOverview.chunkCount }}</strong>
          <p class="muted">来自真实资料索引</p>
        </article>
        <article class="stat-card">
          <small>检索方式</small>
          <strong class="search-mode">确定性检索</strong>
          <p class="muted">标题、正文、关键词与用途加权</p>
        </article>
      </section>

      <StatePanel
        v-if="knowledgeOverview.chunkCount === 0"
        class="knowledge-empty"
        type="empty"
        title="当前项目还没有知识索引"
        description="请先上传资料，完成资料用途确认与解析，再建立索引后返回检索。"
      >
        <template #action>
          <el-button type="primary" @click="router.push({ name: 'project-materials', params: { projectId } })">
            前往资料解析与索引
          </el-button>
        </template>
      </StatePanel>

      <template v-else>
        <section class="panel search-panel" aria-labelledby="knowledge-search-title">
          <div class="panel__header">
            <div>
              <h3 id="knowledge-search-title">本地知识检索</h3>
              <p>在当前项目的全部已索引资料中查找知识片段。</p>
            </div>
            <span class="tag-soft info">范围：当前项目</span>
          </div>
          <div class="search-form">
            <el-input
              v-model="query"
              size="large"
              clearable
              maxlength="500"
              placeholder="请输入教学知识问题，例如：过拟合有哪些解决方法"
              @keyup.enter="executeSearch"
            />
            <el-button
              type="primary"
              size="large"
              :loading="searching"
              :disabled="!query.trim()"
              @click="executeSearch"
            >
              执行检索
            </el-button>
          </div>
        </section>

        <section class="panel results-panel" aria-labelledby="knowledge-results-title">
          <div class="panel__header">
            <div>
              <h3 id="knowledge-results-title">检索结果{{ result ? `（共 ${result.hits.length} 条）` : '' }}</h3>
              <p v-if="result">{{ result.algorithm }}</p>
              <p v-else>结果将展示相关度、来源资料、知识片段和匹配理由。</p>
            </div>
          </div>

          <StatePanel
            v-if="searching"
            type="loading"
            title="正在检索当前项目知识"
            description="正在对标题、正文、关键词和资料用途进行匹配。"
          />

          <StatePanel
            v-else-if="searchError"
            type="error"
            title="知识检索失败"
            :description="searchError"
          >
            <template #action><el-button type="primary" @click="executeSearch">重新检索</el-button></template>
          </StatePanel>

          <StatePanel
            v-else-if="!hasSearched"
            type="info"
            title="输入问题开始检索"
            description="可输入概念、教学难点或解决方法，系统只检索当前项目的知识片段。"
          />

          <StatePanel
            v-else-if="result && result.hits.length === 0"
            type="empty"
            title="没有找到匹配的知识片段"
            description="请缩短查询、替换关键词，或检查资料是否已经完成解析和索引。"
          >
            <template #action>
              <el-button @click="router.push({ name: 'project-materials', params: { projectId } })">检查资料索引</el-button>
            </template>
          </StatePanel>

          <div v-else class="knowledge-results">
            <KnowledgeHitCard v-for="hit in result?.hits" :key="hit.chunkId" :hit="hit" />
          </div>
        </section>
      </template>
    </template>
  </section>
</template>

<script setup lang="ts">
import {
  getKnowledgeOverview,
  searchKnowledge,
  type KnowledgeOverview,
  type KnowledgeSearchResult,
} from '@/api/knowledge';
import { getProjectWorkspaceOverview, type ProjectOverview } from '@/api/workspace';
import KnowledgeHitCard from '@/components/KnowledgeHitCard.vue';
import ProjectContextHeader from '@/components/ProjectContextHeader.vue';
import ProjectWorkspaceNav from '@/components/ProjectWorkspaceNav.vue';
import StatePanel from '@/components/StatePanel.vue';
import { ElMessage } from 'element-plus';
import { computed, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';

interface PageError {
  title: string;
  description: string;
  retryable: boolean;
}

const route = useRoute();
const router = useRouter();
const rawProjectId = computed(() => String(route.params.projectId ?? ''));
const validProjectId = computed(() => /^\d+$/.test(rawProjectId.value) && Number(rawProjectId.value) > 0 && Number.isSafeInteger(Number(rawProjectId.value)));
const projectId = computed(() => Number(rawProjectId.value));
const projectOverview = ref<ProjectOverview>();
const knowledgeOverview = ref<KnowledgeOverview>();
const result = ref<KnowledgeSearchResult>();
const query = ref('');
const loading = ref(false);
const searching = ref(false);
const hasSearched = ref(false);
const pageError = ref<PageError>();
const searchError = ref('');

async function loadPage() {
  projectOverview.value = undefined;
  knowledgeOverview.value = undefined;
  result.value = undefined;
  hasSearched.value = false;
  pageError.value = undefined;
  searchError.value = '';
  if (!validProjectId.value) return;

  loading.value = true;
  try {
    [projectOverview.value, knowledgeOverview.value] = await Promise.all([
      getProjectWorkspaceOverview(projectId.value),
      getKnowledgeOverview(projectId.value),
    ]);
  } catch (error) {
    pageError.value = resolvePageError(error);
  } finally {
    loading.value = false;
  }
}

async function executeSearch() {
  const normalizedQuery = query.value.trim();
  if (!normalizedQuery) {
    ElMessage.warning('请输入检索问题');
    return;
  }
  if (!validProjectId.value || !knowledgeOverview.value?.chunkCount) return;

  searching.value = true;
  searchError.value = '';
  hasSearched.value = true;
  try {
    result.value = await searchKnowledge(projectId.value, normalizedQuery, 10);
  } catch (error) {
    const status = responseStatus(error);
    if (status === 403 || status === 404) {
      projectOverview.value = undefined;
      knowledgeOverview.value = undefined;
      pageError.value = resolvePageError(error);
      return;
    }
    result.value = undefined;
    searchError.value = resolveError(error, '暂时无法完成检索，请稍后重试。');
  } finally {
    searching.value = false;
  }
}

function resolvePageError(error: unknown): PageError {
  const status = responseStatus(error);
  if (status === 404) {
    return {
      title: '项目不存在或已被删除',
      description: '无法读取该项目的知识库，请返回知识库选择仍可访问的项目。',
      retryable: false,
    };
  }
  if (status === 403) {
    return {
      title: '无权访问该项目知识库',
      description: '当前账号不是该项目的负责人，请返回知识库选择本人项目。',
      retryable: false,
    };
  }
  return {
    title: '项目知识库加载失败',
    description: resolveError(error, '暂时无法读取项目概况和知识索引，请稍后重试。'),
    retryable: true,
  };
}

function responseStatus(error: unknown) {
  return (error as { response?: { status?: number } }).response?.status;
}

function resolveError(error: unknown, fallback: string) {
  const message = (error as { response?: { data?: { message?: string } } }).response?.data?.message;
  return message && !/(Exception|java\.|Axios)/i.test(message) ? message : fallback;
}

watch(() => route.params.projectId, loadPage, { immediate: true });
</script>

<style scoped>
.state-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.state-actions .el-button + .el-button {
  margin-left: 0;
}

.knowledge-summary {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 16px;
}

.search-mode {
  font-size: 21px !important;
}

.knowledge-empty,
.search-panel,
.results-panel {
  margin-top: 16px;
}

.search-form {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 132px;
  gap: 10px;
}

.knowledge-results {
  display: grid;
  gap: 12px;
}

@media (max-width: 860px) {
  .knowledge-summary {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 640px) {
  .search-form {
    grid-template-columns: 1fr;
  }

  .search-form .el-button {
    width: 100%;
  }
}
</style>
