<template>
  <LessonForgeFrame workspace>
    <div
      v-if="detail"
      class="lf-workspace lf-workspace--resizable go-mission-workspace"
      :style="{ '--lf-mission-index-width': `${missionRailWidth}px`, '--lf-mission-board-width': `${missionBoardWidth}px` }"
      data-test="go-mission-workspace"
    >
      <aside class="lf-mission-rail go-mission-rail">
        <button class="lf-back-link" type="button" @click="router.push({ name: 'lessonforge-missions' })">‹ <span>Back to Missions</span></button>
        <section class="lf-rail-section">
          <div class="lf-rail-section__heading">Sources <span>{{ detail.files.length }}</span></div>
          <div v-for="file in detail.files" :key="file.id" class="lf-rail-file">
            <span class="lf-rail-file__name" :title="file.file.originalName">{{ file.file.originalName }}</span>
            <small>{{ fileStatusLabel(file.parseStatus) }}</small>
          </div>
          <span v-if="!detail.files.length" class="lf-rail-empty">暂无来源文件</span>
        </section>
        <section class="lf-rail-section">
          <div class="lf-rail-section__heading">Courseware <span>{{ renderedDraft ? 1 : 0 }}</span></div>
          <span class="lf-rail-empty">{{ renderedDraft ? `Plan v${renderedDraft.version}` : '暂无计划草稿' }}</span>
        </section>
        <section class="lf-rail-section">
          <div class="lf-rail-section__heading">Outputs <span>{{ detail.artifacts.length }}</span></div>
          <span class="lf-rail-empty">{{ detail.artifacts.length ? `${detail.artifacts.length} 个课件版本` : '暂无输出' }}</span>
        </section>
        <section class="lf-rail-section">
          <div class="lf-rail-section__heading">Submission</div>
          <span class="lf-rail-empty">等待提交</span>
        </section>
        <div class="lf-nav-account">
          <button class="lf-nav-user" type="button" :aria-expanded="accountMenuOpen" aria-haspopup="menu" @click="accountMenuOpen = !accountMenuOpen"><span class="lf-avatar lf-avatar--small">{{ accountInitial }}</span><span>{{ accountName }}</span><span class="lf-nav-user__more">···</span></button>
          <div v-if="accountMenuOpen" class="lf-account-menu lf-account-menu--nav" role="menu">
            <div class="lf-account-menu__identity"><strong>{{ accountName }}</strong><span>{{ roleLabel }}</span></div>
            <button type="button" role="menuitem" @click="handleLogout">退出登录</button>
          </div>
        </div>
      </aside>

      <button class="lf-resize-handle" type="button" aria-label="调整左侧栏宽度" data-test="resize-left-rail" @pointerdown="startResize('rail', $event)" />

      <main class="lf-conversation go-conversation" data-test="go-conversation">
        <header class="lf-conversation-header">
          <div class="lf-conversation-header__inner">
            <div><div class="lf-eyebrow">MISSION</div><h1>{{ detail.mission.title }}</h1></div>
            <div class="lf-conversation-header__meta"><span class="lf-status-chip"><i class="lf-status-dot" :class="`lf-status-dot--${statusClass(workspaceStatus)}`" />{{ statusLabel(workspaceStatus) }}</span></div>
          </div>
        </header>
        <div class="lf-conversation-progress">
          <div class="lf-conversation-progress__inner">
            <span class="lf-conversation-progress__state"><i class="lf-status-dot" :class="`lf-status-dot--${statusClass(workspaceStatus)}`" />{{ statusLabel(workspaceStatus) }}</span>
            <span class="lf-conversation-progress__track"><span :style="{ width: `${progress}%` }" /></span>
            <span class="lf-conversation-progress__phase">{{ phaseLabel }}</span>
          </div>
        </div>

        <div ref="conversationScroll" class="lf-conversation-scroll">
          <template v-for="message in renderedMessages" :key="message.id">
            <article v-if="!isStructuredMessage(message)" class="lf-message" :class="message.role === 'USER' ? 'lf-message--teacher' : 'lf-message--assistant'">
              <div class="lf-message-body">
                <div class="lf-message-meta">{{ message.role === 'USER' ? 'You' : 'LessonForge' }}<time>{{ formatDateTime(message.createdAt) }}</time></div>
                <p>{{ message.content }}</p>
              </div>
            </article>
          </template>

          <section v-if="showInitialConversation" class="go-initial-conversation" data-test="go-initial-conversation">
            <div class="go-initial-conversation__mark" aria-hidden="true">✦</div>
            <div class="lf-card-kicker">NEW COURSEWARE</div>
            <h2>从一个教学目标开始</h2>
            <p>告诉我这节课要教什么、面向哪些学生，以及你希望重点讲清楚的内容。</p>
            <div class="go-initial-conversation__prompts"><button v-for="prompt in starterPrompts" :key="prompt" type="button" @click="useStarterPrompt(prompt)">{{ prompt }}</button></div>
          </section>
          <section v-if="!renderedMessages.length && !showInitialConversation" class="go-empty-message">还没有消息。</section>

          <section v-if="currentRun" class="go-agent-run-card" :class="`go-agent-run-card--${statusClass(currentRun.status)}`" data-test="go-agent-run-status">
            <div class="go-agent-run-card__header"><div><div class="lf-card-kicker">AGENT RUN</div><h2>{{ statusLabel(currentRun.status) }}</h2></div><span class="lf-status-chip"><i class="lf-status-dot" :class="`lf-status-dot--${statusClass(currentRun.status)}`" />{{ statusLabel(currentRun.status) }}</span></div>
            <p>{{ runDescription }}</p>
            <button v-if="currentRun.status === 'WAITING_INPUTS'" class="lf-secondary-button" type="button" @click="connectionDrawerOpen = true">选择 Model Connection</button>
            <small v-if="currentRun.status === 'WAITING_INPUTS'">选择并验证连接后，服务端会继续处理当前 Mission；不会丢失已经保存的消息。</small>
            <small v-else-if="currentRun.status === 'FAILED' && currentRun.errorMessage">{{ currentRun.errorMessage }}</small>
          </section>

          <GoQuestionCard v-if="activeQuestion" :key="activeQuestion.id" :question="activeQuestion" :question-index="activeQuestionIndex + 1" :question-total="renderedQuestions.length" :mission-id="missionId" :user-id="auth.user?.id ?? undefined" @submitted="handleQuestionSubmitted" />
          <GoQuestionHistoryCard v-if="historyQuestions.length" :questions="historyQuestions" />
          <GoPlanDraftCard v-if="renderedDraft" :draft="renderedDraft" :locked="Boolean(renderedLockedSpecification)" :facts="planFacts" :approving="approving" @approve="approveDraft" />
          <GoFeedbackCard v-if="renderedFeedback.length" :feedback="renderedFeedback" @continue-editing="focusComposer" />

          <section v-if="renderedLockedSpecification" class="go-locked-spec lf-plan-card" data-test="go-locked-specification">
            <header class="go-plan-preview__header"><div><div class="lf-card-kicker">LOCKED SPECIFICATION · v{{ renderedLockedSpecification.version }}</div><h2>已锁定课件规格</h2></div><span class="go-locked-spec__status">LOCKED</span></header>
            <dl class="go-locked-spec__facts"><div><dt>来源草稿</dt><dd>Courseware Plan v{{ renderedLockedSpecification.version }}</dd></div><div><dt>内容校验</dt><dd>{{ shortHash(renderedLockedSpecification.contentHash) }}</dd></div><div><dt>模板绑定</dt><dd>{{ templateBindingLabel(renderedLockedSpecification.templateBinding) }}</dd></div><div><dt>锁定时间</dt><dd>{{ formatDateTime(renderedLockedSpecification.createdAt) }}</dd></div></dl>
            <p>这是批准时生成的不可变版本，后续修改会创建新的方案版本，不会覆盖本规格。</p>
          </section>
          <section v-if="renderedLockedSpecification" class="go-generation-card go-generation-card--waiting" data-test="go-generation-waiting">
            <header class="go-generation-card__header"><div><div class="lf-card-kicker">COURSEWARE GENERATION</div><h2>{{ generationIsActive ? '正在生成' : currentGenerationJob?.status === 'FAILED' || currentGenerationJob?.status === 'CANCELLED' || currentGenerationJob?.status === 'SUCCEEDED' ? '可以再次生成' : '等待生成' }}</h2></div><span class="go-generation-card__status">{{ currentGenerationJob ? statusLabel(currentGenerationJob.status) : 'READY CHECK' }}</span></header>
            <p v-if="generationIsActive">生成任务已提交，页面会持续读取服务端任务状态；完成后保留并展示课件产物。</p>
            <p v-else-if="currentGenerationJob?.status === 'FAILED'">上次生成失败：{{ generationFeedbackLabel(currentGenerationJob) }}。修正输入或服务后，可以针对同一锁定规格重试。</p>
            <p v-else-if="currentGenerationJob?.status === 'CANCELLED'">上次生成已取消，可以针对同一锁定规格重新发起。</p>
            <p v-else-if="currentGenerationJob?.status === 'SUCCEEDED'">上次生成已完成。历史 Artifact 保留在下方，满足条件后仍可再次生成同一锁定规格。</p>
            <p v-else>方案已批准。满足模板和材料就绪条件后，教师可单独启动 PPT 生成。</p>
            <div v-if="generationBlockers.length" class="go-generation-card__requirements" data-test="generation-prerequisites"><span>开始前还需要：</span><ul><li v-for="blocker in generationBlockers" :key="blocker">{{ blocker }}</li></ul></div>
            <button v-if="generationButtonVisible" class="lf-primary-button" type="button" data-test="request-generation" :disabled="!generationCanRequest" @click="requestGeneration">{{ generationRequesting ? '提交生成请求…' : generationIsActive ? '生成中…' : currentGenerationJob ? '再次生成 PPT' : '生成 PPT' }}</button>
            <div v-else class="go-generation-card__track" aria-hidden="true"><span /></div>
          </section>
          <section v-for="job in detail.generationJobs" :key="job.id" class="lf-run-card" :data-test="`generation-job-${job.id}`"><span class="lf-status-dot" :class="`lf-status-dot--${statusClass(job.status)}`" />课件生成 · {{ statusLabel(job.status) }} · 规格 v{{ job.specificationVersion }}<span v-if="job.totalSlides">{{ job.currentSlide }}/{{ job.totalSlides }}</span><small v-if="job.status === 'FAILED'">{{ generationFeedbackLabel(job) }}</small></section>
          <section v-for="artifact in detail.artifacts" :key="artifact.id" class="go-artifact-card"><div class="go-artifact-card__main"><span class="go-artifact-card__icon" aria-hidden="true">▣</span><div><div class="lf-card-kicker">PPT OUTPUT · v{{ artifact.version }}</div><h2>{{ artifact.file.originalName }}</h2><p>{{ artifact.file.size }} bytes · {{ artifact.status || 'ready' }}</p></div></div><div class="go-artifact-card__actions"><a class="lf-secondary-button" :href="artifactDownloadUrl(artifact.id)" target="_blank" rel="noreferrer">预览</a><a class="lf-primary-button" :href="artifactDownloadUrl(artifact.id)" target="_blank" rel="noreferrer">下载</a></div></section>
        </div>

        <LessonForgeComposer v-model="draft" :files="composerFiles" :connections="connections" :selected-connection="selectedConnection" :working="sending || uploading" placeholder="继续补充课件需求…" @send="sendMessage" @files-selected="handleFilesSelected" @select-connection="handleConnectionSelection" @manage-connections="connectionDrawerOpen = true" />
        <p v-if="error" class="go-inline-error" role="alert">{{ error }}</p>
      </main>

      <button class="lf-resize-handle" type="button" aria-label="调整右侧看板宽度" data-test="resize-right-board" @pointerdown="startResize('board', $event)" />
      <aside class="lf-board go-board">
        <section class="lf-board-section"><div class="lf-board-label">MISSION</div><dl class="lf-mission-facts"><div><dt>状态</dt><dd>{{ statusLabel(workspaceStatus) }}</dd></div><div><dt>AgentRun</dt><dd>{{ currentRun ? statusLabel(currentRun.status) : '尚未运行' }}</dd></div><div><dt>Model Connection</dt><dd>{{ selectedConnection?.name || (detail.mission.selectedModelConnectionId ? `Connection #${detail.mission.selectedModelConnectionId}` : '未选择') }}</dd></div><div><dt>更新时间</dt><dd>{{ formatDateTime(detail.mission.updatedAt) }}</dd></div></dl></section>
        <section class="lf-board-section"><div class="lf-board-label">COURSEWARE <span>{{ renderedLockedSpecification ? `Locked v${renderedLockedSpecification.version}` : renderedDraft ? `Draft v${renderedDraft.version}` : '—' }}</span></div><p v-if="renderedLockedSpecification">方案已批准并锁定，后续生成将读取此版本。</p><p v-else-if="renderedDraft">当前计划草稿已由服务端保存，等待教师确认。</p><p v-else class="lf-board-empty">尚无服务端计划草稿。</p></section>
        <section class="lf-board-section"><div class="lf-board-label">ACTIVITY <span>{{ renderedEvents.length }}</span></div><div v-if="renderedEvents.length" class="lf-activity-list"><div v-for="event in renderedEvents" :key="event.id" class="lf-activity"><span class="lf-activity__mark">•</span><span><strong>{{ event.summary }}</strong><small>{{ event.eventType }}</small></span><time>{{ formatDateTime(event.createdAt) }}</time></div></div><p v-else class="lf-board-empty">等待服务端事件。</p></section>
      </aside>
      <ModelConnectionDrawer v-model="connectionDrawerOpen" :selected-connection="selectedConnection" :selected-connection-id="selectedConnection?.id ?? null" @update:connection="handleConnectionSelection" @connections-loaded="handleConnectionsLoaded" />
    </div>
    <div v-else-if="error" class="lf-loading go-load-error" role="alert">{{ error }} <button class="lf-secondary-button" type="button" @click="() => load(true)">重试</button></div>
    <div v-else class="lf-loading" role="status">正在加载 Mission…</div>
  </LessonForgeFrame>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import LessonForgeFrame from '@/components/lessonForge/LessonForgeFrame.vue';
