<template>
  <section class="page assistant-page">
    <StatePanel
      v-if="projectsError && projects.length === 0"
      type="error"
      title="项目读取失败"
      :description="projectsError"
    >
      <template #action>
        <el-button type="primary" :icon="Refresh" @click="loadPage">重新加载</el-button>
      </template>
    </StatePanel>

    <div v-else class="assistant-shell">
      <AssistantProjectContext
        :empty="empty"
        :loading="loading"
        :projects="projects"
        :selected-project-id="selectedProjectId"
        :project-name="selectedProject?.projectName"
        :course-name="selectedProject?.courseName"
        :chapter-title="selectedProject?.chapterTitle"
        :target-students="selectedProject?.targetStudents"
        :lesson-duration="lessonDurationLabel"
        :stage-label="stageLabel"
        @create-project="goToCreateProject"
        @view-projects="goToProjects"
        @select-project="selectProject"
        @open-switch="goToProjects"
        @overview="goToProjectRoute('project-overview')"
      />

      <section v-if="contextFailureMessage" class="assistant-context-error" role="alert">
        <el-icon><WarningFilled /></el-icon>
        <div>
          <strong>部分项目上下文读取失败</strong>
          <span>{{ contextFailureMessage }}</span>
        </div>
        <el-button :icon="Refresh" :loading="contextLoading" @click="retryProjectContext">重新同步</el-button>
      </section>

      <main class="assistant-workspace">
        <AssistantConversation
          v-model="composerText"
          :messages="messages"
          :quick-prompts="quickPrompts"
          :loading="loading"
          :empty="empty"
          :sending="sending"
          :teacher-initial="teacherInitial"
          @send="sendComposerMessage"
          @quick-prompt="sendQuickPrompt"
          @action="handleAction"
          @new-dialogue="startNewDialogue"
          @history="openHistory"
          @create-project="goToCreateProject"
          @view-projects="goToProjects"
        />

        <AssistantSidePanel
          :progress-items="progressItems"
          :sources="sourceStatuses"
          :recent-work="recentWork"
          :loading="loading"
          :syncing="contextLoading"
          :project-synced="projectSynced"
          :student-mode="activeScenario === 'student-questions'"
          :service-state="serviceState"
          :service-label="providerPresentation.label"
          @navigate="router.push"
          @show-service-detail="showServiceDetail"
        />
      </main>
    </div>
  </section>
</template>

<script setup lang="ts">
import { runClarification, runGenerationPlan, runKimiAssistantChat, type GenerationMode } from '@/api/aiAssistant';
import { listProjectDialogues, saveDialogueMessage, type DialogueMessage, type DialogueSender } from '@/api/dialogues';
import { getGenerationWorkspace, type GenerationWorkspace } from '@/api/generation';
import { getKnowledgeOverview, type KnowledgeOverview } from '@/api/knowledge';
import { listMaterials, type MaterialRecord } from '@/api/materials';
import { listProjects, listRecentProjects, type RecentProject, type TeachingProject } from '@/api/projects';
import { listQuestions, type Question } from '@/api/questions';
import { getLatestTeachingRequirement, type TeachingRequirement } from '@/api/requirements';
import AssistantConversation from '@/components/assistant/AssistantConversation.vue';
import AssistantProjectContext from '@/components/assistant/AssistantProjectContext.vue';
import AssistantSidePanel from '@/components/assistant/AssistantSidePanel.vue';
import StatePanel from '@/components/StatePanel.vue';
import { useAiGatewayStatus } from '@/composables/useAiGatewayStatus';
import { useAuthStore } from '@/stores/auth';
import type {
  AssistantMessage,
  AssistantProgressItem,
  AssistantRecentWorkItem,
  AssistantResponseSection,
  AssistantSourceStatus,
  AssistantWorkspaceAction,
} from '@/types/assistant';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Refresh, WarningFilled } from '@element-plus/icons-vue';
import { computed, onMounted, ref } from 'vue';
import { useRoute, useRouter, type RouteLocationRaw } from 'vue-router';

const ASSISTANT_PROJECT_STORAGE_KEY = 'a12-assistant-project-id';

const router = useRouter();
const route = useRoute();
const authStore = useAuthStore();
const projects = ref<TeachingProject[]>([]);
const recentProjects = ref<RecentProject[]>([]);
const selectedProjectId = ref<number>();
const requirement = ref<TeachingRequirement | null>(null);
const materials = ref<MaterialRecord[]>([]);
const knowledgeOverview = ref<KnowledgeOverview | null>(null);
const generationWorkspace = ref<GenerationWorkspace | null>(null);
const questions = ref<Question[]>([]);
const dialogues = ref<DialogueMessage[]>([]);
const sourceState = ref<Record<string, AssistantSourceStatus['state']>>({});
const projectsLoading = ref(false);
const contextLoading = ref(false);
const projectsError = ref('');
const composerText = ref('');
const messages = ref<AssistantMessage[]>([]);
const sending = ref(false);
const activeScenario = ref('overview');
const sessionId = ref(createSessionId());
let contextRequestId = 0;
const pendingDialogueSaves = new Set<Promise<void>>();

const {
  status: gatewayStatus,
  loading: statusLoading,
  error: statusError,
  presentation: providerPresentation,
  refresh: loadGatewayStatus,
} = useAiGatewayStatus();

