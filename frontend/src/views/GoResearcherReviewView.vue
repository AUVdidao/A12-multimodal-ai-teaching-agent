<template>
  <LessonForgeFrame class="lf-app--light-missions" active="missions" context-label="Review" :recent-missions="recentMissions" :search-missions="recentMissions" :show-new-mission="false">
    <section class="go-review-workspace">
      <div v-if="error && !detail" class="lf-loading go-load-error" role="alert">{{ error }} <button class="lf-secondary-button" type="button" @click="load">重试</button></div>
      <div v-else-if="loading && !detail" class="lf-loading" role="status">正在加载审核内容…</div>
      <template v-else-if="detail">
        <header class="go-review-workspace__header">
          <button class="lf-back-link" type="button" @click="router.push({ name: 'lessonforge-researcher-reviews' })">‹ <span>返回审核队列</span></button>
          <div class="lf-eyebrow">RESEARCHER REVIEW</div>
          <div class="go-review-workspace__title-row"><div><h1>{{ detail.mission.title }}</h1><p>{{ detail.mission.description || '为教师提供下一轮可执行的课件修改建议。' }}</p></div><span class="lf-status-chip"><i class="lf-status-dot lf-status-dot--feedback" />{{ statusLabel(detail.mission.status) }}</span></div>
        </header>

        <div class="go-review-workspace__grid">
          <main>
            <section class="go-review-context lf-plan-card">
              <div class="go-review-section-heading"><div><div class="lf-card-kicker">CURRENT COURSEWARE</div><h2>{{ currentDraft ? `Plan v${currentDraft.version}` : '当前没有计划草稿' }}</h2></div><span v-if="currentDraft" class="go-review-context__version">v{{ currentDraft.version }}</span></div>
              <p v-if="currentDraft" class="go-review-context__summary">请基于当前计划给出可执行的教学与版式建议。反馈会作为本次提交的历史记录保存。</p>
              <div v-if="draftSlides.length" class="go-review-context__slides">
                <article v-for="slide in draftSlides" :key="slide.key" class="go-review-context__slide" :class="{ 'is-expanded': expandedSlideKeys.has(slide.key) }">
                  <button class="go-review-context__slide-toggle" type="button" :aria-expanded="expandedSlideKeys.has(slide.key)" @click="toggleSlide(slide.key)">
                    <span class="go-review-context__slide-number">{{ slide.index }}</span>
                    <span class="go-review-context__slide-title">{{ slide.title }}</span>
                    <span class="go-review-context__slide-chevron" aria-hidden="true">{{ expandedSlideKeys.has(slide.key) ? '−' : '+' }}</span>
                  </button>
                  <div v-if="expandedSlideKeys.has(slide.key)" class="go-review-context__slide-details">
                    <div><span>教学目的</span><p>{{ slide.purpose }}</p></div>
                    <div><span>页面内容</span><ul><li v-for="point in slide.points" :key="point">{{ point }}</li></ul></div>
                    <div><span>视觉与布局</span><p>{{ slide.visual }}</p><small>{{ slide.layout }}</small></div>
                    <div><span>课堂动作</span><p>{{ slide.classroomAction }}</p></div>
                    <div v-if="slide.sources.length" class="go-review-context__slide-sources"><span>来源</span><em v-for="source in slide.sources" :key="source">{{ source }}</em></div>
                  </div>
                </article>
              </div>
              <p v-else class="go-board-empty">当前没有可供逐页标注的计划页；仍可以提交总体反馈。</p>
            </section>

            <section v-if="feedback.length" class="go-review-history lf-plan-card">
              <div class="go-review-section-heading"><div><div class="lf-card-kicker">FEEDBACK HISTORY</div><h2>已提交的反馈</h2></div><span>{{ feedback.length }} 条</span></div>
              <article v-for="item in feedback" :key="item.id" class="go-review-history__item"><div><strong>第 {{ item.submissionVersion }} 版 · {{ item.rating }} 星</strong><time>{{ item.reviewerName }} · {{ formatDate(item.createdAt) }}</time></div><p>{{ item.summary }}</p></article>
            </section>
          </main>

          <aside class="go-review-form lf-plan-card">
            <div class="lf-card-kicker">NEW FEEDBACK</div>
            <h2>提交教研反馈</h2>
            <p class="go-review-form__hint">反馈会写入 Mission，并在教师端会话中显示。</p>
            <label class="go-review-form__field"><span>提交版本</span><input :value="currentDraft?.version || 1" type="number" min="1" readonly /></label>
            <fieldset class="go-review-rating"><legend>总体评分</legend><div><button v-for="rating in [1, 2, 3, 4, 5]" :key="rating" type="button" :class="{ 'is-selected': form.rating === rating }" :aria-label="`${rating} 星`" @click="form.rating = rating">{{ rating }}</button></div></fieldset>
            <label class="go-review-form__field"><span>总体意见</span><textarea v-model="form.summary" rows="5" placeholder="写下最重要的修改方向…" /></label>
            <div class="go-review-form__items-heading"><span>逐页建议</span><button class="lf-secondary-button" type="button" @click="addItem">＋ 添加一条</button></div>
              <div v-for="(item, index) in form.items" :key="item.key" class="go-review-item">
               <div class="go-review-item__top"><select v-model.number="item.slideNumber" aria-label="选择页码" @change="syncSlideTitle(item)"><option :value="0">整体</option><option v-for="slide in draftSlides" :key="slide.number" :value="slide.number">Slide {{ slide.number }}</option></select><input v-model="item.slideTitle" aria-label="页面标题" placeholder="页面标题" /><select v-model="item.severity" aria-label="建议级别"><option value="INFO">建议</option><option value="IMPORTANT">重要</option><option value="BLOCKING">需修改</option></select><button type="button" aria-label="移除逐页建议" @click="removeItem(index)">×</button></div>
              <textarea v-model="item.comment" rows="3" placeholder="具体说明这一页需要如何调整…" />
            </div>
            <p v-if="error" class="go-inline-error" role="alert">{{ error }}</p>
            <button class="lf-primary-button go-review-form__submit" type="button" :disabled="submitting" @click="submitFeedback">{{ submitting ? '正在提交…' : '提交反馈' }}</button>
          </aside>
        </div>
      </template>
    </section>
  </LessonForgeFrame>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import LessonForgeFrame from '@/components/lessonForge/LessonForgeFrame.vue';