import LessonForgeComposer from '@/components/lessonForge/LessonForgeComposer.vue';
import ModelConnectionDrawer from '@/components/assistant/ModelConnectionDrawer.vue';
import GoQuestionCard from '@/components/GoQuestionCard.vue';
import GoQuestionHistoryCard from '@/components/GoQuestionHistoryCard.vue';
import GoPlanDraftCard from '@/components/GoPlanDraftCard.vue';
import GoFeedbackCard from '@/components/GoFeedbackCard.vue';
import { approveGoPlanningDraft, createGoGenerationJob, getGoMission, goApiBaseUrl, goErrorMessage, goMissionEventsUrl, listGoMissionAgentRuns, listGoMissionQuestions, listGoModelConnections, selectGoMissionConnection, sendGoMissionMessage, uploadGoMissionFile, type GoActivityEvent, type GoAgentRun, type GoGenerationJob, type GoLockedSpecification, type GoMessage, type GoMissionDetail, type GoPlanningDraft, type GoQuestion } from '@/api/go';
import { type ModelConnection } from '@/api/aiCredentials';
import { composerFileIdentity, type ComposerFile } from '@/utils/lessonForgeComposer';
import { useAuthStore } from '@/stores/auth';
import { useResizableWorkspace } from '@/composables/useResizableWorkspace';

