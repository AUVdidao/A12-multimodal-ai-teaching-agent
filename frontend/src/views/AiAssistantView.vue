<template>
  <section class="page assistant-page">
    <PageHeader
      eyebrow="AI 工作流助手"
      title="把真实项目推进到下一步"
      description="读取本人项目与当前 AI provider，执行需求澄清或生成方案建议。这里不是通用聊天，所有结果都来自现有工作流接口。"
    >
      <template #actions>
        <el-tooltip content="刷新项目与 AI provider 状态" placement="bottom">
          <el-button
            circle
            :icon="Refresh"
            :loading="projectsLoading || statusLoading"
            aria-label="刷新项目与 AI 状态"
            @click="loadPage"
          />
        </el-tooltip>
      </template>
    </PageHeader>

    <StatePanel
      v-if="projectsLoading && projects.length === 0"
      type="loading"
      title="正在读取 AI 工作流入口"
      description="正在读取你的教学项目和当前 provider 状态。"
    />
    <StatePanel v-else-if="projectsError && projects.length === 0" type="error" title="项目读取失败" :description="projectsError">
      <template #action><el-button type="primary" :icon="Refresh" @click="loadPage">重新加载</el-button></template>
    </StatePanel>
    <StatePanel
      v-else-if="projects.length === 0"
      type="empty"
      title="还没有可使用的教学项目"
      description="先创建真实教学项目，再回来基于项目字段运行需求澄清和方案建议。"
    >
      <template #action>
        <el-button type="primary" :icon="Plus" @click="router.push({ name: 'project-create' })">创建教学项目</el-button>
      </template>
    </StatePanel>

    <div v-else class="assistant-layout">
      <section class="surface-panel assistant-control-panel">
        <header class="panel-heading">
          <div>
            <span class="panel-heading__eyebrow">工作流入口</span>
            <h2>选择项目并发起操作</h2>
          </div>
          <el-icon><MagicStick /></el-icon>
        </header>

        <div class="provider-status" :class="`is-${providerTone}`">
          <div class="provider-status__icon"><el-icon><component :is="providerIcon" /></el-icon></div>
          <div>
            <strong>{{ providerLabel }}</strong>
            <p>{{ providerMessage }}</p>
          </div>
        </div>

        <el-alert v-if="projectsError" class="assistant-alert" type="warning" :title="projectsError" show-icon :closable="false" />
        <el-alert v-if="statusError" class="assistant-alert" type="error" :title="statusError" show-icon :closable="false" />

        <el-form class="assistant-form" label-position="top">
          <el-form-item label="教学项目">
            <el-select
              v-model="selectedProjectId"
              class="full-width"
              filterable
              :loading="projectsLoading"
              :disabled="clarifying || planning"
              placeholder="选择一个真实教学项目"
            >
              <el-option v-for="project in projects" :key="project.id" :label="projectLabel(project)" :value="project.id" />
            </el-select>
          </el-form-item>

          <div v-if="selectedProject" class="assistant-project-facts">
            <div><span>课程</span><strong>{{ selectedProject.courseName }}</strong></div>
            <div><span>章节</span><strong>{{ selectedProject.chapterTitle }}</strong></div>
            <div><span>模式</span><strong>{{ modeLabel(selectedProject.modelMode) }}</strong></div>
            <div><span>状态</span><strong>{{ projectStatusLabel(selectedProject.status) }}</strong></div>
          </div>

          <el-form-item label="当前需求描述" required>
            <el-input
              v-model="rawRequirement"
              type="textarea"
              :rows="5"
              maxlength="5000"
              show-word-limit
              resize="vertical"
              placeholder="补充课程、章节、学生对象、课时和希望生成的产物。"
            />
          </el-form-item>

          <el-form-item label="希望生成的产物">
            <el-checkbox-group v-model="outputTypes" class="output-types">
              <el-checkbox value="PPT">课件 PPT</el-checkbox>
              <el-checkbox value="DOCX">Word 教案</el-checkbox>
              <el-checkbox value="INTERACTION">互动内容</el-checkbox>
            </el-checkbox-group>
          </el-form-item>

          <el-alert v-if="actionError" class="assistant-alert" type="error" :title="actionError" show-icon :closable="false" />
          <div class="assistant-actions">
            <el-button
              type="primary"
              plain
              :icon="Search"
              :loading="clarifying"
              :disabled="!canRunWorkflow"
              @click="runClarificationCheck"
            >
              检查需求澄清
            </el-button>
            <el-button
              type="primary"
              :icon="DocumentChecked"
              :loading="planning"
              :disabled="!canRunWorkflow"
              @click="runPlanSuggestion"
            >
              生成方案建议
            </el-button>
          </div>
          <p v-if="workflowDisabledReason" class="workflow-hint">{{ workflowDisabledReason }}</p>
        </el-form>
      </section>

      <section class="assistant-results">
        <StatePanel
          v-if="overviewLoading"
          type="loading"
          title="正在读取项目状态"
          description="正在加载当前项目的阶段、资料与成果指标。"
        />
        <StatePanel v-else-if="overviewError" type="error" title="项目状态读取失败" :description="overviewError">
          <template #action>
            <el-button :icon="Refresh" @click="loadSelectedProjectOverview">重新读取项目状态</el-button>
          </template>
        </StatePanel>

        <section v-else-if="projectOverview" class="surface-panel project-summary-panel">
          <header class="summary-heading">
            <div>
              <span>当前项目</span>
              <h2>{{ projectOverview.project.projectName }}</h2>
              <p>{{ projectOverview.project.courseName }} · {{ projectOverview.project.chapterTitle }}</p>
            </div>
            <UiStatusPill
              :label="projectOverview.project.stageLabel"
              :tone="stageTone(projectOverview.project.stage)"
              dot
            />
          </header>

          <div class="project-metrics">
            <article>
              <span>整体进度</span>
              <strong>{{ projectOverview.metrics.overallProgress }}%</strong>
              <el-progress :percentage="projectOverview.metrics.overallProgress" :show-text="false" />
            </article>
            <article><span>已上传资料</span><strong>{{ projectOverview.metrics.uploadedMaterialCount }}</strong><small>已索引 {{ projectOverview.metrics.indexedMaterialCount }}</small></article>
            <article><span>知识片段</span><strong>{{ projectOverview.metrics.knowledgeChunkCount }}</strong><small>当前项目范围</small></article>
            <article><span>生成成果</span><strong>{{ generatedArtifactCount }}</strong><small>版本 {{ projectOverview.metrics.versionCount }}</small></article>
          </div>

          <div class="recommended-action">
            <span class="recommended-action__icon"><el-icon><Position /></el-icon></span>
            <div>
              <small>建议下一步 · 更新于 {{ formatDateTime(projectOverview.project.updatedAt) }}</small>
              <strong>{{ projectOverview.project.nextAction || '查看项目概览并确认当前阶段' }}</strong>
            </div>
            <el-button
              v-if="projectOverview.project.actionPath"
              :icon="ArrowRight"
              @click="goToPath(projectOverview.project.actionPath)"
            >
              前往处理
            </el-button>
          </div>

          <div class="quick-actions">
            <span>可用项目操作</span>
            <div v-if="enabledQuickActions.length">
              <el-button
                v-for="action in enabledQuickActions"
                :key="action.code"
                :icon="ArrowRight"
                plain
                @click="goToPath(action.path)"
              >
                {{ action.label }}
              </el-button>
            </div>
            <p v-else>当前没有额外可用操作，请先完成上方建议步骤。</p>
          </div>
        </section>

        <section class="surface-panel execution-panel" aria-labelledby="execution-heading">
          <header class="result-heading execution-heading">
            <div>
              <span>本次会话</span>
              <h2 id="execution-heading">最近执行结果</h2>
            </div>
            <el-tag type="info" effect="plain">{{ recentExecutions.length }} 条</el-tag>
          </header>

          <div v-if="recentExecutions.length" class="execution-list">
            <article v-for="execution in recentExecutions" :key="execution.id">
              <span :class="['execution-state', `is-${execution.status.toLowerCase()}`]">
                <el-icon><component :is="execution.status === 'SUCCESS' ? CircleCheck : WarningFilled" /></el-icon>
              </span>
              <div>
                <div class="execution-list__title">
                  <strong>{{ execution.label }}</strong>
                  <small>{{ formatDateTime(execution.executedAt) }}</small>
                </div>
                <p>{{ execution.projectName }} · {{ execution.summary }}</p>
                <span v-if="execution.nextAction">下一步：{{ execution.nextAction }}</span>
              </div>
              <el-button text type="primary" :icon="ArrowRight" @click="openExecution(execution)">进入流程</el-button>
            </article>
          </div>
          <div v-else class="execution-empty">
            <el-icon><Clock /></el-icon>
            <div><strong>本次会话尚无执行记录</strong><p>运行需求澄清或方案建议后，真实返回结果会记录在这里。</p></div>
          </div>
        </section>

        <StatePanel
          v-if="!clarificationResult && !planResult"
          type="info"
          title="等待一次真实工作流操作"
          description="可先检查需求完整性，再根据当前项目字段生成结构化方案建议。"
        />

        <section v-if="clarificationResult" class="surface-panel result-panel">
          <header class="result-heading">
            <div><span>需求澄清结果 · {{ clarificationResult.workflow }}</span><h2>下一步需要确认什么</h2></div>
            <el-tag :type="clarificationResult.missingFields.length ? 'warning' : 'success'" effect="light">
              {{ clarificationResult.missingFields.length ? `缺少 ${clarificationResult.missingFields.length} 项` : '信息已足够' }}
            </el-tag>
          </header>
          <p class="result-next-action">{{ clarificationResult.nextAction }}</p>
          <div v-if="clarificationResult.questions.length" class="result-block">
            <h3>需要回答的问题</h3>
            <ol class="result-list"><li v-for="question in clarificationResult.questions" :key="question">{{ question }}</li></ol>
          </div>
          <div v-if="suggestedFields.length" class="result-block">
            <h3>工作流返回的建议字段</h3>
            <dl class="suggestion-list"><div v-for="field in suggestedFields" :key="field[0]"><dt>{{ field[0] }}</dt><dd>{{ field[1] }}</dd></div></dl>
          </div>
          <div class="result-actions"><el-button type="primary" plain :icon="ArrowRight" @click="goToRequirements">进入项目需求页</el-button></div>
        </section>

        <section v-if="planResult" class="surface-panel result-panel">
          <header class="result-heading">
            <div><span>生成方案建议 · {{ planResult.workflow }}</span><h2>AI 返回的结构化方案</h2></div>
            <el-tag type="success" effect="light">{{ planResult.estimatedDuration || '已返回' }}</el-tag>
          </header>
          <p class="result-next-action">{{ planResult.nextAction }}</p>
          <div class="plan-columns">
            <section class="plan-block">
              <h3>PPT 大纲</h3>
              <StatePanel v-if="planResult.pptOutline.length === 0" type="empty" title="暂无 PPT 大纲" description="工作流没有返回 PPT 段落。" />
              <ol v-else class="plan-list">
                <li v-for="section in planResult.pptOutline" :key="section.title">
                  <strong>{{ section.title }}</strong>
                  <span v-for="point in section.points" :key="point">{{ point }}</span>
                  <small v-if="section.materialReference">依据：{{ section.materialReference }}</small>
                </li>
              </ol>
            </section>
            <section class="plan-block">
              <h3>Word 教案大纲</h3>
              <StatePanel v-if="planResult.docOutline.length === 0" type="empty" title="暂无教案大纲" description="工作流没有返回教案段落。" />
              <ol v-else class="plan-list">
                <li v-for="section in planResult.docOutline" :key="section.title">
                  <strong>{{ section.title }}</strong>
                  <span v-for="point in section.points" :key="point">{{ point }}</span>
                  <small v-if="section.materialReference">依据：{{ section.materialReference }}</small>
                </li>
              </ol>
            </section>
          </div>
          <div v-if="planResult.interactionPlan.length" class="result-block">
            <h3>互动安排</h3>
            <ul class="result-list"><li v-for="item in planResult.interactionPlan" :key="item">{{ item }}</li></ul>
          </div>
          <div class="result-actions"><el-button type="primary" plain :icon="ArrowRight" @click="goToGenerationPlan">打开项目生成流程</el-button></div>
        </section>
      </section>
    </div>
  </section>
