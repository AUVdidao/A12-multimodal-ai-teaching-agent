<template>
  <section class="page student-insights-page">
    <PageHeader
      eyebrow="学情洞察"
      title="班级与提问洞察"
      :description="pageDescription"
    >
      <template #actions>
        <el-tooltip content="刷新洞察" placement="bottom">
          <el-button
            class="insights-refresh-action"
            circle
            :icon="Refresh"
            :loading="loading"
            aria-label="刷新学情洞察"
            @click="loadInsights"
          />
        </el-tooltip>
      </template>
    </PageHeader>

    <StatePanel
      v-if="!canAccess"
      type="error"
      title="当前身份无法查看学情洞察"
      description="请切换为教师或教研负责人身份后查看授权范围内的数据。"
    />
    <StatePanel
      v-else-if="loading && !loaded"
      type="loading"
      title="正在汇总班级与问答数据"
      description="正在读取当前范围内的发布记录和学生问答。"
    />
    <StatePanel
      v-else-if="errorMessage && !hasData"
      type="error"
      title="学情洞察暂时不可用"
      :description="errorMessage"
    >
      <template #action>
        <el-button type="primary" :icon="Refresh" @click="loadInsights">重新加载</el-button>
      </template>
    </StatePanel>

    <template v-else-if="canAccess">
      <el-alert
        v-if="failedSources.length > 0"
        class="insights-alert"
        type="warning"
        :title="`部分数据暂时不可用：${failedSources.join('、')}`"
        show-icon
        :closable="false"
      />

      <section class="insight-metrics" aria-label="学情指标">
        <article v-for="metric in metrics" :key="metric.key" class="insight-metric">
          <span>{{ metric.label }}</span>
          <strong>{{ metric.value }}</strong>
          <p>{{ metric.description }}</p>
        </article>
      </section>

      <section v-if="showUnifiedState" class="surface-panel insight-empty" aria-labelledby="insight-empty-heading">
        <span class="insight-empty__icon"><el-icon><ChatDotRound /></el-icon></span>
        <div class="insight-empty__content">
          <span class="insight-empty__eyebrow">当前统计范围</span>
          <h2 id="insight-empty-heading">{{ unifiedStateTitle }}</h2>
          <p>{{ unifiedStateDescription }}</p>
          <dl class="insight-empty__facts">
            <div>
              <dt>发布覆盖范围</dt>
              <dd>{{ publicationCoverageStatus }}</dd>
            </div>
            <div>
              <dt>问题来源状态</dt>
              <dd>{{ questionSourceStatus }}</dd>
            </div>
          </dl>
          <el-button type="primary" :icon="ArrowRight" @click="goTo(publicationRoute)">
            {{ unifiedActionLabel }}
          </el-button>
        </div>
      </section>

      <template v-else>
        <section class="surface-panel insight-section" aria-labelledby="class-scope-heading">
          <header class="insight-section__header">
            <div>
              <h2 id="class-scope-heading">班级与发布范围</h2>
              <p>仅统计当前授权范围内仍处于已发布状态的记录。</p>
            </div>
            <el-select v-model="classFilter" clearable placeholder="全部班级" aria-label="按班级筛选洞察">
              <el-option v-for="item in classOptions" :key="item.value" :label="item.label" :value="item.value" />
            </el-select>
          </header>
          <StatePanel
            v-if="failedSources.includes('发布记录')"
            type="error"
            title="发布范围暂时不可用"
            description="刷新后可重新读取班级发布记录。"
          />
          <el-empty v-else-if="classRows.length === 0" description="暂无已发布的班级学习内容" :image-size="64" />
          <ul v-else class="class-list">
            <li v-for="item in classRows" :key="item.key">
              <div class="class-list__identity">
                <strong>{{ item.className }}</strong>
                <span>{{ item.courseName }}</span>
              </div>
              <div class="class-list__metrics">
                <span>{{ item.publicationCount }} 条发布</span>
                <span>{{ item.questionCount }} 个提问</span>
                <span>{{ item.answerRate === null ? '暂无回答数据' : `已回答率 ${item.answerRate}%` }}</span>
              </div>
              <button
                type="button"
                class="insight-link"
                :aria-label="`查看${item.className}的问答`"
                @click="openQuestionScope(item.publicationIds[0])"
              >
                <span>问答</span>
                <el-icon><ArrowRight /></el-icon>
              </button>
            </li>
          </ul>
        </section>

        <section class="insights-grid">
          <section class="surface-panel insight-section" aria-labelledby="publication-heading">
            <header class="insight-section__header">
              <div>
                <h2 id="publication-heading">发布层面的提问情况</h2>
                <p>按发布内容统计实际收到的问题与回答。</p>
              </div>
            </header>
            <el-empty v-if="publicationRows.length === 0" description="暂无符合当前班级筛选的发布记录" :image-size="64" />
            <ul v-else class="publication-list">
              <li v-for="item in publicationRows" :key="item.publication.id">
                <div>
                  <strong>{{ item.publication.title }}</strong>
                  <span>{{ item.publication.courseName }} · {{ item.publication.className }}</span>
                </div>
                <div class="publication-list__metrics">
                  <span>{{ item.questionCount }} 个提问</span>
                  <span>{{ item.answerRate === null ? '暂无回答数据' : `已回答率 ${item.answerRate}%` }}</span>
                </div>
                <button
                  type="button"
                  class="insight-link"
                  :aria-label="`查看${item.publication.title}的问答`"
                  @click="openQuestionScope(item.publication.id)"
                >
                  <span>问答</span>
                  <el-icon><ArrowRight /></el-icon>
                </button>
              </li>
            </ul>
          </section>

          <section class="surface-panel insight-section" aria-labelledby="topic-heading">
            <header class="insight-section__header">
              <div>
                <h2 id="topic-heading">重复提问主题</h2>
                <p>仅显示在当前范围中出现至少两次的真实问题标题。</p>
              </div>
            </header>
            <StatePanel
              v-if="failedSources.includes('学生问答')"
              type="error"
              title="问答数据暂时不可用"
              description="刷新后可重新计算提问主题。"
            />
            <StatePanel
              v-else-if="topicRows.length === 0"
              type="empty"
              title="数据不足以形成重复提问主题"
              :description="questionCount === 0 ? '当前范围内还没有学生提问。' : '当前问题标题尚未出现重复主题。'"
            />
            <ul v-else class="topic-list">
              <li v-for="item in topicRows" :key="item.title">
                <div>
                  <strong>{{ item.title }}</strong>
                  <span>{{ item.count }} 次提问 · {{ item.answeredCount }} 条已有回答</span>
                </div>
                <button
                  type="button"
                  class="insight-link"
                  :aria-label="`查看问题主题${item.title}`"
                  @click="openQuestionScope(item.publicationId)"
                >
                  <span>查看</span>
                  <el-icon><ArrowRight /></el-icon>
                </button>
              </li>
            </ul>
          </section>
        </section>
      </template>
    </template>
  </section>