const route = useRoute();
const router = useRouter();
const auth = useAuthStore();
const detail = ref<GoMissionDetail | null>(null);
const questions = ref<GoQuestion[]>([]);
const agentRuns = ref<GoAgentRun[]>([]);
const connections = ref<ModelConnection[]>([]);
const selectedConnection = ref<ModelConnection | null>(null);
const events = ref<GoActivityEvent[]>([]);
const draft = ref('');
const composerFiles = ref<ComposerFile[]>([]);
const connectionDrawerOpen = ref(false);
const sending = ref(false);
const uploading = ref(false);
const approving = ref(false);
const generationRequesting = ref(false);
const error = ref('');
const viewActive = ref(false);
let source: EventSource | null = null;
let reloadTimer: number | undefined;
let eventReconnectTimer: number | undefined;
let generationPollTimer: number | undefined;
let generationPollJobId = '';
let lastEventId = 0;
let requestVersion = 0;

const currentUserId = computed(() => auth.user?.id ?? null);
const { railWidth: missionRailWidth, boardWidth: missionBoardWidth, startResize, stopResize, restoreWorkspaceLayout } = useResizableWorkspace(currentUserId);
const accountMenuOpen = ref(false);
const accountName = computed(() => {
  const displayName = auth.user?.displayName?.trim();
  if (displayName && !/(?:demo|演示)/i.test(displayName)) return displayName;
  const username = auth.user?.username?.trim();
  return username?.split('@')[0] || 'Teacher';
});
const accountInitial = computed(() => accountName.value.slice(0, 1).toUpperCase());
const roleLabel = computed(() => auth.activeRole === 'RESEARCHER' ? '教研员' : auth.activeRole === 'LEADER' ? '负责人' : '教师');
const missionId = computed(() => Number(route.params.missionId));
const currentRun = computed(() => agentRuns.value[0] || null);
const renderedDraft = computed<GoPlanningDraft | null>(() => detail.value?.currentDraft || null);
const renderedLockedSpecification = computed<GoLockedSpecification | null>(() => detail.value?.lockedSpecification || null);
const renderedMessages = computed<GoMessage[]>(() => detail.value?.messages || []);
const renderedFeedback = computed(() => detail.value?.feedback || []);
const renderedQuestions = computed<GoQuestion[]>(() => questions.value);
const renderedEvents = computed<GoActivityEvent[]>(() => events.value);
const currentGenerationJob = computed(() => {
  const jobs = detail.value?.generationJobs || [];
  return jobs[0] || null;
});
const generationIsActive = computed(() => ['QUEUED', 'RUNNING', 'VERIFYING'].includes(currentGenerationJob.value?.status || ''));
const generationBlockers = computed(() => {
  const blockers: string[] = [];
  const binding = renderedLockedSpecification.value?.templateBinding;
  if (!bindingIsUsable(binding)) blockers.push('锁定规格的模板绑定无效，请重新准备模板并锁定新规格。');
  const files = detail.value?.files || [];
  if (!files.length || files.some((file) => file.parseStatus !== 'READY')) blockers.push('所有已绑定材料都必须先完成解析。');
  if (!files.some((file) => file.role === 'TEMPLATE' && file.parseStatus === 'READY')) blockers.push('需要一份已解析完成的 PPTX 模板。');
  return blockers;
});
const generationButtonVisible = computed(() => Boolean(renderedLockedSpecification.value && (!currentGenerationJob.value || generationIsActive.value || ['FAILED', 'CANCELLED', 'SUCCEEDED'].includes(currentGenerationJob.value.status))));
const generationCanRequest = computed(() => Boolean(renderedLockedSpecification.value && !generationIsActive.value && (!currentGenerationJob.value || ['FAILED', 'CANCELLED', 'SUCCEEDED'].includes(currentGenerationJob.value.status)) && !generationBlockers.value.length && !generationRequesting.value));
const planFacts = computed(() => {
  const raw = renderedDraft.value?.structuredPlan;
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return [];
  const record = raw as Record<string, unknown>;
  const facts: string[] = [];
  for (const [key, label] of [['subject', '学科'], ['slideCount', '页数'], ['durationMinutes', '时长']] as const) {
    const value = record[key];
    if (typeof value === 'string' || typeof value === 'number') facts.push(`${label} ${value}${key === 'slideCount' ? ' 页' : key === 'durationMinutes' ? ' 分钟' : ''}`);
  }
  return facts;
});
const progress = computed(() => {
  const status = detail.value?.mission.status;
  if (generationIsActive.value) return currentGenerationJob.value?.status === 'QUEUED' ? 78 : currentGenerationJob.value?.status === 'VERIFYING' ? 92 : 85;
  if (currentGenerationJob.value?.status === 'FAILED' || currentGenerationJob.value?.status === 'CANCELLED') return detail.value?.artifacts.length ? 100 : 0;
  if (detail.value?.artifacts.length) return 100;
  if (detail.value?.generationJobs.length) return 78;
  if (currentRun.value?.status === 'FAILED' || currentRun.value?.status === 'CANCELLED') return 0;
  if (currentRun.value?.status === 'RUNNING') return 78;
  if (currentRun.value?.status === 'QUEUED') return 45;
  if (renderedLockedSpecification.value) return 72;
  if (renderedDraft.value) return 60;
  if (activeQuestion.value) return 35;
  if (renderedMessages.value.length) return 25;
  return status === 'IN_PROGRESS' ? 20 : 10;
});
const phaseLabel = computed(() => generationIsActive.value ? statusLabel(currentGenerationJob.value?.status || 'RUNNING') : currentGenerationJob.value?.status === 'FAILED' ? '需要处理' : currentGenerationJob.value?.status === 'CANCELLED' ? '已取消' : detail.value?.artifacts.length ? '已完成' : detail.value?.generationJobs.length ? '生成状态' : renderedLockedSpecification.value ? '等待生成' : currentRun.value?.status === 'FAILED' ? '需要处理' : currentRun.value?.status === 'CANCELLED' ? '已取消' : currentRun.value?.status === 'WAITING_INPUTS' ? '等待补充' : currentRun.value?.status === 'QUEUED' ? '排队中' : currentRun.value?.status === 'RUNNING' ? '运行中' : detail.value?.mission.status === 'FEEDBACK' ? '反馈待处理' : activeQuestion.value ? '需求确认' : renderedDraft.value ? '计划草稿' : renderedMessages.value.length ? '持续对话' : '等待首条需求');
const workspaceStatus = computed(() => {
  if (currentGenerationJob.value) {
    const status = currentGenerationJob.value.status;
    if (['QUEUED', 'RUNNING', 'VERIFYING', 'FAILED', 'CANCELLED'].includes(status)) return status;
    if (status === 'SUCCEEDED' && detail.value?.artifacts.length) return 'COMPLETED';
    if (status === 'SUCCEEDED') return status;
  }
  if (detail.value?.artifacts.length) return 'COMPLETED';
  if (renderedLockedSpecification.value) return 'GENERATION_WAITING';
  if (currentRun.value?.status && ['WAITING_INPUTS', 'QUEUED', 'RUNNING', 'FAILED', 'CANCELLED'].includes(currentRun.value.status)) return currentRun.value.status;
  if (detail.value?.mission.status === 'FEEDBACK') return 'FEEDBACK';
  if (renderedDraft.value) return 'PLAN_DRAFT';
  if (activeQuestion.value) return 'QUESTION';
  return detail.value?.mission.status || 'ASSIGNED';
});
const runDescription = computed(() => {
  const status = currentRun.value?.status;
  if (status === 'WAITING_INPUTS') return '首条需求已经保存。当前 AgentRun 正在等待继续运行所需的输入。';
  if (status === 'QUEUED') return '当前 Mission 已进入队列，服务端将从数据库重新构建会话上下文。';
  if (status === 'RUNNING') return 'Agent 正在处理当前 Mission，新的状态会通过服务端事件更新。';
  if (status === 'COMPLETED') return '本次 AgentRun 已完成，结果以当前会话中的服务端消息或计划草稿为准。';
  if (status === 'FAILED') return '本次 AgentRun 未完成。请根据错误提示调整输入后再继续。';
  if (status === 'CANCELLED') return '本次 AgentRun 已取消，已经保存的会话内容仍然保留。';
  return '当前 AgentRun 状态由服务端返回。';
});
const starterPrompts = ['制作一节高中物理课件，重点讲清核心概念', '把这份教材整理成一套课堂课件', '沿用我的模板，增加案例和课后练习'];
const showInitialConversation = computed(() => Boolean(detail.value && !renderedMessages.value.length && !renderedQuestions.value.length && !renderedDraft.value && !detail.value.generationJobs.length && !detail.value.artifacts.length));
const activeQuestion = computed(() => { const latest = renderedQuestions.value[renderedQuestions.value.length - 1]; if (!latest || latest.latestAnswer) return null; if (detail.value?.currentDraft && Date.parse(detail.value.currentDraft.createdAt) >= Date.parse(latest.createdAt)) return null; return latest; });
const activeQuestionIndex = computed(() => activeQuestion.value ? renderedQuestions.value.findIndex((question) => question.id === activeQuestion.value?.id) : -1);
const historyQuestions = computed(() => renderedQuestions.value.filter((question) => question.id !== activeQuestion.value?.id));