</template>

<script setup lang="ts">
import {
  getAiGatewayStatus,
  runClarification,
  runGenerationPlan,
  type AiGatewayStatus,
  type ClarificationResponse,
  type GenerationMode,
  type GenerationPlanResponse,
} from '@/api/aiAssistant';
import { listProjects, type ProjectStatus, type TeachingProject } from '@/api/projects';
import { getProjectWorkspaceOverview, type ProjectOverview, type QuickAction } from '@/api/workspace';
import PageHeader from '@/components/PageHeader.vue';
import StatePanel from '@/components/StatePanel.vue';
import UiStatusPill from '@/components/ui/UiStatusPill.vue';
import { formatDateTime, stageTone } from '@/utils/presentation';
import {
  ArrowRight,
  CircleCheck,
  Clock,
  DocumentChecked,
  MagicStick,
  Plus,
  Position,
  Refresh,
  Search,
  WarningFilled,
} from '@element-plus/icons-vue';
import { computed, onMounted, ref, watch } from 'vue';
import { useRouter } from 'vue-router';

type WorkflowKind = 'CLARIFICATION' | 'GENERATION_PLAN';
type ExecutionStatus = 'SUCCESS' | 'FAILED';

interface WorkflowExecution {
  id: number;
  projectId: number;
  projectName: string;
  kind: WorkflowKind;
  label: string;
  status: ExecutionStatus;
  summary: string;
  nextAction?: string;
  executedAt: string;
}