import type { LessonForgeMission } from '@/types/lessonForge';
import { createGoMissionFeedback, getGoResearcherMission, goErrorMessage, type GoFeedbackSeverity, type GoMissionFeedback, type GoPlanningDraft, type GoReviewMissionDetail } from '@/api/go';
import { presentPlanSlides } from '@/utils/conversationPresentation';

const route = useRoute();
const router = useRouter();
const detail = ref<GoReviewMissionDetail | null>(null);
const loading = ref(false);
const submitting = ref(false);
const error = ref('');
const currentDraft = computed<GoPlanningDraft | null>(() => detail.value?.currentDraft || null);
const feedback = computed<GoMissionFeedback[]>(() => detail.value?.feedback || []);
const expandedSlideKeys = ref(new Set<string>());
const recentMissions = computed<LessonForgeMission[]>(() => detail.value ? [{ id: String(detail.value.mission.id), teacherId: detail.value.mission.ownerTeacherId, title: detail.value.mission.title, description: detail.value.mission.description, status: 'FEEDBACK', currentPhase: 'UNAVAILABLE', surfaceGate: { submission: false, feedback: true }, recentActivity: detail.value.mission.status, sources: [], messages: [], activities: [], submissions: [] }] : []);
const draftSlides = computed(() => presentPlanSlides(currentDraft.value).map((slide) => ({ ...slide, number: Number.parseInt(slide.index, 10) })));
type ReviewItem = { key: string; slideNumber: number; slideTitle: string; severity: GoFeedbackSeverity; comment: string };
const form = ref<{ submissionVersion: number; rating: number; summary: string; items: ReviewItem[] }>({ submissionVersion: 1, rating: 4, summary: '', items: [] });

async function load() {
  const missionId = Number(route.params.missionId);
  if (!Number.isInteger(missionId) || missionId <= 0) { error.value = 'Mission ID 无效。'; return; }
  loading.value = true;
  error.value = '';
  try {
    detail.value = await getGoResearcherMission(missionId);
    expandedSlideKeys.value = new Set();
    form.value.submissionVersion = detail.value.currentDraft?.version || 1;
  } catch (reason) { error.value = goErrorMessage(reason, '暂时无法读取审核内容。'); }
  finally { loading.value = false; }
}
function addItem() {
  const slide = draftSlides.value.find((candidate) => !form.value.items.some((item) => item.slideNumber === candidate.number));
  form.value.items.push({ key: `${Date.now()}-${form.value.items.length}`, slideNumber: slide?.number || 0, slideTitle: slide?.title || '整体建议', severity: 'INFO', comment: '' });
}
function removeItem(index: number) { form.value.items.splice(index, 1); }
function toggleSlide(key: string) {
  const next = new Set(expandedSlideKeys.value);
  if (next.has(key)) next.delete(key);
  else next.add(key);
  expandedSlideKeys.value = next;
}
function syncSlideTitle(item: ReviewItem) {
  const slide = draftSlides.value.find((candidate) => candidate.number === item.slideNumber);
  if (slide) item.slideTitle = slide.title;
  else if (item.slideNumber === 0) item.slideTitle = '整体建议';
}
async function submitFeedback() {
  const missionId = Number(route.params.missionId);
  if (!form.value.summary.trim()) { error.value = '请先填写总体意见。'; return; }
  if (form.value.items.some((item) => !item.slideTitle.trim() || !item.comment.trim())) { error.value = '逐页建议需要填写页面标题和具体意见。'; return; }
  submitting.value = true;
  error.value = '';
  try {
    await createGoMissionFeedback(missionId, { submissionVersion: currentDraft.value?.version || 1, rating: form.value.rating, summary: form.value.summary.trim(), items: form.value.items.map(({ key: _key, ...item }) => item) });
    await load();
    form.value.summary = '';
    form.value.items = [];
  } catch (reason) { error.value = goErrorMessage(reason, '反馈提交失败，未伪造成功状态。'); }
  finally { submitting.value = false; }
}
function statusLabel(status: string) { return ({ SUBMITTED: '待审核', COMPLETED: '待审核', FEEDBACK: '已反馈' } as Record<string, string>)[status] || status; }
function formatDate(value: string) { const date = new Date(value); return Number.isNaN(date.valueOf()) ? '—' : date.toLocaleString('zh-CN', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' }); }
onMounted(load);
</script>
