<template>
  <section class="page learning-page">
    <PageHeader eyebrow="学生学习空间" title="我的学习内容" description="查看所在班级已发布的固定教学成果；内容撤回后不会继续出现在这里。" />

    <StatePanel v-if="loading && tasks.length === 0" type="loading" title="正在读取学习内容" description="正在读取你所在班级的最新发布。" />
    <StatePanel v-else-if="errorMessage && tasks.length === 0" type="error" title="学习内容读取失败" :description="errorMessage">
      <template #action><el-button type="primary" :icon="Refresh" @click="loadTasks">重新加载</el-button></template>
    </StatePanel>
    <StatePanel v-else-if="tasks.length === 0" type="empty" title="暂无已发布的学习内容" description="你所在的班级暂时没有可学习的已发布内容。教师发布后，内容会自动出现在这里。" />

    <section v-else class="learning-layout" v-loading="loading">
      <section class="surface-panel task-list-panel" aria-labelledby="task-list-heading">
        <header class="panel-heading">
          <div><h2 id="task-list-heading">学习任务</h2><p>{{ tasks.length }} 项班级发布</p></div>
          <el-tooltip content="刷新学习内容" placement="bottom">
            <el-button circle :icon="Refresh" :loading="loading" aria-label="刷新学习内容" @click="loadTasks" />
          </el-tooltip>
        </header>
        <ul class="task-list">
          <li v-for="task in tasks" :key="task.publicationId">
            <button type="button" :class="['task-list__item', { 'is-active': selectedTaskId === task.publicationId }]" @click="selectTask(task.publicationId)">
              <span class="task-list__item-title">{{ task.title }}</span>
              <span class="task-list__item-meta">{{ task.courseName }} · {{ task.className }}</span>
              <time :datetime="task.publishedAt">发布于 {{ formatFullDateTime(task.publishedAt) }}</time>
            </button>
          </li>
        </ul>
      </section>

      <section class="surface-panel learning-detail-panel" aria-labelledby="learning-detail-heading">
        <StatePanel v-if="detailLoading" type="loading" title="正在读取学习详情" description="正在读取批准版本的结构化教学成果。" />
        <StatePanel v-else-if="detailError" type="error" title="学习详情暂时不可用" :description="detailError">
          <template #action><el-button type="primary" :icon="Refresh" @click="loadDetail">重新打开</el-button></template>
        </StatePanel>
        <StatePanel v-else-if="!detail" type="empty" title="请选择一项学习内容" description="从左侧列表选择学习任务查看详细内容。" />
        <template v-else>
          <header class="detail-header">
            <div>
              <span class="detail-header__eyebrow">{{ detail.courseName }} · {{ detail.className }}</span>
              <h2 id="learning-detail-heading">{{ detail.title }}</h2>
              <p v-if="detail.summary">{{ detail.summary }}</p>
            </div>
            <div class="detail-header__actions">
              <el-tag type="success" effect="light">已发布</el-tag>
              <RouterLink
                class="learning-question-link"
                :to="{ name: 'student-questions', query: { publicationId: String(detail.publicationId) } }"
              >
                <el-icon><ChatDotRound /></el-icon>
                <span>针对本内容提问</span>
              </RouterLink>
            </div>
          </header>
          <dl class="version-facts">
            <div><dt>项目</dt><dd>{{ detail.projectName }}</dd></div>
            <div><dt>批准版本</dt><dd>版本 {{ detail.artifactVersion.versionNumber }}</dd></div>
            <div><dt>版本说明</dt><dd>{{ detail.artifactVersion.description || '暂无版本说明' }}</dd></div>
            <div><dt>发布时间</dt><dd>{{ formatFullDateTime(detail.publishedAt) }}</dd></div>
          </dl>
          <el-alert v-if="detail.artifacts.length === 0" type="info" title="该批准版本暂时没有可展示的成果内容" :closable="false" show-icon />
          <section v-for="artifact in artifactViews" :key="`${artifact.type}-${artifact.title}`" class="artifact-section">
            <header class="artifact-section__header">
              <div><span>{{ artifactTypeLabel(artifact.type) }}</span><h3>{{ artifact.title }}</h3></div>
              <small>结构版本 {{ artifact.schemaVersion }}</small>
            </header>

            <div v-if="artifact.type === 'PPT'" class="ppt-content">
              <article v-for="slide in artifact.slides" :key="slide.order" class="ppt-slide">
                <span class="content-order">第 {{ slide.order }} 页</span>
                <div><h4>{{ slide.title }}</h4><p v-if="slide.subtitle" class="content-subtitle">{{ slide.subtitle }}</p><ul v-if="slide.bullets.length"><li v-for="bullet in slide.bullets" :key="bullet">{{ bullet }}</li></ul><p v-if="slide.notes" class="content-note">备注：{{ slide.notes }}</p></div>
              </article>
              <StatePanel v-if="artifact.slides.length === 0" type="empty" title="PPT 没有可展示页面" description="该版本的 PPT 内容为空。" />
            </div>

            <div v-else-if="artifact.type === 'DOCX'" class="doc-content">
              <article v-for="section in artifact.sections" :key="section.order" class="doc-section">
                <span class="content-order">{{ String(section.order).padStart(2, '0') }}</span>
                <div><h4>{{ section.title }}</h4><p v-for="paragraph in section.paragraphs" :key="paragraph">{{ paragraph }}</p><p v-if="section.paragraphs.length === 0" class="content-muted">本章节暂无正文内容</p></div>
              </article>
              <StatePanel v-if="artifact.sections.length === 0" type="empty" title="教案没有可展示章节" description="该版本的教案内容为空。" />
            </div>

            <div v-else class="interaction-content">
              <article v-for="question in artifact.questions" :key="question.order" class="question-item">
                <header><span>第 {{ question.order }} 题</span><el-tag v-if="question.type" size="small" effect="plain">{{ question.type }}</el-tag></header>
                <h4>{{ question.question }}</h4>
                <ol v-if="question.options.length" class="question-options"><li v-for="option in question.options" :key="option.value"><strong>{{ option.label }}</strong>{{ option.text }}</li></ol>
                <p v-if="question.answer" class="answer-line"><b>参考答案：</b>{{ question.answer }}</p>
                <p v-if="question.explanation" class="answer-line"><b>解析：</b>{{ question.explanation }}</p>
              </article>
              <StatePanel v-if="artifact.questions.length === 0" type="empty" title="互动成果没有可展示题目" description="该版本的互动内容为空。" />
            </div>
          </section>
        </template>
      </section>
    </section>

    <el-alert v-if="errorMessage && tasks.length" class="inline-alert" type="error" :title="errorMessage" show-icon :closable="false" />
  </section>
