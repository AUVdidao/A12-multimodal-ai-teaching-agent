<template>
  <LessonForgeFrame workspace>
    <div v-if="mission" class="lf-workspace lf-workspace--resizable" :style="{ '--lf-mission-index-width': `${missionRailWidth}px`, '--lf-mission-board-width': `${missionBoardWidth}px` }" data-test="mission-workspace">
      <LessonForgeMissionRail :mission="mission" @back="router.push({ name: 'lessonforge-missions' })" @focus="focusConversation" @plan="planOpen = true" />
      <button class="lf-resize-handle" type="button" aria-label="调整左侧栏宽度" @pointerdown="startResize('rail', $event)" />
      <main class="lf-conversation" data-test="workspace-center">
        <header class="lf-conversation-header" data-test="mission-header"><div class="lf-conversation-header__inner"><div><div class="lf-eyebrow">MISSION</div><h1 data-test="mission-title">{{ mission.title }}</h1></div><div class="lf-conversation-header__meta"><span class="lf-status-chip" data-test="mission-status"><i class="lf-status-dot" :class="`lf-status-dot--${mission.status.toLowerCase()}`" />{{ lessonForgeStatusLabels[mission.status] }}</span></div></div></header>
        <div class="lf-conversation-progress" data-test="mission-progress" aria-label="Mission progress"><div class="lf-conversation-progress__inner"><span class="lf-conversation-progress__state"><i class="lf-status-dot" :class="`lf-status-dot--${mission.status.toLowerCase()}`" />{{ mission.status === 'ASSIGNED' ? '等待接受' : '进行中' }}</span><span class="lf-conversation-progress__track" aria-hidden="true"><span :style="{ width: `${phaseProgress}%` }" /></span><span class="lf-conversation-progress__phase">{{ phaseLabel }}</span></div></div>
        <template v-if="isLeader">
          <section class="lf-leader-submissions" data-test="leader-submission-review">
            <div class="lf-card-kicker">FORMAL SUBMISSIONS</div>
            <h2>教师正式提交</h2>
            <p class="lf-inline-note">负责人只能查看正式 Submission 摘要并审核；教师私聊、Context Files 与 Model Connection 不在此页面提供。</p>
            <article v-for="submission in mission.submissions" :key="submission.id" class="lf-leader-submission" :data-test="`leader-submission-${submission.id}`">
              <div><strong>{{ submission.fileName }}</strong><small>{{ submission.submittedAt }} · {{ submission.status === 'REVIEWED' ? `已审核 ${submission.rating} 星` : '待审核' }}</small></div>
              <template v-if="submission.status === 'REVIEWED'"><p>{{ submission.reviewNote || '审核通过' }}</p></template>
              <template v-else>
                <select v-model.number="reviewDraft(submission.id).rating" :aria-label="`Submission ${submission.fileName} 评分`"><option :value="5">5 星</option><option :value="4">4 星</option><option :value="3">3 星</option><option :value="2">2 星</option><option :value="1">1 星</option></select>
                <textarea v-model="reviewDraft(submission.id).note" aria-label="审核理由" placeholder="1–2 星必须填写详细理由；3–5 星可选填。" />
                <button class="lf-primary-button" @click="reviewSubmission(submission.id)">提交审核</button>
              </template>
            </article>
            <p v-if="!mission.submissions.length" class="lf-table-empty">暂无正式 Submission。</p>
            <p v-if="reviewError" class="lf-submission-error" data-test="leader-review-error">{{ reviewError }}</p>
          </section>
        </template>
        <template v-else>
          <div v-if="mission.status === 'ASSIGNED'" class="lf-inline-note">该 Mission 尚未接受；请先接受或拒绝（拒绝必须填写理由）。 <button class="lf-secondary-button" @click="acceptMission">接受 Mission</button><button class="lf-secondary-button" @click="rejectMission">拒绝</button></div>
          <div class="lf-conversation-scroll" ref="conversationScroll" data-test="conversation-scroll">
            <article v-for="message in mission.messages" :key="message.id" class="lf-message" :class="`lf-message--${message.role}`"><div class="lf-message-body"><div class="lf-message-meta">{{ message.role === 'teacher' ? 'You' : 'LessonForge' }}<time>{{ message.time }}</time></div><p>{{ message.body }}</p></div></article>
            <section v-if="mission.currentPhase === 'QUESTION' && mission.question" class="lf-question-card" data-test="question-card"><div class="lf-question-progress"><span>QUESTION {{ mission.question.current }} OF {{ mission.question.total }}</span></div><h2>{{ mission.question.prompt }}</h2><div class="lf-question-options"><button v-for="option in mission.question.options" :key="option" type="button">{{ option }}<span>›</span></button></div></section>
            <section v-else-if="(mission.currentPhase === 'DRAFT' || mission.currentPhase === 'LOCKED') && mission.plan" class="lf-plan-card" data-test="plan-card"><div class="lf-card-header"><div><div class="lf-card-kicker">{{ mission.currentPhase === 'LOCKED' ? 'LOCKED PLAN' : 'COURSEWARE PLAN' }}</div><h2>courseware-plan.md <span>{{ mission.plan.version }} · {{ mission.currentPhase === 'LOCKED' ? 'Locked' : 'Draft' }}</span></h2></div></div><p>{{ mission.plan.summary }}</p><div class="lf-plan-sections"><span v-for="section in mission.plan.sections" :key="section">{{ section }}</span></div></section>
            <section v-else-if="mission.currentPhase === 'GENERATING'" class="lf-run-card" data-test="generation-card"><span class="lf-status-dot lf-status-dot--in_progress" />正在生成 PPT<span class="lf-dev-only">FRONTEND_ONLY</span></section>
            <template v-else-if="mission.currentPhase === 'SUCCEEDED' && mission.output"><section class="lf-output-card" data-test="output-card"><div class="lf-output-details"><div class="lf-card-kicker">PPT OUTPUT</div><h2>{{ mission.output.name }}</h2><p>{{ mission.output.pages }} Slides · {{ mission.output.sizeLabel }} · {{ mission.output.version }}</p><small class="lf-boundary-note lf-dev-only">FRONTEND_ONLY / DEVELOPMENT_FIXTURE · 非真实 PPTX / Office 验收</small></div></section><LessonForgePptPreview :current-phase="mission.currentPhase" :total="mission.output.pages" :title="mission.title" /></template>
            <section v-else-if="mission.currentPhase === 'CONFLICT'" class="lf-phase-notice lf-phase-notice--conflict" data-test="phase-conflict"><strong>阶段内容暂不可用</strong><p>{{ mission.phaseMessage }}</p></section>
            <section v-else-if="mission.currentPhase === 'UNAVAILABLE'" class="lf-phase-notice" data-test="phase-unavailable"><strong>等待下一步</strong><p>继续补充要求，或选择模型连接，系统会从这里继续。</p><span class="lf-dev-only">{{ mission.phaseMessage }}</span></section>
            <section v-if="mission.surfaceGate.feedback" class="lf-feedback-card" data-test="review-card"><button class="lf-feedback-summary" data-test="review-toggle" type="button" :aria-expanded="feedbackOpen" @click="feedbackOpen = !feedbackOpen"><span>✓ 已收到审核反馈 · {{ mission.review?.rating || '—' }} 星 · {{ mission.review?.reviewerName || '审核人' }}</span><span aria-hidden="true">{{ feedbackOpen ? '⌃' : '›' }}</span></button><div v-if="feedbackOpen && mission.review" class="lf-review-details" data-test="review-details"><strong>审核反馈</strong><div><span>意见</span><p>{{ mission.review.comment }}</p></div><div><span>提交文件</span><p>{{ mission.review.submittedFileName }}</p></div><div><span>审核时间</span><p>{{ mission.review.reviewedAt }}</p></div></div></section>
            <div class="lf-inline-note lf-dev-only">真实消息、Context File 与 Submission 已接入服务器；Planning、Generation、Provider、Renderer、Office 保持未接通。</div>
            <LessonForgeSubmissionCard v-if="mission.surfaceGate.submission" :mission="mission" @submit-file="submitFile" />
          </div>
          <LessonForgeComposer v-model="draft" :files="composerFiles" :connections="connections" :selected-connection="selectedConnection" :disabled="mission.status === 'ASSIGNED'" @send="sendMessage" @files-selected="composerFiles = $event" @select-connection="selectConnection" @manage-connections="showConnectionNotice = true" />
          <div v-if="showConnectionNotice" class="lf-inline-note lf-inline-note--composer">连接管理复用真实 Model Connection API；Provider 验证仍待真实环境。</div>
        </template>
      </main>
      <button class="lf-resize-handle" type="button" aria-label="调整右侧看板宽度" @pointerdown="startResize('board', $event)" />
      <LessonForgeBoard :mission="mission" :plan-open="planOpen" @toggle-plan="planOpen = !planOpen" />
    </div><div v-else class="lf-loading">Loading mission…</div>
  </LessonForgeFrame>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import LessonForgeFrame from '@/components/lessonForge/LessonForgeFrame.vue';