</template>

<script setup lang="ts">
import { loadStudentInsightsData, type StudentInsightsData } from '@/api/teachingAnalytics';
import PageHeader from '@/components/PageHeader.vue';
import StatePanel from '@/components/StatePanel.vue';
import { useAuthStore } from '@/stores/auth';
import { ArrowRight, ChatDotRound, Refresh } from '@element-plus/icons-vue';
import { computed, ref, watch } from 'vue';
import { useRouter } from 'vue-router';

const auth = useAuthStore();
const router = useRouter();
const data = ref<StudentInsightsData>({ publications: [], questions: [], failedSources: [] });
const loading = ref(false);
const loaded = ref(false);
const errorMessage = ref('');
const classFilter = ref<number>();
let requestSequence = 0;

const isTeacher = computed(() => auth.activeRole === 'TEACHER');
const isLeader = computed(() => auth.activeRole === 'LEADER');
const canAccess = computed(() => isTeacher.value || isLeader.value);
const pageDescription = computed(() => isTeacher.value
  ? '查看自己项目发布到班级后的真实提问与回答情况。'
  : '查看自己发布范围内的班级与学生问答情况。');
const failedSources = computed(() => data.value.failedSources);
const activePublications = computed(() => data.value.publications.filter((publication) => publication.status === 'PUBLISHED'));
const classOptions = computed(() => [...new Map(activePublications.value
  .map((publication) => [publication.classId, { value: publication.classId, label: publication.className }]))
  .values()]);