const router = useRouter();
const projects = ref<TeachingProject[]>([]);
const selectedProjectId = ref<number>();
const rawRequirement = ref('');
const outputTypes = ref<string[]>(['PPT', 'DOCX', 'INTERACTION']);
const gatewayStatus = ref<AiGatewayStatus>();
const projectOverview = ref<ProjectOverview>();
const clarificationResult = ref<ClarificationResponse>();
const planResult = ref<GenerationPlanResponse>();
const recentExecutions = ref<WorkflowExecution[]>([]);
const projectsLoading = ref(false);
const statusLoading = ref(false);
const overviewLoading = ref(false);
const clarifying = ref(false);
const planning = ref(false);
const projectsError = ref('');
const statusError = ref('');
const overviewError = ref('');
const actionError = ref('');
let overviewRequestSequence = 0;
let executionSequence = 0;

const selectedProject = computed(() => projects.value.find((project) => project.id === selectedProjectId.value));
const suggestedFields = computed(() => Object.entries(clarificationResult.value?.suggestedFields || {}));
const generatedArtifactCount = computed(() => {
  if (!projectOverview.value) return 0;
  const metrics = projectOverview.value.metrics;
  return metrics.pptCount + metrics.docxCount + metrics.interactionCount;
});
const enabledQuickActions = computed<QuickAction[]>(() => {
  const overview = projectOverview.value;
  if (!overview) return [];
  return overview.quickActions
    .filter((action) => action.enabled && action.path && action.path !== overview.project.actionPath)
    .slice(0, 4);
});
const providerIsUnavailable = computed(() => Boolean(
  gatewayStatus.value
  && gatewayStatus.value.activeProvider === 'UNAVAILABLE'
  && !gatewayStatus.value.mockEnabled,
));
const canRunWorkflow = computed(() => Boolean(
  selectedProject.value
  && rawRequirement.value.trim()
  && outputTypes.value.length
  && !providerIsUnavailable.value
  && !clarifying.value
  && !planning.value,
));
const providerLabel = computed(() => {
  if (statusLoading.value && !gatewayStatus.value) return '正在读取 provider';
  const provider = gatewayStatus.value?.activeProvider || gatewayStatus.value?.requestedProvider || '';
  if (provider.toUpperCase().includes('MOCK') || gatewayStatus.value?.mockEnabled) return 'Mock provider';
  if (provider === 'UNAVAILABLE') return 'AI provider 不可用';
  return provider || '未知 provider';
});
const providerMessage = computed(() => {
  if (statusError.value) return '无法读取当前 AI provider 状态，请刷新后重试。';
  return gatewayStatus.value?.message || '正在读取当前工作流状态。';
});
const providerTone = computed(() => providerIsUnavailable.value ? 'danger' : gatewayStatus.value?.mockEnabled ? 'warning' : 'success');
const providerIcon = computed(() => providerIsUnavailable.value ? WarningFilled : gatewayStatus.value?.mockEnabled ? MagicStick : CircleCheck);
const workflowDisabledReason = computed(() => {
  if (providerIsUnavailable.value) return '当前选择了 Dify 且 Mock fallback 未启用，工作流不可执行。';
  if (!selectedProject.value) return '请选择一个真实教学项目。';
  if (!rawRequirement.value.trim()) return '请先补充当前需求描述。';
  if (!outputTypes.value.length) return '至少选择一种希望生成的产物。';
  return '';
});