const quickPrompts = [
  { id: 'progress', label: '检查项目进度' },
  { id: 'requirement', label: '完善教学需求' },
  { id: 'intent', label: '检查教学意图' },
  { id: 'generation', label: '生成教学方案' },
  { id: 'student-questions', label: '汇总学生问题' },
];

const selectedProject = computed(() => projects.value.find((project) => project.id === selectedProjectId.value));
const loading = computed(() => projectsLoading.value || contextLoading.value || statusLoading.value);
const empty = computed(() => !loading.value && projects.value.length === 0);
const lessonDurationLabel = computed(() => selectedProject.value?.lessonDuration ? `${selectedProject.value.lessonDuration} 分钟` : undefined);
const stageLabel = computed(() => statusLabel(selectedProject.value?.status));
const projectSynced = computed(() => Boolean(selectedProject.value && !contextLoading.value && Object.values(sourceState.value).every((state) => state !== 'error')));
const providerUnavailable = computed(() => providerPresentation.value.unavailable);
const serviceState = computed<'ok' | 'error' | 'unknown'>(() => {
  if (providerPresentation.value.tone === 'danger' || statusError.value) return 'error';
  if (!gatewayStatus.value) return 'unknown';
  return 'ok';
});
const contextFailureMessage = computed(() => {
  if (!selectedProject.value || contextLoading.value) return '';
  const failed = sourceStatuses.value.filter((source) => source.state === 'error').map((source) => source.label);
  return failed.length ? `${failed.join('、')}暂时不可用；其余已读取数据仍可继续使用。` : '';
});

const currentArtifacts = computed(() => generationWorkspace.value?.artifacts ?? []);
const currentVersion = computed(() => Math.max(0, ...currentArtifacts.value.map((artifact) => artifact.versionNumber || 0)));
const nextVersion = computed(() => currentVersion.value + 1);
const openQuestions = computed(() => questions.value.filter((question) => question.status === 'OPEN'));
const answeredQuestions = computed(() => questions.value.filter((question) => question.status === 'ANSWERED'));
const teacherInitial = computed(() => userInitial(authStore.user?.displayName || authStore.user?.username));

const progressItems = computed<AssistantProgressItem[]>(() => [
  {
    id: 'requirement',
    label: '教学需求',
    value: requirement.value ? '已填写' : '待完善',
    tone: requirement.value ? 'green' : 'orange',
    route: projectRoute('project-requirements'),
  },
  {
    id: 'material',
    label: '参考资料',
    value: `${materials.value.length} 份`,
    tone: materials.value.length ? 'green' : 'gray',
    route: projectRoute('project-materials'),
  },
  {
    id: 'knowledge',
    label: '知识切片',
    value: `${knowledgeOverview.value?.chunkCount ?? 0} 条`,
    tone: (knowledgeOverview.value?.chunkCount ?? 0) > 0 ? 'purple' : 'gray',
    route: projectRoute('project-knowledge'),
  },
  {
    id: 'intent',
    label: '教学意图',
    value: generationWorkspace.value?.teachingIntent?.status === 'CONFIRMED' ? '已确认' : generationWorkspace.value?.teachingIntent ? '待确认' : '未生成',
    tone: generationWorkspace.value?.teachingIntent?.status === 'CONFIRMED' ? 'green' : 'orange',
    route: projectRoute('project-intent'),
  },
  {
    id: 'artifact',
    label: '成果版本',
    value: currentVersion.value ? `V${currentVersion.value}` : '未生成',
    tone: currentVersion.value ? 'purple' : 'gray',
    route: projectRoute(currentVersion.value ? 'project-preview' : 'project-plan'),
  },
  {
    id: 'question',
    label: '学生问题',
    value: openQuestions.value.length ? `${openQuestions.value.length} 个待答` : `${answeredQuestions.value.length} 个已答`,
    tone: openQuestions.value.length ? 'orange' : 'green',
    route: { name: 'teacher-questions' },
  },
]);

const sourceStatuses = computed<AssistantSourceStatus[]>(() => {
  if (!selectedProject.value) {
    return [
      { id: 'project', label: '项目基础信息', state: 'empty', detail: '等待项目' },
      { id: 'requirement', label: '教学需求', state: 'empty', detail: '未读取' },
      { id: 'materials', label: '参考资料', state: 'empty', detail: '未读取' },
      { id: 'knowledge', label: '知识库', state: 'empty', detail: '未读取' },
    ];
  }

  return [
    { id: 'project', label: '项目基础信息', state: 'loaded', detail: selectedProject.value.courseName },
    { id: 'requirement', label: '教学需求', state: sourceState.value.requirement || 'loading', detail: requirement.value ? '已读取' : '暂无' },
    { id: 'materials', label: '参考资料', state: sourceState.value.materials || 'loading', detail: `${materials.value.length} 份` },
    { id: 'knowledge', label: '知识库', state: sourceState.value.knowledge || 'loading', detail: `${knowledgeOverview.value?.chunkCount ?? 0} 条` },
    { id: 'generation', label: '生成工作区', state: sourceState.value.generation || 'loading', detail: currentVersion.value ? `V${currentVersion.value}` : '待生成' },
    { id: 'questions', label: '学生问题', state: sourceState.value.questions || 'loading', detail: `${openQuestions.value.length} 个待答` },
  ];
});

