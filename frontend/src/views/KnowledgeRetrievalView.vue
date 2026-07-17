<template>
  <section class="page" v-loading="loading">
    <template v-if="overview && materials">
      <div class="grid cols-3">
        <article class="stat-card">
          <small>已索引资料</small>
          <strong>{{ materials.statistics.indexed }}</strong>
          <p class="muted">来自当前项目资料库</p>
        </article>
        <article class="stat-card">
          <small>知识片段</small>
          <strong>{{ overview.metrics.knowledgeChunkCount }}</strong>
          <p class="muted">由已解析资料建立索引</p>
        </article>
        <article class="stat-card">
          <small>检索方式</small>
          <strong class="search-mode">确定性检索</strong>
          <p class="muted">关键词、标题与正文加权</p>
        </article>
      </div>

      <section class="panel search-panel">
        <div class="panel__header">
          <div>
            <h3>本地知识检索</h3>
            <p>只返回当前项目真实知识片段，并展示来源与命中理由。</p>
          </div>
          <el-button type="primary" :loading="searching" :disabled="!query.trim()" @click="executeSearch">执行检索</el-button>
        </div>
        <el-input v-model="query" size="large" clearable placeholder="请输入要检索的教学知识，例如：过拟合的解决方法" @keydown.enter="executeSearch" />
        <div class="search-options">
          <el-select v-model="materialId" clearable placeholder="全部资料" style="width: 220px">
            <el-option v-for="material in indexedMaterials" :key="material.id" :label="material.originalFilename" :value="material.id" />
          </el-select>
          <el-select v-model="matchMode" style="width: 150px">
            <el-option label="精确匹配" value="PRECISE" />
            <el-option label="宽泛匹配" value="BROAD" />
          </el-select>
          <el-switch v-model="caseSensitive" inactive-text="区分大小写" />
          <span class="tag-soft info">本地原型检索</span>
        </div>
      </section>

      <section class="panel results-panel">
        <div class="panel__header">
          <div>
            <h3>检索结果（共 {{ result?.totalElements || 0 }} 条）</h3>
            <p v-if="result">{{ result.algorithm }}</p>
          </div>
          <el-button :disabled="!result?.hits.length" @click="router.push(`/projects/${projectId}/intent`)">采用证据并进入意图</el-button>
        </div>

        <article v-for="item in result?.hits || []" :key="item.chunkId" class="knowledge-card">
          <div class="knowledge-card__score">{{ item.scorePercent }}<small>%</small><span>匹配度</span></div>
          <div class="knowledge-card__body">
            <h3>{{ item.title }}</h3>
            <p>{{ item.content }}</p>
            <p class="hit-reason">{{ item.hitReason }}</p>
            <div class="inline-actions result-tags">
              <span class="tag-soft info">{{ item.sourceFilename }}</span>
              <span class="tag-soft">{{ item.sourceLocation }}</span>
              <span v-for="keyword in item.keywords" :key="keyword" class="tag-soft">{{ keyword }}</span>
            </div>
          </div>
        </article>

        <el-empty
          v-if="!searching && (!result || result.hits.length === 0)"
          :description="result ? '没有命中知识片段，请调整关键词或使用宽泛匹配' : '输入关键词后执行检索'"
          :image-size="86"
        />

        <el-pagination
          v-if="result && result.totalElements > result.size"
          v-model:current-page="currentPage"
          :page-size="result.size"
          :total="result.totalElements"
          layout="prev, pager, next"
          @current-change="executeSearch"
        />
      </section>
    </template>
  </section>
</template>

<script setup lang="ts">
import {
  getMaterialWorkspace,
  getProjectWorkspaceOverview,
  searchKnowledgeWorkspace,
  type KnowledgeWorkspaceSearchResult,
  type MaterialWorkspace,
  type ProjectOverview,
} from '@/api/workspace';
import { ElMessage } from 'element-plus';
import { computed, onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';

const route = useRoute();
const router = useRouter();
const projectId = computed(() => Number(route.params.projectId));
const overview = ref<ProjectOverview>();
const materials = ref<MaterialWorkspace>();
const result = ref<KnowledgeWorkspaceSearchResult>();
const query = ref('');
const materialId = ref<number>();
const matchMode = ref<'PRECISE' | 'BROAD'>('PRECISE');
const caseSensitive = ref(false);
const currentPage = ref(1);
const loading = ref(true);
const searching = ref(false);
const indexedMaterials = computed(() => materials.value?.materials.filter((item) => item.parseStatus === 'SUCCEEDED') || []);

async function loadPage() {
  loading.value = true;
  try {
    [overview.value, materials.value] = await Promise.all([
      getProjectWorkspaceOverview(projectId.value),
      getMaterialWorkspace(projectId.value),
    ]);
  } finally {
    loading.value = false;
  }
}

async function executeSearch() {
  if (!query.value.trim()) {
    ElMessage.warning('请输入检索关键词');
    return;
  }
  searching.value = true;
  try {
    result.value = await searchKnowledgeWorkspace(projectId.value, {
      query: query.value.trim(),
      materialId: materialId.value,
      matchMode: matchMode.value,
      caseSensitive: caseSensitive.value,
      page: currentPage.value - 1,
      size: 10,
    });
  } finally {
    searching.value = false;
  }
}

onMounted(loadPage);
</script>

<style scoped>
.search-mode {
  font-size: 22px !important;
}

.search-panel,
.results-panel {
  margin-top: 16px;
}

.search-options {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
  margin-top: 12px;
}

.knowledge-card {
  display: grid;
  grid-template-columns: 92px minmax(0, 1fr);
  gap: 18px;
  padding: 18px 0;
  border-bottom: 1px solid var(--ui-border);
}

.knowledge-card__score {
  display: grid;
  min-height: 78px;
  place-items: center;
  align-self: start;
  border-radius: 14px;
  background: #edf4ff;
  color: var(--ui-info);
  font-size: 27px;
  font-weight: 800;
}

.knowledge-card__score small,
.knowledge-card__score span {
  font-size: 12px;
  font-weight: 600;
}

.knowledge-card__body h3 {
  margin: 0;
}

.knowledge-card__body > p {
  line-height: 1.7;
}

.hit-reason {
  color: #3d6fc7;
  font-size: 12px;
}

.result-tags {
  flex-wrap: wrap;
}

@media (max-width: 760px) {
  .knowledge-card {
    grid-template-columns: 1fr;
  }

  .knowledge-card__score {
    width: 92px;
  }
}
</style>