function contextIsCurrent(id: number, userId: number | null, version?: number) { return viewActive.value && missionId.value === id && auth.user?.id === userId && (version === undefined || requestVersion === version); }
function isStructuredMessage(message: GoMessage) { return message.messageType === 'QUESTION' || message.messageType === 'PLAN_DRAFT' || message.outputStage === 'QUESTION_MESSAGE' || message.outputStage === 'PLAN_DRAFT'; }
function fileStatusLabel(status: string) { return ({ PENDING: '等待解析', PARSING: '解析中', INDEXING: '建立索引', READY: '已就绪', FAILED: '解析失败' } as Record<string, string>)[status] || status || '等待处理'; }
function shortHash(value: string) { return value ? `${value.slice(0, 12)}…` : '—'; }
function templateBindingLabel(value: unknown) { if (!bindingIsUsable(value)) return '未完成'; const record = value as Record<string, unknown>; return typeof record.templateOriginalName === 'string' ? record.templateOriginalName : '已绑定模板'; }
function bindingIsUsable(value: unknown) { if (!value || typeof value !== 'object' || Array.isArray(value)) return false; const record = value as Record<string, unknown>; return record.bindingKind === 'LESSONFORGE_UPSTREAM_TEMPLATE_BINDING' && typeof record.templateStorageKey === 'string' && record.templateStorageKey.length > 0 && typeof record.templateFileSha256 === 'string' && record.templateFileSha256.length === 64 && Number(record.missionFileId) > 0 && Number(record.fileObjectId) > 0; }
function generationFeedbackLabel(job: GoGenerationJob) { const feedback = job.generationFeedback; if (!feedback || typeof feedback !== 'object' || Array.isArray(feedback)) return '服务端未返回详细原因'; const code = (feedback as Record<string, unknown>).code; return typeof code === 'string' ? code : '服务端未返回详细原因'; }
function useStarterPrompt(prompt: string) { draft.value = prompt; }
function focusComposer() { document.querySelector<HTMLTextAreaElement>('[data-test="composer"] textarea')?.focus(); }
function composerDraftKey(id = missionId.value, userId = auth.user?.id ?? null) { return userId == null || !Number.isInteger(id) || id <= 0 ? null : `lessonforge:mission:${userId}:${id}:composer-draft`; }
function restoreComposerDraft() { const key = composerDraftKey(); if (!key) { draft.value = ''; return; } try { draft.value = window.localStorage.getItem(key) || ''; } catch { draft.value = ''; } }
function persistComposerDraft(value: string) { const key = composerDraftKey(); if (!key) return; try { if (value) window.localStorage.setItem(key, value); else window.localStorage.removeItem(key); } catch { /* Browser storage is optional; server persistence remains authoritative after send. */ } }