watch(selectedProjectId, (projectId) => {
  const project = projects.value.find((item) => item.id === projectId);
  clarificationResult.value = undefined;
  planResult.value = undefined;
  actionError.value = '';
  if (!project) {
    rawRequirement.value = '';
    projectOverview.value = undefined;
    return;
  }
  rawRequirement.value = project.description?.trim()
    || `${project.courseName}，章节主题：${project.chapterTitle}，面向${project.targetStudents || '目标学生'}。`;
  outputTypes.value = ['PPT', 'DOCX', 'INTERACTION'];
  void loadSelectedProjectOverview();
});

async function loadPage() {
  projectsLoading.value = true;
  statusLoading.value = true;
  projectsError.value = '';
  statusError.value = '';
  const [projectResult, statusResult] = await Promise.allSettled([listProjects(), getAiGatewayStatus()]);
  if (projectResult.status === 'fulfilled') {
    projects.value = [...projectResult.value].sort((left, right) => right.updatedAt.localeCompare(left.updatedAt));
    const currentId = selectedProjectId.value;
    const nextId = projects.value.some((project) => project.id === currentId) ? currentId : projects.value[0]?.id;
    if (nextId === selectedProjectId.value) void loadSelectedProjectOverview();
    else selectedProjectId.value = nextId;
  } else {
    projectsError.value = resolveError(projectResult.reason, '暂时无法读取教学项目，请稍后重试。');
  }
  if (statusResult.status === 'fulfilled') gatewayStatus.value = statusResult.value;
  else statusError.value = resolveError(statusResult.reason, '暂时无法读取 AI provider 状态，请稍后重试。');
  projectsLoading.value = false;
  statusLoading.value = false;
}