const recentWork = computed<AssistantRecentWorkItem[]>(() => {
  const items: AssistantRecentWorkItem[] = [];
  const lastDialogue = [...dialogues.value].sort(sortByCreatedAtDesc)[0];
  if (lastDialogue) {
    items.push({
      id: `dialogue-${lastDialogue.id}`,
      title: truncate(lastDialogue.content, 18),
      time: formatTime(lastDialogue.createdAt),
      route: projectRoute('project-requirements'),
      icon: 'target',
    });
  }
  if (materials.value[0]) {
    items.push({
      id: `material-${materials.value[0].id}`,
      title: truncate(materials.value[0].originalFilename, 18),
      time: formatTime(materials.value[0].updatedAt || materials.value[0].createdAt),
      route: projectRoute('project-materials'),
      icon: 'document',
    });
  }
  if (currentVersion.value) {
    items.push({
      id: `version-${currentVersion.value}`,
      title: `成果版本 V${currentVersion.value}`,
      time: generationWorkspace.value?.latestPlan ? formatTime(generationWorkspace.value.latestPlan.updatedAt) : '刚刚',
      route: projectRoute('project-preview'),
      icon: 'layers',
    });
  }
  if (openQuestions.value.length) {
    items.push({
      id: 'open-questions',
      title: `${openQuestions.value.length} 个学生问题待处理`,
      time: formatTime(openQuestions.value[0].updatedAt),
      route: { name: 'teacher-questions' },
      icon: 'question-help',
    });
  }
  for (const item of recentProjects.value) {
    if (items.length >= 4) break;
    if (item.project.id === selectedProjectId.value) continue;
    items.push({
      id: `recent-${item.project.id}`,
      title: truncate(item.project.projectName, 18),
      time: formatTime(item.lastVisitedAt),
      route: { name: 'project-overview', params: { projectId: item.project.id } },
      icon: 'document',
    });
  }
  return items.slice(0, 4);
});

onMounted(loadPage);

async function loadPage() {
  await waitForPendingDialogueSaves();
  projectsLoading.value = true;
  projectsError.value = '';
  const [projectResult, recentResult] = await Promise.allSettled([
    listProjects(),
    listRecentProjects(),
    loadGatewayStatus(),
  ]);

  if (projectResult.status === 'fulfilled') {
    projects.value = projectResult.value;
    const requestedId = Number(route.query.projectId);
    const storedId = Number(localStorage.getItem(ASSISTANT_PROJECT_STORAGE_KEY));
    const nextProjectId = projects.value.some((project) => project.id === requestedId)
      ? requestedId
      : projects.value.some((project) => project.id === storedId)
        ? storedId
        : projects.value[0]?.id;
    selectedProjectId.value = nextProjectId;
  } else {
    projectsError.value = resolveError(projectResult.reason, '暂时无法读取教学项目，请稍后重试。');
  }

  if (recentResult.status === 'fulfilled') recentProjects.value = recentResult.value;
  projectsLoading.value = false;

  if (selectedProjectId.value) {
    await loadProjectContext(selectedProjectId.value);
  } else {
    resetContext();
    messages.value = [];
  }
}

async function selectProject(projectId: number) {
  if (!projectId) return;
  await waitForPendingDialogueSaves();
  selectedProjectId.value = projectId;
  localStorage.setItem(ASSISTANT_PROJECT_STORAGE_KEY, String(projectId));
  await loadProjectContext(projectId);
}

async function retryProjectContext() {
  if (!selectedProjectId.value || contextLoading.value) return;
  await loadProjectContext(selectedProjectId.value);
}

async function loadProjectContext(projectId: number) {
  const requestId = ++contextRequestId;
  contextLoading.value = true;
  activeScenario.value = 'overview';
  resetContext();

  const [requirementResult, materialResult, knowledgeResult, generationResult, questionResult, dialogueResult] = await Promise.allSettled([
    getLatestTeachingRequirement(projectId),
    listMaterials(projectId),
    getKnowledgeOverview(projectId),
    getGenerationWorkspace(projectId),
    listQuestions(),
    listProjectDialogues(projectId),
  ]);
  if (requestId !== contextRequestId) return;

  if (requirementResult.status === 'fulfilled') {
    requirement.value = requirementResult.value;
    sourceState.value.requirement = requirementResult.value ? 'loaded' : 'empty';
  } else {
    sourceState.value.requirement = 'error';
  }

  if (materialResult.status === 'fulfilled') {
    materials.value = materialResult.value;
    sourceState.value.materials = materialResult.value.length ? 'loaded' : 'empty';
  } else {
    sourceState.value.materials = 'error';
  }

  if (knowledgeResult.status === 'fulfilled') {
    knowledgeOverview.value = knowledgeResult.value;
    sourceState.value.knowledge = knowledgeResult.value.chunkCount ? 'loaded' : 'empty';
  } else {
    sourceState.value.knowledge = 'error';
  }

  if (generationResult.status === 'fulfilled') {
    generationWorkspace.value = generationResult.value;
    sourceState.value.generation = generationResult.value.latestPlan || generationResult.value.artifacts.length ? 'loaded' : 'empty';
  } else {
    sourceState.value.generation = 'error';
  }

  if (questionResult.status === 'fulfilled') {
    questions.value = questionResult.value.filter((question) => question.projectId === projectId);
    sourceState.value.questions = questions.value.length ? 'loaded' : 'empty';
  } else {
    sourceState.value.questions = 'error';
  }

  if (dialogueResult.status === 'fulfilled') {
    dialogues.value = dialogueResult.value;
    sourceState.value.dialogues = dialogueResult.value.length ? 'loaded' : 'empty';
  } else {
    sourceState.value.dialogues = 'error';
  }

  restoreMessagesFromDialogues();
  contextLoading.value = false;
}