</template>

<script setup lang="ts">
import { listLearningTasks, getLearningTask, type LearningTaskDetail, type LearningTaskSummary, type PublishedArtifact } from '@/api/publications';
import type { ArtifactContent } from '@/api/generation';
import PageHeader from '@/components/PageHeader.vue';
import StatePanel from '@/components/StatePanel.vue';
import { normalizeDocSections, normalizeInteractionQuestions, normalizePptSlides, type DocSectionView, type InteractionQuestionView, type PptSlideView } from '@/components/generation/artifactContent';
import { useAuthStore } from '@/stores/auth';
import { formatFullDateTime } from '@/utils/presentation';
import { ChatDotRound, Refresh } from '@element-plus/icons-vue';
import { computed, ref, watch } from 'vue';
import { RouterLink } from 'vue-router';

type ArtifactView = PublishedArtifact & { type: PublishedArtifact['artifactType']; content: ArtifactContent; slides: PptSlideView[]; sections: DocSectionView[]; questions: InteractionQuestionView[] };
const auth = useAuthStore();
const tasks = ref<LearningTaskSummary[]>([]);
const detail = ref<LearningTaskDetail | null>(null);
const selectedTaskId = ref<number | null>(null);
const loading = ref(false);
const detailLoading = ref(false);
const errorMessage = ref('');
const detailError = ref('');
let requestSequence = 0;
let detailSequence = 0;

const artifactViews = computed<ArtifactView[]>(() => (detail.value?.artifacts || []).map((artifact) => {
  const content = parseContent(artifact.contentJson);
  return {
    ...artifact,
    type: artifact.artifactType,
    content,
    slides: artifact.artifactType === 'PPT' ? normalizePptSlides(content) : [],
    sections: artifact.artifactType === 'DOCX' ? normalizeDocSections(content) : [],
    questions: artifact.artifactType === 'INTERACTION' ? normalizeInteractionQuestions(content) : [],
  };
}));