const selectedPublications = computed(() => activePublications.value.filter((publication) => {
  return classFilter.value == null || publication.classId === classFilter.value;
}));
const publicationIds = computed(() => new Set(selectedPublications.value.map((publication) => publication.id)));
const scopedQuestions = computed(() => data.value.questions.filter((question) => publicationIds.value.has(question.publicationId)));
const activePublicationIds = computed(() => new Set(activePublications.value.map((publication) => publication.id)));
const activeQuestionCount = computed(() => data.value.questions.filter((question) => (
  activePublicationIds.value.has(question.publicationId)
)).length);
const questionCount = computed(() => scopedQuestions.value.length);
const answeredQuestionCount = computed(() => scopedQuestions.value.filter((question) => question.answers.length > 0).length);
const answerRate = computed(() => questionCount.value === 0
  ? '—'
  : `${Math.round((answeredQuestionCount.value / questionCount.value) * 100)}%`);
const hasData = computed(() => data.value.publications.length > 0 || data.value.questions.length > 0);
const questionRoute = computed(() => isTeacher.value ? 'teacher-questions' : 'leader-questions');
const publicationRoute = computed(() => isTeacher.value ? 'teacher-publications' : 'leader-publications');
const publicationSourceFailed = computed(() => failedSources.value.includes('发布记录'));
const questionSourceFailed = computed(() => failedSources.value.includes('学生问答'));
const scopeSourceFailed = computed(() => publicationSourceFailed.value || questionSourceFailed.value);
const showUnifiedState = computed(() => scopeSourceFailed.value || activeQuestionCount.value === 0);
const unifiedStateTitle = computed(() => {
  if (questionSourceFailed.value) return '问题来源暂时无法汇总';
  if (publicationSourceFailed.value) return '发布覆盖暂时无法确认';
  if (activePublications.value.length === 0) return '尚未形成可分析的发布范围';
  return '发布范围已建立，暂未收到学生提问';
});
const unifiedStateDescription = computed(() => {
  if (questionSourceFailed.value) return '已保留能够确认的发布覆盖信息；刷新后可重新读取学生问答。';
  if (publicationSourceFailed.value) return '问答数据已读取，但缺少可核对的发布范围，因此暂不计算派生指标。';
  if (activePublications.value.length === 0 && data.value.publications.length > 0) {
    return '当前可见发布记录均不处于已发布状态，因此不会生成问题计数或回答率。';
  }
  if (activePublications.value.length === 0) {
    return '学生问答只会来自真实发布内容；完成发布后，这里会据实汇总提问。';
  }
  return '当前显示的 0 来自真实接口结果，不会用演示问题填充；学生提交问题后将自动进入统计。';
});
const publicationCoverageStatus = computed(() => {
  if (publicationSourceFailed.value) return '发布记录接口暂时不可用，无法确认覆盖范围。';
  if (activePublications.value.length > 0) {
    return `${classOptions.value.length} 个班级、${activePublications.value.length} 条有效发布纳入统计。`;
  }
  if (data.value.publications.length > 0) return '可见发布记录中暂无处于已发布状态的内容。';
  return '当前授权范围内尚无真实发布记录。';
});
const questionSourceStatus = computed(() => {
  if (questionSourceFailed.value) return '学生问答接口暂时不可用，当前未计算提问数量。';
  if (publicationSourceFailed.value) return '问答数据已读取，但发布范围不可用，暂不计算范围内问题。';
  if (activePublications.value.length === 0) return '没有有效发布作为问题来源，当前不生成问答统计。';
  return `${activePublications.value.length} 条有效发布尚未收到真实学生提问。`;
});
const unifiedActionLabel = computed(() => activePublications.value.length > 0 ? '查看发布范围' : '前往发布管理');
const metrics = computed(() => [
  {
    key: 'classes',
    label: '覆盖班级',
    value: sourceValue('发布记录', classOptions.value.length),
    description: '当前已发布学习内容覆盖的班级',
  },
  {
    key: 'publications',
    label: '有效发布',
    value: sourceValue('发布记录', selectedPublications.value.length),
    description: classFilter.value == null ? '当前范围内处于已发布状态的记录' : '当前班级处于已发布状态的记录',
  },
  {
    key: 'questions',
    label: '学生提问',
    value: scopeValue(questionCount.value),
    description: scopeSourceFailed.value ? '发布范围或学生问答数据暂时不可用' : '当前发布范围内实际收到的问题',
  },
  {
    key: 'answered-rate',
    label: '已回答率',
    value: scopeSourceFailed.value ? '—' : answerRate.value,
    description: scopeSourceFailed.value
      ? '发布范围或学生问答数据暂时不可用'
      : `${answeredQuestionCount.value} / ${questionCount.value} 个问题已有教师回答`,
  },
]);
const classRows = computed(() => {
  const grouped = new Map<number, { className: string; courseName: string; publications: typeof activePublications.value }>();
  selectedPublications.value.forEach((publication) => {
    const current = grouped.get(publication.classId);
    if (current) {
      current.publications.push(publication);
      return;
    }
    grouped.set(publication.classId, {
      className: publication.className,
      courseName: publication.courseName,
      publications: [publication],
    });
  });
  return [...grouped.entries()].map(([classId, group]) => {
    const ids = new Set(group.publications.map((publication) => publication.id));
    const questions = data.value.questions.filter((question) => ids.has(question.publicationId));
    const answered = questions.filter((question) => question.answers.length > 0).length;
    return {
      key: `class:${classId}`,
      className: group.className,
      courseName: group.courseName,
      publicationIds: group.publications.map((publication) => publication.id),
      publicationCount: group.publications.length,
      questionCount: questions.length,
      answerRate: questions.length === 0 ? null : Math.round((answered / questions.length) * 100),
    };
  });
});
const publicationRows = computed(() => selectedPublications.value.map((publication) => {
  const questions = data.value.questions.filter((question) => question.publicationId === publication.id);
  const answered = questions.filter((question) => question.answers.length > 0).length;
  return {
    publication,
    questionCount: questions.length,
    answerRate: questions.length === 0 ? null : Math.round((answered / questions.length) * 100),
  };
}));
const topicRows = computed(() => {
  const groups = new Map<string, { title: string; count: number; answeredCount: number; publicationId: number }>();
  scopedQuestions.value.forEach((question) => {
    const normalized = question.title.trim().toLocaleLowerCase();
    if (!normalized) return;
    const current = groups.get(normalized);
    if (current) {
      current.count += 1;
      current.answeredCount += question.answers.length > 0 ? 1 : 0;
      return;
    }
    groups.set(normalized, {
      title: question.title.trim(),
      count: 1,
      answeredCount: question.answers.length > 0 ? 1 : 0,
      publicationId: question.publicationId,
    });
  });
  return [...groups.values()]
    .filter((group) => group.count >= 2)
    .sort((left, right) => right.count - left.count || left.title.localeCompare(right.title, 'zh-CN'));
});

