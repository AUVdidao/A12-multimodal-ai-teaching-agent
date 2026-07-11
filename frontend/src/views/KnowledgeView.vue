<template>
  <section class="page knowledge-page">
    <PageHeader eyebrow="M2 · 本地知识库" title="可解释的原型检索" description="从已上传并成功解析的资料中检索知识片段，清楚展示分数、命中理由和来源文件。" :project-label="projectLabel">
      <template #actions><el-button :icon="Refresh" :loading="loading" @click="loadWorkspace">刷新索引</el-button></template>
    </PageHeader>

    <M2ProgressSteps v-if="projectId" :current-step="3" :project-id="projectId" :has-materials="hasMaterials" :has-usages="hasUsages" :has-parsed="hasParsed" :has-knowledge="hasKnowledge" :intent-confirmed="intentConfirmed" />
    <StatePanel v-if="!projectId" type="error" title="没有可用的教学项目" description="请从资料页进入本地知识检索。" />
    <StatePanel v-else-if="loading && !overview" type="loading" title="正在读取知识片段" description="汇总已索引资料和本地原型数据。" />
    <StatePanel v-else-if="errorMessage && !overview" type="error" title="知识库读取失败" :description="errorMessage"><template #action><el-button type="primary" @click="loadWorkspace">重新加载</el-button></template></StatePanel>

    <template v-else-if="overview">
      <section class="knowledge-metrics">
        <div><span>已索引资料</span><strong>{{ overview.indexedMaterialCount }}</strong><small>成功解析后自动建立</small></div>
        <div><span>知识片段</span><strong>{{ overview.chunkCount }}</strong><small>每份资料生成 3 个结构化片段</small></div>
        <div><span>检索方式</span><strong>本地</strong><small>关键词、标题、内容和用途加权</small></div>
      </section>

      <section class="surface-panel search-workspace">
        <div class="search-workspace__heading"><div><span>DETERMINISTIC RETRIEVAL</span><h2>检索教学证据</h2><p>当前不是向量数据库 RAG；相同数据和查询会得到稳定、可复验的结果。</p></div><el-tag type="warning" effect="plain">原型检索</el-tag></div>
        <div class="search-bar"><el-input v-model="query" clearable maxlength="200" placeholder="输入课题、关键词或资料用途，例如：光合作用 / 教材依据" @keyup.enter="runSearch"><template #prefix><el-icon><Search /></el-icon></template></el-input><el-button type="primary" :icon="Search" :loading="searching" :disabled="!query.trim() || !hasKnowledge" @click="runSearch">执行检索</el-button></div>
        <el-alert v-if="errorMessage" :title="errorMessage" type="error" show-icon :closable="false" />
      </section>

      <StatePanel v-if="!hasKnowledge" type="empty" title="还没有可检索的知识片段" description="请返回资料页，为至少一份资料绑定用途并完成原型解析。"><template #action><el-button type="primary" @click="router.push(`/projects/${projectId}/materials`)">返回资料页</el-button></template></StatePanel>
      <section v-else class="results-section">
        <div class="section-title"><div><span>{{ searched ? `命中 ${hits.length} 条` : `已建立 ${overview.chunkCount} 个片段` }}</span><h2>{{ searched ? '知识检索结果' : '等待检索' }}</h2></div><p v-if="searchResult">{{ searchResult.algorithm }}</p></div>
        <StatePanel v-if="searching" type="loading" title="正在执行本地原型检索" />
        <StatePanel v-else-if="searched && hits.length === 0" type="empty" title="没有匹配的知识片段" description="系统不会返回与上传资料无关的固定结果，请更换查询词。" />
        <StatePanel v-else-if="!searched" type="info" title="输入课题或教学关键词开始检索" description="命中结果会显示来源资料、评分依据和用途标签。" />
        <div v-else class="hit-list"><KnowledgeHitCard v-for="hit in hits" :key="hit.chunkId" :hit="hit" /></div>
      </section>

      <PrimaryActionBar>
        <template #info>{{ hits.length ? '已获得真实知识片段命中，可生成资料增强教学意图。' : '至少执行一次有命中的检索后再生成教学意图。' }}</template>
        <template #secondary><el-button @click="router.push(`/projects/${projectId}/materials`)">返回资料与解析</el-button></template>
        <el-button type="primary" :disabled="hits.length === 0" @click="openIntent">下一步：生成教学意图</el-button>
      </PrimaryActionBar>
    </template>
  </section>
</template>