watch(() => auth.activeRole, (role) => {
  requestSequence += 1;
  detailSequence += 1;
  tasks.value = [];
  detail.value = null;
  selectedTaskId.value = null;
  errorMessage.value = '';
  detailError.value = '';
  if (role === 'STUDENT') void loadTasks();
}, { immediate: true });

async function loadTasks() {
  if (auth.activeRole !== 'STUDENT') return;
  const sequence = ++requestSequence;
  loading.value = true;
  errorMessage.value = '';
  try {
    const result = await listLearningTasks();
    if (sequence !== requestSequence) return;
    tasks.value = result;
    if (!result.some((task) => task.publicationId === selectedTaskId.value)) {
      selectedTaskId.value = result[0]?.publicationId || null;
    }
    if (selectedTaskId.value) void loadDetail(selectedTaskId.value);
    else detail.value = null;
  } catch (error) {
    if (sequence === requestSequence) errorMessage.value = resolveError(error, '暂时无法读取学习内容，请稍后重试。');
  } finally {
    if (sequence === requestSequence) loading.value = false;
  }
}

function selectTask(publicationId: number) {
  selectedTaskId.value = publicationId;
  void loadDetail(publicationId);
}

async function loadDetail(publicationId = selectedTaskId.value) {
  if (!publicationId) return;
  const sequence = ++detailSequence;
  detailLoading.value = true;
  detailError.value = '';
  try {
    const result = await getLearningTask(publicationId);
    if (sequence === detailSequence) detail.value = result;
  } catch (error) {
    if (sequence === detailSequence) {
      detail.value = null;
      detailError.value = resolveError(error, '暂时无法读取这项学习内容，请稍后重试。');
      if (isUnavailableLearningTask(error)) {
        detailError.value = '这项学习内容可能已被撤回，列表会在刷新后同步最新状态。';
        tasks.value = tasks.value.filter((task) => task.publicationId !== publicationId);
        if (selectedTaskId.value === publicationId) {
          selectedTaskId.value = tasks.value[0]?.publicationId || null;
          if (selectedTaskId.value) void loadDetail(selectedTaskId.value);
        }
      }
    }
  } finally {
    if (sequence === detailSequence) detailLoading.value = false;
  }
}

function parseContent(value: string): ArtifactContent {
  try {
    const parsed: unknown = JSON.parse(value);
    return (parsed && typeof parsed === 'object') ? parsed as ArtifactContent : {};
  } catch {
    return {};
  }
}

function artifactTypeLabel(type: PublishedArtifact['artifactType']) {
  return type === 'PPT' ? '课件' : type === 'DOCX' ? '教案' : '互动练习';
}

function isUnavailableLearningTask(error: unknown) {
  const status = (error as { response?: { status?: number } }).response?.status;
  return status === 403 || status === 404;
}

function resolveError(error: unknown, fallback: string) {
  const message = (error as { response?: { data?: { message?: string } } }).response?.data?.message;
  return message && !/(Exception|java\.|Axios)/i.test(message) ? message : fallback;
}
</script>