async function loadSelectedProjectOverview() {
  const projectId = selectedProjectId.value;
  const requestId = ++overviewRequestSequence;
  projectOverview.value = undefined;
  overviewError.value = '';
  if (!projectId) {
    overviewLoading.value = false;
    return;
  }
  overviewLoading.value = true;
  try {
    const overview = await getProjectWorkspaceOverview(projectId);
    if (requestId === overviewRequestSequence) projectOverview.value = overview;
  } catch (error) {
    if (requestId === overviewRequestSequence) {
      overviewError.value = resolveError(error, '暂时无法读取当前项目状态，AI 工作流仍可单独执行。');
    }
  } finally {
    if (requestId === overviewRequestSequence) overviewLoading.value = false;
  }
}

async function runClarificationCheck() {
  const project = selectedProject.value;
  if (!project || !canRunWorkflow.value) return;
  clarifying.value = true;
  actionError.value = '';
  try {
    const result = await runClarification({
      projectId: project.id,
      rawRequirement: rawRequirement.value.trim(),
      knownFields: knownFieldsForProject(project),
      generationMode: generationMode(project.modelMode),
    });
    clarificationResult.value = result;
    addExecution({
      project,
      kind: 'CLARIFICATION',
      status: 'SUCCESS',
      summary: result.missingFields.length ? `识别到 ${result.missingFields.length} 项待补充信息` : '需求信息已满足当前工作流检查',
      nextAction: result.nextAction,
    });
  } catch (error) {
    const message = resolveError(error, '需求澄清工作流执行失败，请稍后重试。');
    actionError.value = message;
    addExecution({ project, kind: 'CLARIFICATION', status: 'FAILED', summary: message });
  } finally {
    clarifying.value = false;
  }
}