watch(
  () => auth.activeRole,
  () => {
    requestSequence += 1;
    data.value = emptyData();
    classFilter.value = undefined;
    loaded.value = false;
    errorMessage.value = '';
    if (canAccess.value) void loadInsights();
  },
  { immediate: true },
);

async function loadInsights() {
  if (!canAccess.value) return;
  const requestId = ++requestSequence;
  loading.value = true;
  errorMessage.value = '';
  try {
    const result = await loadStudentInsightsData();
    if (requestId === requestSequence) {
      data.value = result;
      loaded.value = true;
      if (result.failedSources.length === 2) errorMessage.value = '所有洞察数据来源均暂时不可用，请稍后重试。';
    }
  } catch {
    if (requestId === requestSequence) errorMessage.value = '学情洞察暂时不可用，请稍后重试。';
  } finally {
    if (requestId === requestSequence) loading.value = false;
  }
}

function sourceValue(source: string, value: number) {
  return failedSources.value.includes(source) ? '—' : String(value);
}

function scopeValue(value: number) {
  return scopeSourceFailed.value ? '—' : String(value);
}

function openQuestionScope(publicationId: number) {
  void router.push({ name: questionRoute.value, query: { publicationId } });
}

function goTo(destination: string) {
  void router.push({ name: destination });
}