<style scoped>
.learning-layout { display: grid; grid-template-columns: minmax(250px, 0.32fr) minmax(0, 1fr); align-items: start; gap: 20px; }
.task-list-panel { position: sticky; top: calc(var(--header-height) + 18px); overflow: hidden; }
.learning-detail-panel { min-width: 0; padding: 22px; }
.panel-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 16px 18px; border-bottom: 1px solid var(--color-border); }
.panel-heading h2, .panel-heading p { margin: 0; }
.panel-heading h2 { color: var(--color-text); font-size: 16px; }
.panel-heading p { margin-top: 4px; color: var(--color-text-muted); font-size: 12px; }
.task-list { margin: 0; padding: 0; list-style: none; }
.task-list li + li { border-top: 1px solid var(--color-border); }
.task-list__item { display: grid; width: 100%; gap: 5px; padding: 16px 18px; border: 0; border-left: 3px solid transparent; background: var(--color-surface); color: var(--color-text); cursor: pointer; text-align: left; }
.task-list__item:hover, .task-list__item.is-active { border-left-color: var(--color-primary); background: var(--color-primary-soft); }
.task-list__item-title { overflow-wrap: anywhere; font-size: 14px; font-weight: 700; line-height: 1.45; }
.task-list__item-meta, .task-list__item time { overflow-wrap: anywhere; color: var(--color-text-muted); font-size: 12px; line-height: 1.45; }
.detail-header { display: flex; align-items: flex-start; justify-content: space-between; gap: 18px; padding-bottom: 20px; border-bottom: 1px solid var(--color-border); }
.detail-header__actions { display: flex; flex: 0 0 auto; align-items: center; flex-wrap: wrap; justify-content: flex-end; gap: 9px; }
.learning-question-link { display: inline-flex; align-items: center; gap: 6px; min-height: 36px; padding: 0 11px; border: 1px solid var(--color-primary-border); border-radius: var(--radius-md); background: var(--color-primary-soft); color: var(--color-primary); font-size: 12px; font-weight: 700; text-decoration: none; }
.learning-question-link:hover { border-color: var(--color-primary); color: var(--color-primary-hover); }
.detail-header__eyebrow { color: var(--color-primary); font-size: 12px; font-weight: 700; }
.detail-header h2 { margin: 6px 0 0; color: var(--color-text); font-size: 24px; line-height: 1.35; overflow-wrap: anywhere; }
.detail-header p { max-width: 760px; margin: 9px 0 0; color: var(--color-text-secondary); font-size: 13px; line-height: 1.7; white-space: pre-wrap; }
.version-facts { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 16px; padding: 18px 0; margin: 0; border-bottom: 1px solid var(--color-border); }
.version-facts dt { color: var(--color-text-muted); font-size: 12px; }
.version-facts dd { margin: 5px 0 0; color: var(--color-text); font-size: 13px; line-height: 1.5; overflow-wrap: anywhere; }
.artifact-section { padding-top: 24px; }
.artifact-section__header { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 14px; }
.artifact-section__header span { color: var(--color-primary); font-size: 12px; font-weight: 700; }
.artifact-section__header h3 { margin: 4px 0 0; color: var(--color-text); font-size: 18px; line-height: 1.4; overflow-wrap: anywhere; }
.artifact-section__header small { color: var(--color-text-muted); font-size: 11px; }
.ppt-content, .doc-content, .interaction-content { display: grid; gap: 10px; }
.ppt-slide, .doc-section, .question-item { display: grid; grid-template-columns: 64px minmax(0, 1fr); gap: 16px; padding: 16px; border: 1px solid var(--color-border); border-radius: var(--radius-md); background: var(--color-surface-subtle); }
.content-order { color: var(--color-primary); font-size: 12px; font-weight: 700; }
.ppt-slide h4, .doc-section h4, .question-item h4 { margin: 0; color: var(--color-text); font-size: 16px; line-height: 1.45; overflow-wrap: anywhere; }
.ppt-slide ul { display: grid; gap: 7px; padding-left: 20px; margin: 12px 0 0; color: var(--color-text-secondary); font-size: 13px; line-height: 1.65; }
.content-subtitle, .content-note, .doc-section p, .answer-line { margin: 9px 0 0; color: var(--color-text-secondary); font-size: 13px; line-height: 1.7; overflow-wrap: anywhere; white-space: pre-wrap; }
.content-note, .content-muted { color: var(--color-text-muted) !important; }
.question-item { grid-template-columns: 1fr; gap: 10px; }
.question-item header { display: flex; align-items: center; justify-content: space-between; gap: 10px; color: var(--color-primary); font-size: 12px; font-weight: 700; }
.question-options { display: grid; gap: 7px; padding-left: 24px; margin: 0; color: var(--color-text-secondary); font-size: 13px; line-height: 1.6; }
.question-options li { padding-left: 4px; overflow-wrap: anywhere; }
.question-options strong { margin-right: 7px; color: var(--color-primary); }
.answer-line b { color: var(--color-text); }

@media (max-width: 900px) { .learning-layout { grid-template-columns: 1fr; } .task-list-panel { position: static; } .task-list { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); } .task-list li + li { border-top: 0; } .task-list li:nth-child(odd) { border-right: 1px solid var(--color-border); } .task-list li:nth-child(n + 3) { border-top: 1px solid var(--color-border); } }
@media (max-width: 600px) { .learning-detail-panel { padding: 16px; } .task-list { display: block; } .task-list li + li { border-top: 1px solid var(--color-border); } .task-list li:nth-child(odd) { border-right: 0; } .detail-header { flex-direction: column; } .detail-header__actions { align-items: stretch; justify-content: flex-start; } .detail-header h2 { font-size: 21px; } .version-facts { grid-template-columns: repeat(2, minmax(0, 1fr)); } .ppt-slide, .doc-section { grid-template-columns: 1fr; gap: 7px; } }
</style>