async function load(reconnectEvents = true) {
  const id = missionId.value;
  const userId = auth.user?.id ?? null;
  const version = ++requestVersion;
  if (!Number.isInteger(id) || id <= 0) { if (contextIsCurrent(id, userId, version)) error.value = 'Mission ID 无效。'; return; }
  error.value = '';
  try {
    const [nextDetail, nextQuestions, nextAgentRuns] = await Promise.all([getGoMission(id), listGoMissionQuestions(id), listGoMissionAgentRuns(id)]);
    if (!contextIsCurrent(id, userId, version)) return;
    detail.value = nextDetail;
    questions.value = nextQuestions;
    agentRuns.value = nextAgentRuns;
    syncGenerationPolling(nextDetail, id, userId);
    await loadConnections(id, userId, version);
    if (contextIsCurrent(id, userId, version) && (reconnectEvents || !source)) connectEvents(id, userId);
  } catch (reason) { if (contextIsCurrent(id, userId, version)) error.value = goErrorMessage(reason, '暂时无法读取 Mission。'); }
}
async function loadConnections(id: number, userId: number | null, version: number) { const nextConnections = await listGoModelConnections(); if (!contextIsCurrent(id, userId, version)) return; connections.value = nextConnections; const selected = detail.value?.mission.selectedModelConnectionId; selectedConnection.value = selected == null ? null : nextConnections.find((item) => item.id === selected) || null; }
function handleConnectionsLoaded(value: ModelConnection[]) { connections.value = value; const id = detail.value?.mission.selectedModelConnectionId; selectedConnection.value = id == null ? null : value.find((item) => item.id === id) || null; }
async function handleConnectionSelection(value: { id: number } | ModelConnection | null) { const id = value?.id ?? null; const full = id == null ? null : connections.value.find((item) => item.id === id && item.enabled && item.verificationStatus === 'VERIFIED') || null; if (id !== null && !full) return; const mission = missionId.value; const userId = auth.user?.id ?? null; if (!contextIsCurrent(mission, userId)) return; selectedConnection.value = full; try { await selectGoMissionConnection(mission, id); if (contextIsCurrent(mission, userId)) await load(); } catch (reason) { if (contextIsCurrent(mission, userId)) error.value = goErrorMessage(reason, 'Model Connection 选择失败。'); } }
async function handleFilesSelected(value: ComposerFile[]) { const mission = missionId.value; const userId = auth.user?.id ?? null; if (!contextIsCurrent(mission, userId)) return; const additions = value.filter((item) => item.file && !composerFiles.value.some((old) => composerFileIdentity(old) === composerFileIdentity(item))); composerFiles.value = value; if (!additions.length) return; uploading.value = true; try { for (const item of additions) { if (!contextIsCurrent(mission, userId)) return; if (item.file) await uploadGoMissionFile(mission, item.file); } if (contextIsCurrent(mission, userId)) { composerFiles.value = []; await load(); } } catch (reason) { if (contextIsCurrent(mission, userId)) error.value = goErrorMessage(reason, '文件绑定失败。'); } finally { if (contextIsCurrent(mission, userId)) uploading.value = false; } }
async function sendMessage(payload: { text: string; files: ComposerFile[] }) { if (!payload.text.trim()) return; const mission = missionId.value; const userId = auth.user?.id ?? null; if (!contextIsCurrent(mission, userId)) return; sending.value = true; error.value = ''; try { await sendGoMissionMessage(mission, payload.text); if (contextIsCurrent(mission, userId)) { draft.value = ''; composerFiles.value = []; await load(); } } catch (reason) { if (contextIsCurrent(mission, userId)) error.value = goErrorMessage(reason, '消息发送失败；未伪造 Agent 结果。'); } finally { if (contextIsCurrent(mission, userId)) sending.value = false; } }
async function handleQuestionSubmitted() { const mission = missionId.value; const userId = auth.user?.id ?? null; if (contextIsCurrent(mission, userId)) await load(false); }
async function approveDraft() { const draftToApprove = renderedDraft.value; const mission = missionId.value; const userId = auth.user?.id ?? null; const version = requestVersion; if (!draftToApprove || renderedLockedSpecification.value || !contextIsCurrent(mission, userId, version) || approving.value) return; approving.value = true; error.value = ''; try { const locked = await approveGoPlanningDraft(draftToApprove.id); if (!contextIsCurrent(mission, userId, version)) return; if (!detail.value) return; detail.value = { ...detail.value, lockedSpecification: locked }; await load(false); } catch (reason) { if (contextIsCurrent(mission, userId, version)) error.value = goErrorMessage(reason, '方案锁定失败；当前方案仍保持草稿状态。'); } finally { approving.value = false; } }
async function requestGeneration() {
  const specification = renderedLockedSpecification.value;
  const mission = missionId.value;
  const userId = auth.user?.id ?? null;
  if (!specification || !generationCanRequest.value || !contextIsCurrent(mission, userId) || generationRequesting.value) return;
  generationRequesting.value = true;
  error.value = '';
  try {
    const result = await createGoGenerationJob(mission, specification.id, specification.version);
    if (!contextIsCurrent(mission, userId)) return;
    await load(false);
    if (result.generationJob.status === 'QUEUED' || result.generationJob.status === 'RUNNING' || result.generationJob.status === 'VERIFYING') startGenerationPolling(result.generationJob.id, mission, userId);
  } catch (reason) {
    if (contextIsCurrent(mission, userId)) error.value = goErrorMessage(reason, 'PPT 生成请求未提交；当前状态保持不变。');
  } finally {
    if (contextIsCurrent(mission, userId)) generationRequesting.value = false;
  }
}
function syncGenerationPolling(nextDetail: GoMissionDetail, id: number, userId: number | null) {
  const job = nextDetail.generationJobs[0];
  if (job && ['QUEUED', 'RUNNING', 'VERIFYING'].includes(job.status)) {
    if (generationPollJobId !== job.id) startGenerationPolling(job.id, id, userId);
  } else {
    stopGenerationPolling();
  }
}
function startGenerationPolling(jobId: string, id: number, userId: number | null) {
  stopGenerationPolling();
  generationPollJobId = jobId;
  generationPollTimer = window.setTimeout(async () => {
    generationPollTimer = undefined;
    if (!contextIsCurrent(id, userId)) return;
    try {
      const nextDetail = await getGoMission(id);
      if (!contextIsCurrent(id, userId)) return;
      detail.value = nextDetail;
      syncGenerationPolling(nextDetail, id, userId);
    } catch {
      if (contextIsCurrent(id, userId)) startGenerationPolling(jobId, id, userId);
    }
  }, 1500);
}
function stopGenerationPolling() { if (generationPollTimer !== undefined) { window.clearTimeout(generationPollTimer); generationPollTimer = undefined; } generationPollJobId = ''; }
async function handleLogout() { accountMenuOpen.value = false; try { await auth.logout(); } finally { await router.replace({ name: 'lessonforge-login' }); } }
function connectEvents(id: number, userId: number | null) {
  if (eventReconnectTimer !== undefined) {
    window.clearTimeout(eventReconnectTimer);
    eventReconnectTimer = undefined;
  }
  source?.close();
  source = new EventSource(goMissionEventsUrl(id, lastEventId), { withCredentials: true });
  const handleEvent = (event: Event) => {
    if (!contextIsCurrent(id, userId)) return;
    const message = event as MessageEvent<string>;
    try {
      const activity = JSON.parse(message.data) as GoActivityEvent;
      if (activity && typeof activity.id === 'number') {
        lastEventId = Math.max(lastEventId, activity.id);
        if (!events.value.some((item) => item.id === activity.id)) events.value = [...events.value, activity].sort((a, b) => a.id - b.id);
      }
    } catch { /* REST remains the source of truth when an event payload is malformed. */ }
    scheduleReload();
  };
  source.onmessage = handleEvent;
  for (const eventType of ['MISSION_CREATED', 'MESSAGE_CREATED', 'AGENT_RUN_UPDATED', 'QUESTION_CREATED', 'PLAN_DRAFT_CREATED', 'GENERATION_UPDATED', 'ARTIFACT_READY', 'ARTIFACT_INVALID', 'REVIEW_FEEDBACK_RECEIVED']) source.addEventListener(eventType, handleEvent);
  source.onerror = () => {
    if (!contextIsCurrent(id, userId)) return;
    source?.close();
    source = null;
    if (eventReconnectTimer !== undefined) return;
    eventReconnectTimer = window.setTimeout(() => {
      eventReconnectTimer = undefined;
      if (contextIsCurrent(id, userId)) connectEvents(id, userId);
    }, 1000);
  };
}
function scheduleReload() { if (reloadTimer !== undefined) return; reloadTimer = window.setTimeout(() => { reloadTimer = undefined; void load(false); }, 400); }
function artifactDownloadUrl(id: string) { return `${goApiBaseUrl}/api/artifacts/${id}/download`; }
function statusClass(status: string) { return status.toLowerCase().replaceAll('_', '-'); }
function statusLabel(status: string) { return ({ ASSIGNED: '待开始', IN_PROGRESS: '进行中', SUBMITTED: '已提交', COMPLETED: '已完成', WAITING_INPUTS: '等待补充', QUEUED: '排队中', RUNNING: '运行中', VERIFYING: '校验中', FAILED: '失败', CANCELLED: '已取消', FEEDBACK: '待修改', QUESTION: '需求确认', PLAN_DRAFT: '待确认', LOCKED: '已锁定', GENERATION_WAITING: '等待生成' } as Record<string, string>)[status] || status; }
function formatDateTime(value: string) { const date = new Date(value); return Number.isNaN(date.valueOf()) ? '—' : date.toLocaleString('zh-CN', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' }); }
watch(() => route.params.missionId, () => { stopGenerationPolling(); source?.close(); source = null; if (eventReconnectTimer !== undefined) { window.clearTimeout(eventReconnectTimer); eventReconnectTimer = undefined; } lastEventId = 0; void load(); });
watch(() => auth.user?.id, (next, previous) => { if (next === previous) return; stopGenerationPolling(); restoreWorkspaceLayout(); source?.close(); source = null; if (eventReconnectTimer !== undefined) { window.clearTimeout(eventReconnectTimer); eventReconnectTimer = undefined; } lastEventId = 0; requestVersion++; detail.value = null; questions.value = []; agentRuns.value = []; events.value = []; error.value = ''; if (next != null) void load(); });
watch([() => auth.user?.id, missionId], restoreComposerDraft, { immediate: true });
watch(draft, persistComposerDraft);
onMounted(() => { viewActive.value = true; restoreWorkspaceLayout(); void load(); });
onBeforeUnmount(() => { viewActive.value = false; requestVersion++; stopGenerationPolling(); source?.close(); if (reloadTimer !== undefined) window.clearTimeout(reloadTimer); if (eventReconnectTimer !== undefined) window.clearTimeout(eventReconnectTimer); stopResize(); });
</script>