async function runPlanSuggestion() {
  const project = selectedProject.value;
  if (!project || !canRunWorkflow.value) return;
  planning.value = true;
  actionError.value = '';
  try {
    const result = await runGenerationPlan({
      projectId: project.id,
      courseName: project.courseName,
      chapterTopic: project.chapterTitle,
      targetAudience: project.targetStudents,
      outputTypes: outputTypes.value,
      generationMode: generationMode(project.modelMode),
    });
    planResult.value = result;
    addExecution({
      project,
      kind: 'GENERATION_PLAN',
      status: 'SUCCESS',
      summary: `返回 ${result.pptOutline.length + result.docOutline.length} 个大纲段落、${result.interactionPlan.length} 项互动安排`,
      nextAction: result.nextAction,
    });
  } catch (error) {
    const message = resolveError(error, '生成方案工作流执行失败，请稍后重试。');
    actionError.value = message;
    addExecution({ project, kind: 'GENERATION_PLAN', status: 'FAILED', summary: message });
  } finally {
    planning.value = false;
  }
}

function addExecution(input: {
  project: TeachingProject;
  kind: WorkflowKind;
  status: ExecutionStatus;
  summary: string;
  nextAction?: string;
}) {
  recentExecutions.value.unshift({
    id: ++executionSequence,
    projectId: input.project.id,
    projectName: input.project.projectName,
    kind: input.kind,
    label: input.kind === 'CLARIFICATION' ? '需求澄清检查' : '生成方案建议',
    status: input.status,
    summary: input.summary,
    nextAction: input.nextAction,
    executedAt: new Date().toISOString(),
  });
  recentExecutions.value = recentExecutions.value.slice(0, 6);
}

function goToRequirements() {
  if (selectedProject.value) void router.push({ name: 'project-requirements', params: { projectId: selectedProject.value.id } });
}

function goToGenerationPlan() {
  if (selectedProject.value) void router.push({ name: 'project-plan', params: { projectId: selectedProject.value.id } });
}

function goToPath(path: string) {
  if (path.startsWith('/')) void router.push(path);
}

function openExecution(execution: WorkflowExecution) {
  const name = execution.kind === 'CLARIFICATION' ? 'project-requirements' : 'project-plan';
  void router.push({ name, params: { projectId: execution.projectId } });
}

function projectLabel(project: TeachingProject) {
  return `${project.projectName} · ${project.courseName} · ${project.chapterTitle}`;
}

function knownFieldsForProject(project: TeachingProject) {
  return [
    'courseName',
    'chapterTopic',
    ...(project.targetStudents ? ['targetAudience'] : []),
    ...(project.lessonDuration ? ['lessonDurationMinutes'] : []),
  ];
}

function generationMode(value?: string): GenerationMode {
  return value === 'QUALITY' || value === 'HIGH_QUALITY' || value === 'ECONOMY' || value === 'MOCK' ? value : 'STANDARD';
}

function modeLabel(value?: string) {
  return value === 'HIGH_QUALITY' ? '高质量' : value === 'QUALITY' ? '质量优先' : value === 'ECONOMY' ? '经济模式' : value === 'MOCK' ? 'Mock' : '标准模式';
}

function projectStatusLabel(status: ProjectStatus) {
  return {
    CREATED: '项目已创建',
    REQUIREMENT_CONFIRMED: '需求已确认',
    MATERIAL_READY: '资料已就绪',
    INTENT_CONFIRMED: '意图已确认',
    GENERATED: '内容已生成',
    FINALIZED: '成果已定稿',
  }[status];
}

function resolveError(error: unknown, fallback: string) {
  const message = (error as { response?: { data?: { message?: string } } }).response?.data?.message;
  return message && !/(Exception|java\.|Axios)/i.test(message) ? message : fallback;
}

onMounted(loadPage);
</script>