function emptyData(): StudentInsightsData {
  return { publications: [], questions: [], failedSources: [] };
}
</script>

<style scoped>
.insights-alert {
  margin-bottom: 18px;
}

.insight-metrics {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 14px;
  margin-bottom: 18px;
}

.insight-metric {
  display: grid;
  align-content: start;
  min-width: 0;
  min-height: 132px;
  padding: 16px;
  border: 1px solid var(--color-border);
  border-top: 3px solid var(--color-primary);
  border-radius: var(--radius-lg);
  background: var(--color-surface);
  box-shadow: var(--shadow-card);
}

.insight-metric:nth-child(2) { border-top-color: var(--color-success); }
.insight-metric:nth-child(3) { border-top-color: var(--color-warning); }
.insight-metric:nth-child(4) { border-top-color: var(--color-ai); }

.insight-metric > span {
  color: var(--color-text-secondary);
  font-size: 13px;
  font-weight: 700;
}

.insight-metric > strong {
  margin-top: 10px;
  color: var(--color-text);
  font-size: 28px;
  line-height: 1;
}

.insight-metric p {
  margin: 10px 0 0;
  color: var(--color-text-muted);
  font-size: 12px;
  line-height: 1.55;
}

.insight-empty {
  display: grid;
  grid-template-columns: 48px minmax(0, 1fr);
  align-items: start;
  gap: 18px;
  padding: 24px;
  overflow: hidden;
}

.insight-empty__icon {
  display: grid;
  width: 46px;
  height: 46px;
  place-items: center;
  border-radius: 8px;
  background: var(--color-primary-soft);
  color: var(--color-primary);
  font-size: 22px;
}

.insight-empty__content {
  min-width: 0;
}

.insight-empty__eyebrow {
  color: var(--color-text-muted);
  font-size: 12px;
  font-weight: 700;
}

.insight-empty__content h2,
.insight-empty__content p,
.insight-empty__facts {
  margin: 0;
}

.insight-empty__content h2 {
  margin-top: 5px;
  color: var(--color-text);
  font-size: 18px;
  line-height: 1.45;
}

.insight-empty__content > p {
  margin-top: 7px;
  color: var(--color-text-secondary);
  font-size: 13px;
  line-height: 1.65;
}

.insight-empty__facts {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  margin: 18px 0;
  padding: 0;
  border-top: 1px solid var(--color-border);
  border-bottom: 1px solid var(--color-border);
}

.insight-empty__facts div {
  min-width: 0;
  padding: 14px 16px 14px 0;
}

.insight-empty__facts div + div {
  padding-right: 0;
  padding-left: 16px;
  border-left: 1px solid var(--color-border);
}

.insight-empty__facts dt {
  color: var(--color-text-muted);
  font-size: 12px;
  font-weight: 700;
}

.insight-empty__facts dd {
  margin: 6px 0 0;
  color: var(--color-text-secondary);
  font-size: 13px;
  line-height: 1.55;
  overflow-wrap: anywhere;
}

.insight-section {
  overflow: hidden;
}

.insight-section__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  padding: 18px 20px;
  border-bottom: 1px solid var(--color-border);
}