function resetContext() {
  requirement.value = null;
  materials.value = [];
  knowledgeOverview.value = null;
  generationWorkspace.value = null;
  questions.value = [];
  dialogues.value = [];
  sourceState.value = {};
}

function restoreMessagesFromDialogues() {
  const latestSessionId = latestDialogueSessionId(dialogues.value);
  if (!latestSessionId) {
    messages.value = [buildWelcomeMessage()];
    return;
  }
  sessionId.value = latestSessionId;
  const restored = dialogues.value
    .filter((dialogue) => dialogue.sessionId === latestSessionId)
    .sort(sortDialoguesAsc)
    .map(dialogueToMessage);
  messages.value = restored.length ? restored : [buildWelcomeMessage()];
}

function sendComposerMessage() {
  const content = composerText.value.trim();
  if (!content || !selectedProject.value || sending.value) return;
  composerText.value = '';
  void handleTeacherIntent(inferIntent(content), content);
}

function sendQuickPrompt(promptId: string) {
  const prompt = quickPrompts.find((item) => item.id === promptId);
  if (!prompt || !selectedProject.value || sending.value) return;
  void handleTeacherIntent(promptId, prompt.label);
}

async function handleTeacherIntent(intent: string, content: string) {
  addTeacherMessage(content);
  sending.value = true;
  activeScenario.value = intent;
  try {
    if (intent === 'progress') addAssistantMessage(buildProgressMessage());
    else if (intent === 'requirement') await runRequirementWorkflow(content);
    else if (intent === 'intent') addAssistantMessage(buildIntentMessage());
    else if (intent === 'generation') await runGenerationWorkflow(content);
    else if (intent === 'student-questions') addAssistantMessage(buildQuestionMessage());
    else await runKimiTeachingAssistant(content);
  } finally {
    sending.value = false;
  }
}

async function runKimiTeachingAssistant(teacherInput: string) {
  if (!selectedProject.value) return;
  const conversation = messages.value
    .slice(0, -1)
    .filter((message) => message.role === 'teacher' || message.role === 'assistant')
    .slice(-8)
    .map((message) => ({
      role: message.role === 'teacher' ? 'teacher' as const : 'assistant' as const,
      content: message.content,
    }));

  try {
    const response = await runKimiAssistantChat(selectedProject.value.id, {
      message: teacherInput,
      conversation,
    });
    addAssistantMessage({
      id: createMessageId(),
      role: 'assistant',
      content: response.content,
      createdAt: new Date().toISOString(),
      status: 'success',
      evidence: buildEvidence(),
      sections: [{
        id: 'kimi-assistant',
        title: `Kimi ${response.model}`,
        content: '已结合当前教学项目上下文生成建议。',
        tone: 'purple',
      }],
    });
  } catch (error) {
    addAssistantMessage(buildErrorMessage(
      resolveError(error, 'Kimi 教学助手暂时不可用，请稍后重试。'),
      'kimi-assistant',
    ));
  }
}

function handleAction(action: AssistantWorkspaceAction) {
  if (action.disabled) return;
  if (action.actionType === 'NAVIGATE' && action.route) {
    void router.push(action.route);
    return;
  }
  if (action.actionType === 'START_WORKFLOW') {
    const prompt = action.id === 'start-generation' ? '生成教学方案' : '完善教学需求';
    void handleTeacherIntent(action.id === 'start-generation' ? 'generation' : 'requirement', prompt);
    return;
  }
  if (action.actionType === 'RETRY') {
    void loadPage();
    return;
  }
  if (action.actionType === 'RETRY_SAVE' && action.messageId) {
    retryPersistMessage(action.messageId);
  }
}

function startNewDialogue() {
  sessionId.value = createSessionId();
  messages.value = selectedProject.value ? [buildWelcomeMessage()] : [];
}

async function openHistory() {
  await waitForPendingDialogueSaves();
  goToProjectRoute('project-requirements');
}

function showServiceDetail() {
  const detail = [
    providerPresentation.value.summary,
    providerPresentation.value.diagnostic,
    statusError.value,
  ].filter(Boolean).join('\n');
  void ElMessageBox.alert(detail || '当前没有更多诊断信息。', providerPresentation.value.label, { confirmButtonText: '知道了' });
}

async function runRequirementWorkflow(teacherInput: string) {
  if (!selectedProject.value) return;
  if (providerUnavailable.value) {
    addAssistantMessage(buildProviderUnavailableMessage('需求澄清工作流暂时不可用。'));
    return;
  }

  try {
    const result = await runClarification({
      projectId: selectedProject.value.id,
      rawRequirement: rawRequirementText(teacherInput),
      knownFields: knownFieldsForProject(selectedProject.value),
      generationMode: generationMode(selectedProject.value.modelMode),
    });

    addAssistantMessage({
      id: createMessageId(),
      role: 'assistant',
      content: result.missingFields.length
        ? `我检查了当前项目需求，还有 ${result.missingFields.length} 项信息建议补齐。`
        : '我检查了当前项目需求，现有信息已经足够进入下一步。',
      createdAt: new Date().toISOString(),
      status: 'success',
      evidence: buildEvidence(),
      sections: [
        {
          id: 'missing',
          title: result.missingFields.length ? '需要补齐的信息' : '需求状态',
          tone: result.missingFields.length ? 'orange' : 'green',
          items: result.missingFields.length
            ? result.missingFields.map((field) => ({ id: field, title: field, description: '建议回到教学需求页补充确认。', status: 'warning' }))
            : [{ id: 'ready', title: '信息已足够', description: result.nextAction, status: 'done' }],
        },
        {
          id: 'questions',
          title: 'AI 建议追问',
          content: result.questions.length ? result.questions.map((question, index) => `${index + 1}. ${question}`).join('\n') : '暂无额外追问。',
        },
      ],
      actions: [
        routeAction('open-requirement', '进入需求页补充', projectRoute('project-requirements'), 'primary'),
        workflowAction('start-generation', '继续生成教学方案', 'success'),
      ],
    });
  } catch (error) {
    addAssistantMessage(buildErrorMessage(resolveError(error, '需求澄清工作流执行失败，请稍后重试。'), 'requirement'));
  } finally {
    await loadGatewayStatus();
  }
}