import LessonForgeMissionRail from '@/components/lessonForge/LessonForgeMissionRail.vue';
import LessonForgeBoard from '@/components/lessonForge/LessonForgeBoard.vue';
import LessonForgeComposer from '@/components/lessonForge/LessonForgeComposer.vue';
import LessonForgeSubmissionCard from '@/components/lessonForge/LessonForgeSubmissionCard.vue';
import LessonForgePptPreview from '@/components/lessonForge/LessonForgePptPreview.vue';
import { useLessonForgeStore } from '@/stores/lessonForge';
import { useAuthStore } from '@/stores/auth';
import { lessonForgeStatusLabels } from '@/types/lessonForge';
import { getModelConnections, type ModelConnection } from '@/api/aiCredentials';
import { reviewLessonForgeSubmission } from '@/api/lessonForge';
const route = useRoute(); const router = useRouter(); const store = useLessonForgeStore();
const auth = useAuthStore(); const isLeader = computed(() => auth.activeRole === 'LEADER');
const draft = ref(''); const composerFiles = ref<{ name: string; file?: File }[]>([]); const selectedConnection = ref<ModelConnection | null>(null); const connections = ref<ModelConnection[]>([]); const planOpen = ref(false); const feedbackOpen = ref(false); const showConnectionNotice = ref(false); const reviewError = ref('');
const reviewDrafts = ref<Record<string, { rating: number; note: string }>>({});
const mission = computed(() => store.missions.find(item => item.id === String(route.params.missionId)));
const missionRailWidth = ref(172); const missionBoardWidth = ref(292); const resizeTarget = ref<'rail' | 'board' | null>(null); let resizeStartX = 0; let resizeStartWidth = 0;
const phaseLabel = computed(() => ({ QUESTION: '补充生成要求', DRAFT: '规划草稿', LOCKED: '已锁定规划', GENERATING: '正在生成', SUCCEEDED: '课件已完成', CONFLICT: '需要检查', UNAVAILABLE: '等待下一步' }[mission.value?.currentPhase || 'UNAVAILABLE'] || '等待下一步'));
const phaseProgress = computed(() => ({ QUESTION: 25, DRAFT: 50, LOCKED: 68, GENERATING: 84, SUCCEEDED: 100, CONFLICT: 25, UNAVAILABLE: 25 }[mission.value?.currentPhase || 'UNAVAILABLE'] || 25));
onMounted(async () => { await store.loadMission(String(route.params.missionId)); if (!isLeader.value) await loadConnections(); });
watch(() => route.params.missionId, async id => { feedbackOpen.value = false; if (id) { await store.loadMission(String(id)); if (!isLeader.value) await loadConnections(); } });
async function loadConnections() { const response = await getModelConnections(); if (response.code === 0) { connections.value = response.data || []; const selectedId = mission.value?.selectedConnectionId; selectedConnection.value = selectedId == null ? null : connections.value.find(connection => connection.id === selectedId) || null; } }
function focusConversation() { document.querySelector('.lf-conversation-scroll')?.scrollTo({ top: 999999, behavior: 'smooth' }); }
function startResize(target: 'rail' | 'board', event: PointerEvent) { if (event.button !== 0) return; resizeTarget.value = target; resizeStartX = event.clientX; resizeStartWidth = target === 'rail' ? missionRailWidth.value : missionBoardWidth.value; document.body.classList.add('lf-is-resizing'); window.addEventListener('pointermove', handleResize); window.addEventListener('pointerup', stopResize, { once: true }); }
function handleResize(event: PointerEvent) { if (resizeTarget.value === 'rail') missionRailWidth.value = Math.min(260, Math.max(140, resizeStartWidth + event.clientX - resizeStartX)); if (resizeTarget.value === 'board') missionBoardWidth.value = Math.min(460, Math.max(240, resizeStartWidth - (event.clientX - resizeStartX))); }
function stopResize() { resizeTarget.value = null; document.body.classList.remove('lf-is-resizing'); window.removeEventListener('pointermove', handleResize); window.removeEventListener('pointerup', stopResize); }
onBeforeUnmount(stopResize);
async function sendMessage(payload: { text: string; files: { name: string; file?: File }[] }) { if (!mission.value || mission.value.status === 'ASSIGNED') return; await store.sendMessage(mission.value.id, payload.text, payload.files.flatMap(item => item.file ? [item.file] : [])); draft.value = ''; composerFiles.value = []; }
async function selectConnection(connection: ModelConnection | { id: number; name: string; modelId?: string; verificationStatus?: ModelConnection['verificationStatus'] }) { if (!('protocol' in connection)) return; selectedConnection.value = connection; if (mission.value) await store.selectConnection(mission.value.id, connection.id); }
async function acceptMission() { if (mission.value) await store.accept(mission.value.id); }
async function rejectMission() { const reason = window.prompt('请输入拒绝理由'); if (mission.value && reason?.trim()) await store.reject(mission.value.id, reason.trim()); }
async function submitFile(file: File) { if (mission.value) await store.submitFinalDeck(mission.value.id, file); }
function reviewDraft(id: string) { return reviewDrafts.value[id] || (reviewDrafts.value[id] = { rating: 5, note: '' }); }
async function reviewSubmission(submissionId: string) { if (!mission.value) return; const draft = reviewDraft(submissionId); reviewError.value = ''; if (draft.rating <= 2 && !draft.note.trim()) { reviewError.value = '1–2 星必须填写详细理由。'; return; } try { await reviewLessonForgeSubmission(Number(mission.value.id), Number(submissionId), draft.rating, draft.note.trim() || undefined); await store.loadMission(mission.value.id); } catch (error) { reviewError.value = error instanceof Error ? error.message : '审核失败'; } }
</script>