.insight-section__header h2,
.insight-section__header p {
  margin: 0;
}

.insight-section__header h2 {
  color: var(--color-text);
  font-size: 17px;
  line-height: 1.4;
}

.insight-section__header p {
  margin-top: 5px;
  color: var(--color-text-muted);
  font-size: 12px;
  line-height: 1.5;
}

.insight-section__header :deep(.el-select) {
  width: min(220px, 42vw);
}

.class-list,
.publication-list,
.topic-list {
  margin: 0;
  padding: 0;
  list-style: none;
}

.class-list li,
.publication-list li,
.topic-list li {
  display: grid;
  align-items: center;
  gap: 16px;
  padding: 16px 20px;
  border-bottom: 1px solid var(--color-border);
}

.class-list li:last-child,
.publication-list li:last-child,
.topic-list li:last-child {
  border-bottom: 0;
}

.class-list li {
  grid-template-columns: minmax(0, 1fr) minmax(280px, 0.8fr) auto;
}

.publication-list li,
.topic-list li {
  grid-template-columns: minmax(0, 1fr) auto auto;
}

.class-list__identity,
.publication-list li > div,
.topic-list li > div {
  display: grid;
  min-width: 0;
  gap: 5px;
}

.class-list strong,
.class-list__identity span,
.publication-list strong,
.publication-list li > div > span,
.topic-list strong,
.topic-list li > div > span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.class-list strong,
.publication-list strong,
.topic-list strong {
  color: var(--color-text);
  font-size: 14px;
}

.class-list__identity span,
.publication-list li > div > span,
.topic-list li > div > span {
  color: var(--color-text-muted);
  font-size: 12px;
}

.class-list__metrics,
.publication-list__metrics {
  display: flex;
  flex-wrap: wrap;
  gap: 5px 12px;
  color: var(--color-text-secondary);
  font-size: 13px;
}

.insight-link {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
  min-height: 28px;
  padding: 3px 5px;
  border: 0;
  background: transparent;
  color: var(--color-text-secondary);
  font: inherit;
  font-size: 12px;
  cursor: pointer;
  transition: color 160ms ease;
}

.insight-link:hover {
  color: var(--color-text);
}

.insight-link:focus-visible {
  border-radius: 4px;
  outline: 2px solid var(--color-primary);
  outline-offset: 2px;
}

.insight-link .el-icon {
  flex: 0 0 auto;
  font-size: 13px;
}

.insights-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 18px;
  margin-top: 18px;
}

@media (max-width: 1050px) {
  .insight-metrics {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .class-list li {
    grid-template-columns: minmax(0, 1fr) auto;
  }

  .class-list__metrics {
    grid-column: 1 / -1;
  }
}

@media (max-width: 760px) {
  .insights-grid {
    grid-template-columns: 1fr;
  }

  .publication-list li,
  .topic-list li {
    grid-template-columns: minmax(0, 1fr) auto;
  }

  .publication-list__metrics {
    grid-column: 1 / -1;
  }
}

@media (max-width: 560px) {
  .insight-metrics {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  :deep(.page-heading__actions .insights-refresh-action) {
    flex: 0 0 32px;
    width: 32px;
    height: 32px;
    margin-left: auto;
  }

  .insight-section__header,
  .class-list li,
  .publication-list li,
  .topic-list li {
    display: flex;
    align-items: stretch;
    flex-direction: column;
  }

  .insight-section__header :deep(.el-select) {
    width: 100%;
  }

  .insight-empty {
    grid-template-columns: 1fr;
    padding: 20px 16px;
  }

  .insight-empty__facts {
    grid-template-columns: 1fr;
  }

  .insight-empty__facts div {
    padding-right: 0;
  }

  .insight-empty__facts div + div {
    padding-left: 0;
    border-top: 1px solid var(--color-border);
    border-left: 0;
  }

  .insight-empty__content > .el-button {
    width: 100%;
  }

  .insight-link {
    align-self: flex-start;
  }
}
</style>