async function runGenerationWorkflow(teacherInput: string) {
  if (!selectedProject.value) return;
  if (providerUnavailable.value) {
    addAssistantMessage(buildProviderUnavailableMessage('生成方案工作流暂时不可用。'));
    return;
  }

  try {
    const result = await runGenerationPlan({
      projectId: selectedProject.value.id,
      courseName: selectedProject.value.courseName,
      chapterTopic: selectedProject.value.chapterTitle,
      targetAudience: selectedProject.value.targetStudents,
      outputTypes: requirement.value?.outputTypes?.length ? requirement.value.outputTypes : ['PPT', 'DOCX', 'INTERACTION'],
      generationMode: generationMode(selectedProject.value.modelMode),
    });

    addAssistantMessage({
      id: createMessageId(),
      role: 'assistant',
      content: teacherInput && !isDefaultGenerationPrompt(teacherInput)
        ? '我已调用生成方案工作流。当前接口只接收项目、课程、章节、授课对象和输出类型，因此本轮自由编辑要求不会直接写入生成请求；请进入生成流程继续细化。'
        : '我已基于当前项目数据调用生成方案工作流，你可以进入生成流程继续编辑和确认。',
      createdAt: new Date().toISOString(),
      status: 'success',
      evidence: buildEvidence(),
      versionNotice: `本次方案建议来自当前项目数据。真正生成成果版本需在内容生成页继续确认，不会在副驾驶内覆盖当前 V${currentVersion.value || 0}。`,
      sections: [
        ...(teacherInput && !isDefaultGenerationPrompt(teacherInput)
          ? [outlineSection('instruction-boundary', '本轮输入处理', [`已收到：${teacherInput}`, '生成方案接口暂不支持自由编辑指令参数，未篡改课程名或章节主题来伪装传递。'])] : []),
        outlineSection('ppt', 'PPT 大纲', result.pptOutline.map((section) => `${section.title}：${section.points.join('、')}`)),
        outlineSection('doc', 'Word 教案大纲', result.docOutline.map((section) => `${section.title}：${section.points.join('、')}`)),
        outlineSection('interaction', '互动安排', result.interactionPlan),
      ],
      actions: [
        routeAction('open-plan', '打开生成流程', projectRoute('project-plan'), 'primary'),
        routeAction('open-preview', '查看已有成果', projectRoute('project-preview'), 'secondary', !currentVersion.value, '当前项目还没有已生成成果。'),
      ],
    });
  } catch (error) {
    addAssistantMessage(buildErrorMessage(resolveError(error, '生成方案工作流执行失败，请稍后重试。'), 'generation'));
  } finally {
    await loadGatewayStatus();
  }
}

function buildWelcomeMessage(): AssistantMessage {
  const project = selectedProject.value;
  const missing = [
    requirement.value ? '' : '教学需求',
    materials.value.length ? '' : '参考资料',
    generationWorkspace.value?.teachingIntent ? '' : '教学意图',
  ].filter(Boolean);

  return {
    id: createMessageId(),
    role: 'assistant',
    content: project
      ? `我已读取「${project.projectName}」的上下文。你可以让我检查进度、完善需求、生成教学方案，或汇总学生问题。`
      : '请先选择一个教学项目，我会读取项目上下文后继续协助你。',
    createdAt: new Date().toISOString(),
    status: 'success',
    evidence: buildEvidence(),
    sections: [
      {
        id: 'context',
        title: missing.length ? '当前需要关注' : '当前状态',
        tone: missing.length ? 'orange' : 'green',
        content: missing.length ? `${missing.join('、')}还需要继续完善。` : '项目关键上下文已具备，可以继续推进生成与发布前检查。',
      },
      {
        id: 'workflows',
        title: '可以直接开始',
        items: [
          { id: 'requirement', title: '完善教学需求', description: '检查缺失字段和建议追问。', status: requirement.value ? 'done' : 'pending', action: workflowAction('start-requirement', '开始检查', 'primary') },
          { id: 'generation', title: '生成教学方案', description: '生成 PPT、教案和互动安排建议。', status: currentVersion.value ? 'done' : 'pending', action: workflowAction('start-generation', '生成方案', 'success') },
        ],
      },
    ],
    actions: [
      routeAction('overview', '查看项目概览', projectRoute('project-overview'), 'secondary'),
      routeAction('materials', '管理参考资料', projectRoute('project-materials'), 'secondary'),
    ],
  };
}