<script setup lang="ts">
import { getKnowledgeOverview, searchKnowledge, type KnowledgeHit, type KnowledgeOverview, type KnowledgeSearchResult } from '@/api/knowledge';
import { listMaterials } from '@/api/materials';
import { getProject } from '@/api/projects';
import { getLatestRequirementSummary } from '@/api/requirementSummaries';
import { getLatestTeachingIntent } from '@/api/teachingIntents';
import KnowledgeHitCard from '@/components/KnowledgeHitCard.vue';
import M2ProgressSteps from '@/components/M2ProgressSteps.vue';
import PageHeader from '@/components/PageHeader.vue';
import PrimaryActionBar from '@/components/PrimaryActionBar.vue';
import StatePanel from '@/components/StatePanel.vue';
import { Refresh, Search } from '@element-plus/icons-vue';
import { computed, onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';

const route = useRoute();
const router = useRouter();
const projectId = computed(() => { const value = Number(route.params.projectId); return Number.isInteger(value) && value > 0 ? value : null; });
const projectLabel = ref<string>();
const overview = ref<KnowledgeOverview | null>(null);
const searchResult = ref<KnowledgeSearchResult | null>(null);
const hits = ref<KnowledgeHit[]>([]);
const query = ref('');
const loading = ref(false);
const searching = ref(false);
const searched = ref(false);
const errorMessage = ref('');
const hasMaterials = ref(false);
const hasUsages = ref(false);
const hasParsed = ref(false);
const intentConfirmed = ref(false);
const hasKnowledge = computed(() => Boolean(overview.value?.chunkCount));

onMounted(loadWorkspace);
async function loadWorkspace() {
  if (!projectId.value) return;
  loading.value = true; errorMessage.value = '';
  try {
    const [project, summary, materialList, knowledge, intent] = await Promise.all([getProject(projectId.value), getLatestRequirementSummary(projectId.value), listMaterials(projectId.value), getKnowledgeOverview(projectId.value), getLatestTeachingIntent(projectId.value)]);
    if (summary?.status !== 'CONFIRMED') { router.replace(`/projects/${projectId.value}/requirement-summary`); return; }
    projectLabel.value = project.projectName; overview.value = knowledge; hasMaterials.value = materialList.length > 0; hasUsages.value = hasMaterials.value && materialList.every((item) => item.usageTypes.length > 0); hasParsed.value = materialList.some((item) => item.parseStatus === 'SUCCEEDED'); intentConfirmed.value = intent?.status === 'CONFIRMED';
    if (!query.value) query.value = summary.topic || project.chapterTitle;
  } catch (error) { errorMessage.value = resolveError(error, '知识库读取失败，请稍后重试。'); }
  finally { loading.value = false; }
}
async function runSearch() {
  if (!projectId.value || !query.value.trim() || searching.value) return;
  searching.value = true; errorMessage.value = '';
  try { searchResult.value = await searchKnowledge(projectId.value, query.value.trim(), 10); hits.value = searchResult.value.hits; searched.value = true; }
  catch (error) { errorMessage.value = resolveError(error, '检索失败，请检查查询词后重试。'); }
  finally { searching.value = false; }
}
function openIntent() { if (projectId.value && hits.value.length) router.push(`/projects/${projectId.value}/teaching-intent`); }
function resolveError(error: unknown, fallback: string) { const message = (error as { response?: { data?: { message?: string } } }).response?.data?.message; return message && !/(Exception|java\.|Axios)/i.test(message) ? message : fallback; }
</script>

<style scoped>
.knowledge-metrics { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 12px; margin-bottom: 17px; }
.knowledge-metrics > div { padding: 16px 18px; border: 1px solid var(--color-border); border-radius: var(--radius-lg); background: var(--color-surface); box-shadow: var(--shadow-card); }
.knowledge-metrics span, .knowledge-metrics strong, .knowledge-metrics small { display: block; }
.knowledge-metrics span { color: var(--color-text-muted); font-size: 10px; font-weight: 700; }
.knowledge-metrics strong { margin-top: 5px; color: var(--color-primary); font-size: 22px; }
.knowledge-metrics small { margin-top: 3px; color: var(--color-text-secondary); font-size: 10px; }
.search-workspace { display: grid; gap: 17px; padding: 21px; }
.search-workspace__heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; }
.search-workspace__heading span, .section-title span { color: var(--color-primary); font-size: 10px; font-weight: 800; }
.search-workspace__heading h2, .search-workspace__heading p, .section-title h2, .section-title p { margin: 0; }
.search-workspace__heading h2 { margin-top: 4px; font-size: 18px; }
.search-workspace__heading p { margin-top: 5px; color: var(--color-text-secondary); font-size: 11px; }
.search-bar { display: grid; grid-template-columns: minmax(0, 1fr) auto; gap: 9px; }
.results-section { margin-top: 24px; }
.section-title { display: flex; align-items: end; justify-content: space-between; gap: 18px; margin-bottom: 12px; }
.section-title h2 { margin-top: 3px; font-size: 18px; }
.section-title p { color: var(--color-text-muted); font-size: 10px; }
.hit-list { display: grid; gap: 12px; }
@media (max-width: 720px) { .knowledge-metrics { grid-template-columns: 1fr; } .search-bar { grid-template-columns: 1fr; } .search-workspace__heading, .section-title { align-items: flex-start; flex-direction: column; } .search-bar .el-button { width: 100%; } }
</style>
