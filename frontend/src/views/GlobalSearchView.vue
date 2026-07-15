<template>
  <section class="page global-search-page">
    <PageHeader
      eyebrow="全局搜索"
      title="查找工作内容"
      :description="pageDescription"
    />

    <section class="surface-panel search-panel">
      <form class="search-form" role="search" @submit.prevent="submitSearch">
        <el-input
          ref="searchInput"
          v-model="searchText"
          size="large"
          clearable
          :prefix-icon="Search"
          placeholder="搜索名称、编号、课程或内容"
          aria-label="全局搜索"
          @clear="clearSearch"
        />
        <el-button type="primary" :icon="Search" :loading="loading" native-type="submit">搜索</el-button>
      </form>

      <StatePanel
        v-if="!query"
        class="search-state"
        type="empty"
        title="输入关键词开始搜索"
        :description="emptyPrompt"
      />
      <StatePanel
        v-else-if="loading"
        class="search-state"
        type="loading"
        title="正在聚合相关内容"
        description="正在读取当前身份可访问的真实业务数据。"
      />
      <StatePanel
        v-else-if="errorMessage && results.length === 0"
        class="search-state"
        type="error"
        title="搜索暂时不可用"
        :description="errorMessage"
      >
        <template #action>
          <el-button type="primary" :icon="Refresh" @click="runSearch(query)">重新搜索</el-button>
        </template>
      </StatePanel>

      <template v-else-if="query">
        <el-alert
          v-if="failedSources.length > 0"
          class="search-panel__alert"
          type="warning"
          :title="`部分数据暂时不可用：${failedSources.join('、')}`"
          show-icon
          :closable="false"
        />
        <header class="search-results__header">
          <span>“{{ query }}”</span>
          <strong>{{ results.length }} 条结果</strong>
        </header>
        <el-empty v-if="results.length === 0" description="没有找到匹配内容" :image-size="72" />
        <ul v-else class="search-results">
          <li v-for="result in results" :key="result.id">
            <button type="button" class="search-result" @click="openResult(result)">
              <span :class="['search-result__kind', `search-result__kind--${result.kind.toLowerCase()}`]">
                {{ kindLabel(result.kind) }}
              </span>
              <span class="search-result__body">
                <strong>{{ result.title }}</strong>
                <span>{{ result.description }}</span>
                <small>{{ result.meta }}</small>
              </span>
              <el-icon class="search-result__arrow"><ArrowRight /></el-icon>
            </button>
          </li>
        </ul>
      </template>
    </section>
  </section>
</template>