function buildProgressMessage(): AssistantMessage {
  return {
    id: createMessageId(),
    role: 'assistant',
    content: '我按项目流程检查了一遍，下面是当前最值得处理的环节。',
    createdAt: new Date().toISOString(),
    status: 'success',
    evidence: buildEvidence(),
    sections: [
      {
        id: 'progress',
        title: '项目进度',
        items: progressItems.value.map((item) => ({
          id: item.id,
          title: item.label,
          description: item.value,
          status: item.tone === 'green' || item.tone === 'purple' ? 'done' : item.tone === 'orange' ? 'warning' : 'pending',
          action: item.route ? routeAction(`route-${item.id}`, '去处理', item.route, item.tone === 'orange' ? 'primary' : 'secondary') : undefined,
        })),
      },
    ],
    actions: [routeAction('overview', '打开项目概览', projectRoute('project-overview'), 'primary')],
  };
}

function buildIntentMessage(): AssistantMessage {
  const intent = generationWorkspace.value?.teachingIntent;
  return {
    id: createMessageId(),
    role: 'assistant',
    content: intent ? '我找到了当前教学意图，请确认它是否仍符合本节课目标。' : '当前项目还没有可用的教学意图，建议先从资料与需求生成教学意图。',
    createdAt: new Date().toISOString(),
    status: 'success',
    evidence: buildEvidence(),
    sections: [
      {
        id: 'intent',
        title: intent ? '教学意图摘要' : '下一步建议',
        tone: intent?.status === 'CONFIRMED' ? 'green' : 'orange',
        content: intent
          ? [
              intent.generationGoal || (Array.isArray(intent.generationGoals) ? intent.generationGoals.join('、') : ''),
              intent.contentBasis || intent.primaryBasis || '',
              intent.teachingApproach || '',
            ].filter(Boolean).join('\n')
          : '先确认需求摘要与参考资料，再进入教学意图页生成并确认。',
      },
    ],
    actions: [routeAction('open-intent', '打开教学意图', projectRoute('project-intent'), 'primary')],
  };
}

function buildQuestionMessage(): AssistantMessage {
  return {
    id: createMessageId(),
    role: 'assistant',
    content: openQuestions.value.length
      ? `当前项目有 ${openQuestions.value.length} 个学生问题等待处理，我已按状态汇总。`
      : '当前项目没有待处理的学生问题。',
    createdAt: new Date().toISOString(),
    status: 'success',
    evidence: [
      { id: 'open', label: '待答问题', value: `${openQuestions.value.length}`, source: 'STUDENT_QUESTION', tone: 'orange' },
      { id: 'answered', label: '已回答', value: `${answeredQuestions.value.length}`, source: 'STUDENT_QUESTION', tone: 'green' },
    ],
    sections: [
      {
        id: 'questions',
        title: '问题概览',
        items: questions.value.slice(0, 5).map((question) => ({
          id: String(question.id),
          title: question.title,
          description: `${question.studentName} · ${statusTextForQuestion(question.status)}`,
          status: question.status === 'ANSWERED' ? 'done' : question.status === 'OPEN' ? 'warning' : 'pending',
        })),
      },
    ],
    actions: [routeAction('open-questions', '进入学生问答', { name: 'teacher-questions' }, 'primary')],
  };
}

function buildUnsupportedMessage(): AssistantMessage {
  return {
    id: createMessageId(),
    role: 'assistant',
    content: '当前后端还没有开放任意连续聊天接口。我可以基于真实项目数据执行这些工作流：检查进度、完善需求、检查教学意图、生成教学方案、汇总学生问题。',
    createdAt: new Date().toISOString(),
    status: 'success',
    evidence: buildEvidence(),
    actions: [
      workflowAction('start-requirement', '完善教学需求', 'primary'),
      workflowAction('start-generation', '生成教学方案', 'success'),
      routeAction('open-overview', '查看项目概览', projectRoute('project-overview'), 'secondary'),
    ],
  };
}

function buildProviderUnavailableMessage(content: string): AssistantMessage {
  return {
    id: createMessageId(),
    role: 'assistant',
    content: `${content}\n${providerPresentation.value.summary}`,
    createdAt: new Date().toISOString(),
    status: 'error',
    evidence: buildEvidence(),
    actions: [retryAction()],
  };
}

function buildErrorMessage(content: string, intent: string): AssistantMessage {
  return {
    id: createMessageId(),
    role: 'assistant',
    content,
    createdAt: new Date().toISOString(),
    status: 'error',
    evidence: buildEvidence(),
    actions: [
      {
        ...retryAction(),
        id: `retry-${intent}`,
      },
    ],
  };
}

function addTeacherMessage(content: string) {
  const message: AssistantMessage = {
    id: createMessageId(),
    role: 'teacher',
    content,
    createdAt: new Date().toISOString(),
    status: 'success',
    persistenceStatus: 'pending',
    persistRetryCount: 0,
  };
  messages.value.push(message);
  persistMessage(message, 'TEACHER');
}

function addAssistantMessage(message: AssistantMessage) {
  message.persistenceStatus = 'pending';
  message.persistRetryCount = message.persistRetryCount ?? 0;
  messages.value.push(message);
  persistMessage(message, 'ASSISTANT');
}