<style scoped>
.assistant-page { min-width: 0; }
.assistant-layout { display: grid; grid-template-columns: minmax(330px, 0.68fr) minmax(0, 1.32fr); align-items: start; gap: 18px; }
.assistant-control-panel, .result-panel, .project-summary-panel, .execution-panel { min-width: 0; padding: 18px; }
.assistant-control-panel { position: sticky; top: 0; }
.assistant-results { display: grid; min-width: 0; gap: 14px; }
.panel-heading, .result-heading, .summary-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 14px; }
.panel-heading > .el-icon { color: var(--color-primary); font-size: 22px; }
.panel-heading__eyebrow, .result-heading span, .summary-heading > div > span { color: var(--color-primary); font-size: 11px; font-weight: 700; }
.panel-heading h2, .result-heading h2, .summary-heading h2 { margin: 4px 0 0; color: var(--color-text); font-size: 18px; line-height: 1.4; overflow-wrap: anywhere; }
.summary-heading p { margin: 4px 0 0; color: var(--color-text-muted); font-size: 12px; }
.provider-status { display: flex; align-items: flex-start; gap: 10px; padding: 12px; margin: 18px 0 14px; border: 1px solid #bce8db; border-radius: var(--radius-md); background: var(--color-success-soft); }
.provider-status.is-warning { border-color: #f0d59e; background: var(--color-warning-soft); }
.provider-status.is-danger { border-color: #f0c4c8; background: var(--color-danger-soft); }
.provider-status__icon { display: grid; width: 30px; height: 30px; flex: 0 0 30px; place-items: center; border-radius: var(--radius-md); background: var(--color-surface); color: var(--color-success); }
.is-warning .provider-status__icon { color: var(--color-warning); }
.is-danger .provider-status__icon { color: var(--color-danger); }
.provider-status strong { color: var(--color-text); font-size: 13px; }
.provider-status p { margin: 3px 0 0; color: var(--color-text-secondary); font-size: 12px; line-height: 1.55; overflow-wrap: anywhere; }
.assistant-form { margin-top: 16px; }
.full-width { width: 100%; }
.assistant-project-facts { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px; padding: 10px; margin: -3px 0 14px; border: 1px solid var(--color-border); border-radius: var(--radius-md); background: var(--color-surface-subtle); }
.assistant-project-facts div { min-width: 0; }
.assistant-project-facts span, .assistant-project-facts strong { display: block; overflow-wrap: anywhere; }
.assistant-project-facts span { color: var(--color-text-muted); font-size: 10px; }
.assistant-project-facts strong { margin-top: 2px; color: var(--color-text); font-size: 12px; line-height: 1.4; }
.output-types { display: flex; flex-wrap: wrap; gap: 8px 14px; }
.assistant-alert { margin-bottom: 12px; }
.assistant-actions { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 9px; margin-top: 3px; }
.assistant-actions .el-button { width: 100%; margin-left: 0; }
.workflow-hint { margin: 9px 0 0; color: var(--color-text-muted); font-size: 12px; line-height: 1.5; }
.project-metrics { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 9px; margin-top: 16px; }
.project-metrics article { min-width: 0; padding: 11px; border: 1px solid var(--color-border); border-radius: var(--radius-md); background: var(--color-surface-subtle); }
.project-metrics span, .project-metrics small { display: block; color: var(--color-text-muted); font-size: 10px; }
.project-metrics strong { display: block; margin: 4px 0; color: var(--color-text); font-size: 20px; }
.recommended-action { display: grid; grid-template-columns: 34px minmax(0, 1fr) auto; align-items: center; gap: 10px; padding: 12px; margin-top: 12px; border-left: 3px solid var(--color-primary); background: var(--color-primary-soft); }
.recommended-action__icon { display: grid; width: 32px; height: 32px; place-items: center; border-radius: var(--radius-md); background: var(--color-surface); color: var(--color-primary); }
.recommended-action small, .recommended-action strong { display: block; overflow-wrap: anywhere; }
.recommended-action small { color: var(--color-text-muted); font-size: 10px; }
.recommended-action strong { margin-top: 3px; color: var(--color-text); font-size: 13px; }
.quick-actions { display: grid; grid-template-columns: 105px minmax(0, 1fr); align-items: start; gap: 10px; padding-top: 12px; margin-top: 12px; border-top: 1px solid var(--color-border); }
.quick-actions > span { padding-top: 8px; color: var(--color-text-muted); font-size: 11px; }
.quick-actions > div { display: flex; flex-wrap: wrap; gap: 7px; }
.quick-actions p { margin: 7px 0 0; color: var(--color-text-muted); font-size: 12px; }
.result-heading { padding-bottom: 13px; border-bottom: 1px solid var(--color-border); }
.execution-list { display: grid; }
.execution-list article { display: grid; grid-template-columns: 30px minmax(0, 1fr) auto; align-items: center; gap: 10px; padding: 11px 0; border-bottom: 1px solid var(--color-border); }
.execution-list article:last-child { padding-bottom: 0; border-bottom: 0; }
.execution-state { display: grid; width: 28px; height: 28px; place-items: center; border-radius: 50%; background: var(--color-success-soft); color: var(--color-success); }
.execution-state.is-failed { background: var(--color-danger-soft); color: var(--color-danger); }
.execution-list__title { display: flex; align-items: baseline; justify-content: space-between; gap: 10px; }
.execution-list__title strong { color: var(--color-text); font-size: 12px; }
.execution-list__title small { color: var(--color-text-muted); font-size: 10px; white-space: nowrap; }
.execution-list p { margin: 3px 0 0; color: var(--color-text-secondary); font-size: 12px; line-height: 1.5; overflow-wrap: anywhere; }
.execution-list article > div > span { display: block; margin-top: 3px; color: var(--color-text-muted); font-size: 11px; overflow-wrap: anywhere; }
.execution-empty { display: flex; align-items: flex-start; gap: 10px; padding-top: 13px; }
.execution-empty > .el-icon { margin-top: 2px; color: var(--color-text-muted); font-size: 18px; }
.execution-empty strong { color: var(--color-text); font-size: 12px; }
.execution-empty p { margin: 3px 0 0; color: var(--color-text-muted); font-size: 12px; line-height: 1.5; }
.result-next-action { padding: 11px; margin: 14px 0 0; border-left: 3px solid var(--color-primary); background: var(--color-primary-soft); color: var(--color-text-secondary); font-size: 13px; line-height: 1.6; overflow-wrap: anywhere; }
.result-block { margin-top: 16px; }
.result-block h3, .plan-block h3 { margin: 0 0 8px; color: var(--color-text); font-size: 14px; }
.result-list { display: grid; gap: 7px; padding-left: 20px; margin: 0; color: var(--color-text-secondary); font-size: 13px; line-height: 1.6; }
.suggestion-list { display: grid; gap: 7px; margin: 0; }
.suggestion-list div { display: grid; grid-template-columns: 145px minmax(0, 1fr); gap: 9px; padding: 8px 0; border-bottom: 1px solid var(--color-border); }
.suggestion-list dt { color: var(--color-text-muted); font-size: 12px; overflow-wrap: anywhere; }
.suggestion-list dd { margin: 0; color: var(--color-text-secondary); font-size: 13px; overflow-wrap: anywhere; }
.plan-columns { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; margin-top: 16px; }
.plan-block { min-width: 0; padding: 13px; border: 1px solid var(--color-border); border-radius: var(--radius-md); background: var(--color-surface-subtle); }
.plan-list { display: grid; gap: 9px; padding-left: 19px; margin: 0; }
.plan-list li { display: grid; gap: 4px; color: var(--color-text-secondary); font-size: 12px; line-height: 1.5; }
.plan-list strong { color: var(--color-text); font-size: 13px; }
.plan-list small { color: var(--color-text-muted); }
.result-actions { display: flex; justify-content: flex-end; margin-top: 16px; }
@media (max-width: 1080px) { .assistant-layout { grid-template-columns: 1fr; } .assistant-control-panel { position: static; } }
@media (max-width: 760px) { .project-metrics { grid-template-columns: repeat(2, minmax(0, 1fr)); } .recommended-action { grid-template-columns: 34px minmax(0, 1fr); } .recommended-action .el-button { grid-column: 1 / -1; width: 100%; } .quick-actions { grid-template-columns: 1fr; gap: 3px; } .quick-actions > div, .quick-actions .el-button { width: 100%; } .plan-columns { grid-template-columns: 1fr; } }
@media (max-width: 560px) { .assistant-control-panel, .result-panel, .project-summary-panel, .execution-panel { padding: 15px; } .assistant-project-facts, .assistant-actions, .project-metrics { grid-template-columns: 1fr; } .assistant-actions, .assistant-actions .el-button, .result-actions, .result-actions .el-button { width: 100%; } .suggestion-list div { grid-template-columns: 1fr; gap: 3px; } .result-heading, .summary-heading { flex-direction: column; } .execution-list article { grid-template-columns: 28px minmax(0, 1fr); align-items: start; } .execution-list article > .el-button { grid-column: 2; justify-self: start; padding-left: 0; } .execution-list__title { align-items: flex-start; flex-direction: column; gap: 2px; } }
</style>