<script setup lang="ts">
import { searchGlobalData, type GlobalSearchKind, type GlobalSearchResult } from '@/api/globalSearch';
import PageHeader from '@/components/PageHeader.vue';
import StatePanel from '@/components/StatePanel.vue';
import { useAuthStore } from '@/stores/auth';
import { ArrowRight, Refresh, Search } from '@element-plus/icons-vue';
import { computed, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';

const route = useRoute();
const router = useRouter();
const auth = useAuthStore();
const searchInput = ref<{ focus: () => void }>();
const searchText = ref('');
const query = ref('');
const results = ref<GlobalSearchResult[]>([]);
const failedSources = ref<string[]>([]);
const loading = ref(false);
const errorMessage = ref('');
let requestSequence = 0;

const pageDescription = computed(() => `仅搜索当前${roleLabel.value}身份能够访问的内容。`);
const emptyPrompt = computed(() => ({
  TEACHER: '可搜索教学项目、教学任务与项目问题。',
  LEADER: '可搜索教学任务、审批、发布与问答。',
  STUDENT: '可搜索已发布学习任务与自己的问答。',
}[auth.activeRole || 'TEACHER']));
const roleLabel = computed(() => ({
  TEACHER: '教师',
  LEADER: '教研负责人',
  STUDENT: '学生',
}[auth.activeRole || 'TEACHER']));

watch(
  () => route.query.q,
  (value) => {
    const nextQuery = typeof value === 'string' ? value.trim() : '';
    searchText.value = nextQuery;
    void runSearch(nextQuery);
  },
  { immediate: true },
);

watch(
  () => auth.activeRole,
  () => {
    if (query.value) void runSearch(query.value);
  },
);

async function submitSearch() {
  const nextQuery = searchText.value.trim();
  await router.replace({ name: 'global-search', query: nextQuery ? { q: nextQuery } : {} });
}

async function clearSearch() {
  await router.replace({ name: 'global-search', query: {} });
  searchInput.value?.focus();
}

async function runSearch(nextQuery: string) {
  query.value = nextQuery;
  if (!nextQuery || !auth.activeRole) {
    requestSequence += 1;
    results.value = [];
    failedSources.value = [];
    errorMessage.value = '';
    loading.value = false;
    return;
  }

  const requestId = ++requestSequence;
  loading.value = true;
  errorMessage.value = '';
  try {
    const response = await searchGlobalData(auth.activeRole, nextQuery);
    if (requestId !== requestSequence) return;
    results.value = response.results;
    failedSources.value = response.failedSources;
    if (response.failedSources.length > 0 && response.results.length === 0) {
      errorMessage.value = '当前搜索来源暂时不可用，请稍后重试。';
    }
  } catch {
    if (requestId === requestSequence) errorMessage.value = '搜索暂时不可用，请稍后重试。';
  } finally {
    if (requestId === requestSequence) loading.value = false;
  }
}

function openResult(result: GlobalSearchResult) {
  void router.push(result.destination);
}

function kindLabel(kind: GlobalSearchKind) {
  return ({
    PROJECT: '项目',
    TASK: '任务',
    QUESTION: '问答',
    APPROVAL: '审批',
    PUBLICATION: '发布',
    LEARNING_TASK: '学习任务',
  })[kind];
}
</script>

<style scoped>
.search-panel {
  overflow: hidden;
}

.search-form {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 10px;
  padding: 20px;
  border-bottom: 1px solid var(--color-border);
}

.search-state {
  margin: 20px;
}

.search-panel__alert {
  margin: 16px 20px 0;
}

.search-results__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 18px 20px;
  color: var(--color-text-secondary);
  font-size: 13px;
}

.search-results__header strong {
  color: var(--color-text);
  white-space: nowrap;
}

.search-results {
  margin: 0;
  padding: 0;
  list-style: none;
}

.search-results li {
  border-top: 1px solid var(--color-border);
}

.search-result {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  width: 100%;
  gap: 14px;
  padding: 17px 20px;
  border: 0;
  background: transparent;
  color: inherit;
  text-align: left;
  cursor: pointer;
}

.search-result:hover {
  background: var(--color-surface-subtle);
}

.search-result:focus-visible {
  outline: 3px solid var(--color-primary-border);
  outline-offset: -3px;
}

.search-result__kind {
  align-self: start;
  min-width: 48px;
  padding: 3px 6px;
  border-radius: var(--radius-sm);
  background: var(--color-primary-soft);
  color: var(--color-primary);
  font-size: 12px;
  font-weight: 700;
  text-align: center;
}

.search-result__kind--task,
.search-result__kind--approval {
  background: var(--color-warning-soft);
  color: var(--color-warning);
}

.search-result__kind--question,
.search-result__kind--learning_task {
  background: var(--color-success-soft);
  color: var(--color-success);
}

.search-result__kind--publication {
  background: #f0efff;
  color: var(--color-ai);
}

.search-result__body {
  display: grid;
  min-width: 0;
  gap: 4px;
}

.search-result__body strong,
.search-result__body > span,
.search-result__body small {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.search-result__body strong {
  color: var(--color-text);
  font-size: 14px;
  line-height: 1.45;
}

.search-result__body > span {
  color: var(--color-text-secondary);
  font-size: 13px;
  line-height: 1.45;
}

.search-result__body small {
  color: var(--color-text-muted);
  font-size: 12px;
}

.search-result__arrow {
  align-self: center;
  color: var(--color-text-muted);
}

@media (max-width: 560px) {
  .search-form {
    grid-template-columns: 1fr;
    padding: 16px;
  }

  .search-form .el-button {
    width: 100%;
  }

  .search-state,
  .search-panel__alert {
    margin-right: 16px;
    margin-left: 16px;
  }

  .search-result {
    grid-template-columns: auto minmax(0, 1fr);
    padding: 16px;
  }

  .search-result__arrow {
    display: none;
  }

  .search-result__body > span {
    white-space: normal;
  }
}
</style>