function persistMessage(message: AssistantMessage, sender: DialogueSender) {
  if (!selectedProject.value || !message.content.trim()) {
    updateMessage(message.id, { persistenceStatus: 'not_required' });
    return;
  }
  updateMessage(message.id, { persistenceStatus: 'pending', persistenceError: '' });
  const projectId = selectedProject.value.id;
  const currentSessionId = sessionId.value;
  const savePromise = saveDialogueMessage(projectId, {
    sessionId: currentSessionId,
    sender,
    content: message.content,
    roundNo: messageRoundNo(message.id),
  })
    .then((saved) => {
      updateMessage(message.id, { persistenceStatus: 'saved', persistenceError: '' });
      dialogues.value = mergeSavedDialogue(dialogues.value, saved);
    })
    .catch((error) => {
      const persistenceError = resolveError(error, '对话保存失败，刷新后这条消息可能丢失。');
      updateMessage(message.id, { persistenceStatus: 'failed', persistenceError });
      ElMessage.error(persistenceError);
    });
  trackDialogueSave(savePromise);
}

function retryPersistMessage(messageId: string) {
  const message = messages.value.find((item) => item.id === messageId);
  if (!message || message.persistenceStatus !== 'failed') return;
  if ((message.persistRetryCount || 0) >= 1) return;
  updateMessage(message.id, { persistRetryCount: (message.persistRetryCount || 0) + 1 });
  persistMessage(message, message.role === 'teacher' ? 'TEACHER' : 'ASSISTANT');
}

function updateMessage(messageId: string, patch: Partial<AssistantMessage>) {
  const index = messages.value.findIndex((item) => item.id === messageId);
  if (index === -1) return;
  messages.value[index] = { ...messages.value[index], ...patch };
}

function trackDialogueSave(promise: Promise<void>) {
  pendingDialogueSaves.add(promise);
  void promise.finally(() => pendingDialogueSaves.delete(promise));
}

async function waitForPendingDialogueSaves() {
  if (!pendingDialogueSaves.size) return;
  await Promise.allSettled([...pendingDialogueSaves]);
}

function messageRoundNo(messageId: string) {
  const index = messages.value.findIndex((message) => message.id === messageId);
  return index >= 0 ? index + 1 : messages.value.length + 1;
}

function mergeSavedDialogue(items: DialogueMessage[], saved: DialogueMessage) {
  const next = items.filter((item) => item.id !== saved.id);
  next.push(saved);
  return next.sort(sortDialoguesAsc);
}

function latestDialogueSessionId(items: DialogueMessage[]) {
  const latestBySession = new Map<string, DialogueMessage>();
  for (const item of items) {
    const current = latestBySession.get(item.sessionId);
    if (!current || sortDialoguesByRecencyAsc(current, item) < 0) latestBySession.set(item.sessionId, item);
  }
  return [...latestBySession.values()].sort(sortDialoguesByRecencyAsc).at(-1)?.sessionId;
}

function dialogueToMessage(dialogue: DialogueMessage): AssistantMessage {
  return {
    id: `dialogue-${dialogue.id}`,
    role: dialogue.sender === 'TEACHER' ? 'teacher' : 'assistant',
    content: dialogue.content,
    createdAt: dialogue.createdAt,
    status: 'success',
    persistenceStatus: 'saved',
    persistRetryCount: 0,
  };
}

function buildEvidence() {
  return [
    { id: 'project', label: '项目', value: selectedProject.value?.projectName || '未选择', source: 'PROJECT' as const, tone: 'purple' as const },
    { id: 'requirement', label: '需求', value: requirement.value ? '已读取' : '暂无', source: 'REQUIREMENT' as const, tone: requirement.value ? 'green' as const : 'orange' as const },
    { id: 'materials', label: '资料', value: `${materials.value.length} 份`, source: 'MATERIAL' as const, tone: materials.value.length ? 'green' as const : 'gray' as const },
    { id: 'knowledge', label: '知识切片', value: `${knowledgeOverview.value?.chunkCount ?? 0}`, source: 'KNOWLEDGE' as const, tone: (knowledgeOverview.value?.chunkCount ?? 0) ? 'purple' as const : 'gray' as const },
  ];
}

function routeAction(
  id: string,
  label: string,
  route: RouteLocationRaw | undefined,
  tone: AssistantWorkspaceAction['tone'],
  disabled = false,
  disabledReason = '',
): AssistantWorkspaceAction {
  return { id, label, route, tone, actionType: 'NAVIGATE', disabled: disabled || !route, disabledReason };
}

function workflowAction(id: string, label: string, tone: AssistantWorkspaceAction['tone']): AssistantWorkspaceAction {
  return { id, label, tone, actionType: 'START_WORKFLOW' };
}

function retryAction(): AssistantWorkspaceAction {
  return { id: 'retry', label: '重新读取上下文', tone: 'primary', actionType: 'RETRY' };
}

function outlineSection(id: string, title: string, rows: string[]): AssistantResponseSection {
  return {
    id,
    title,
    tone: rows.length ? 'purple' : 'gray',
    content: rows.length ? rows.map((row, index) => `${index + 1}. ${row}`).join('\n') : '本次工作流没有返回该部分内容。',
  };
}

function inferIntent(content: string) {
  if (/问题|答疑|学生|提问/.test(content)) return 'student-questions';
  if (/生成|方案|PPT|教案|课件|互动/.test(content)) return 'generation';
  if (/需求|澄清|补充|字段/.test(content)) return 'requirement';
  if (/意图|目标|依据/.test(content)) return 'intent';
  if (/进度|状态|下一步|现在/.test(content)) return 'progress';
  return 'unknown';
}

function rawRequirementText(teacherInput = '') {
  const savedParts = [
    requirement.value?.rawRequirementText?.trim(),
    requirement.value?.teachingGoals ? `教学目标：${requirement.value.teachingGoals}` : '',
    requirement.value?.keyPoints ? `重点：${requirement.value.keyPoints}` : '',
    requirement.value?.difficultPoints ? `难点：${requirement.value.difficultPoints}` : '',
    selectedProject.value?.description?.trim(),
  ].filter(Boolean);
  const base = savedParts.length
    ? savedParts.join('\n')
    : selectedProject.value
      ? `${selectedProject.value.courseName}，章节主题：${selectedProject.value.chapterTitle}，面向${selectedProject.value.targetStudents || '目标学生'}。`
      : '';
  const currentInput = teacherInput.trim();
  return currentInput ? `${base}\n\n本轮教师补充：${currentInput}` : base;
}

function knownFieldsForProject(project: TeachingProject) {
  return [
    'courseName',
    'chapterTopic',
    ...(project.targetStudents ? ['targetAudience'] : []),
    ...(project.lessonDuration ? ['lessonDurationMinutes'] : []),
    ...(requirement.value?.teachingGoals ? ['teachingGoals'] : []),
    ...(requirement.value?.outputTypes?.length ? ['outputTypes'] : []),
  ];
}

function generationMode(value?: string): GenerationMode {
  return value === 'QUALITY' || value === 'HIGH_QUALITY' || value === 'ECONOMY' || value === 'MOCK' ? value : 'STANDARD';
}

function isDefaultGenerationPrompt(value: string) {
  return value.trim() === '生成教学方案';
}

function projectRoute(name: string): RouteLocationRaw | undefined {
  if (!selectedProject.value) return undefined;
  return { name, params: { projectId: selectedProject.value.id } };
}

function goToProjectRoute(name: string) {
  const route = projectRoute(name);
  if (route) void router.push(route);
}

function goToCreateProject() {
  void router.push({ name: 'project-create' });
}

function goToProjects() {
  void router.push({ name: 'projects' });
}

function statusLabel(status?: string) {
  const labels: Record<string, string> = {
    CREATED: '项目已创建',
    REQUIREMENT_CONFIRMED: '需求已确认',
    MATERIAL_READY: '资料已准备',
    INTENT_CONFIRMED: '意图已确认',
    GENERATED: '成果已生成',
    FINALIZED: '已定稿',
  };
  return status ? labels[status] || status : '待选择项目';
}

function statusTextForQuestion(status: Question['status']) {
  return status === 'OPEN' ? '待回答' : status === 'ANSWERED' ? '已回答' : '已关闭';
}

function sortDialoguesAsc(a: DialogueMessage, b: DialogueMessage) {
  return (a.roundNo || 0) - (b.roundNo || 0)
    || new Date(a.createdAt).getTime() - new Date(b.createdAt).getTime()
    || a.id - b.id;
}

function sortDialoguesByRecencyAsc(a: DialogueMessage, b: DialogueMessage) {
  return new Date(a.createdAt).getTime() - new Date(b.createdAt).getTime()
    || a.id - b.id;
}

function sortByCreatedAtDesc(a: { createdAt: string }, b: { createdAt: string }) {
  return new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime();
}

function userInitial(value?: string) {
  const first = Array.from((value || '').trim()).find((char) => /\S/.test(char));
  return first || '师';
}

function formatTime(value?: string) {
  if (!value) return '刚刚';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '刚刚';
  return date.toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' });
}

function truncate(value: string, length: number) {
  return value.length > length ? `${value.slice(0, length)}...` : value;
}

function resolveError(error: unknown, fallback: string) {
  const message = (error as { response?: { data?: { message?: string } } }).response?.data?.message;
  return message && !/(Exception|java\.|Axios|Error)/i.test(message) ? message : fallback;
}

function createSessionId() {
  return `assistant-${Date.now()}-${Math.random().toString(16).slice(2, 8)}`;
}

function createMessageId() {
  return `${Date.now()}-${Math.random().toString(16).slice(2, 8)}`;
}
</script>

<style scoped>
.assistant-page {
  display: grid;
  min-width: 0;
  min-height: calc(100vh - 116px);
  gap: 18px;
}

.assistant-shell {
  display: grid;
  min-width: 0;
  min-height: 0;
  gap: 16px;
}

.assistant-context-error {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  align-items: center;
  gap: 12px;
  padding: 12px 14px;
  border: 1px solid #f0c8cc;
  border-radius: 8px;
  background: #fff7f7;
  color: var(--ui-danger);
}

.assistant-context-error > .el-icon {
  font-size: 20px;
}

.assistant-context-error div {
  display: grid;
  min-width: 0;
  gap: 3px;
}

.assistant-context-error strong {
  color: #9f3240;
  font-size: 14px;
}

.assistant-context-error span {
  color: #75515a;
  font-size: 12px;
  line-height: 1.5;
}

.assistant-workspace {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(280px, 360px);
  min-width: 0;
  min-height: min(720px, calc(100vh - 268px));
  gap: 16px;
}

@media (max-width: 1180px) {
  .assistant-workspace {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 760px) {
  .assistant-page {
    min-height: auto;
  }

  .assistant-workspace {
    min-height: 680px;
  }

  .assistant-context-error {
    grid-template-columns: auto minmax(0, 1fr);
  }

  .assistant-context-error .el-button {
    grid-column: 1 / -1;
    width: 100%;
  }
}
</style>
