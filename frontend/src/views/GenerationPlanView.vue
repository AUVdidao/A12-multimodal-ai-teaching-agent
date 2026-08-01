<template>
  <section class="generation-page">
    <StatePanel v-if="loading" type="loading" title="正在加载内容生成工作区" description="正在读取教学意图与最新生成方案。" />

    <StatePanel
      v-else-if="!workspace"
      type="error"
      title="内容生成工作区加载失败"
      :description="workspaceError"
    >
      <template #action>
        <el-button type="primary" :icon="Refresh" @click="loadWorkspace">重新加载</el-button>
      </template>
    </StatePanel>

    <template v-else>
      <ProjectContextHeader v-if="projectContext" :project="projectContext" />
      <ProjectWorkspaceNav :project-id="workspace.projectId" />

      <header class="generation-hero">
        <div class="generation-hero__main">
          <span class="generation-hero__icon"><el-icon><MagicStick /></el-icon></span>
          <div>
            <span class="generation-hero__eyebrow">{{ workspace.projectName }}</span>
            <h2>教学内容生成</h2>
            <p>{{ heroStatus }}</p>
          </div>
        </div>
        <div class="generation-hero__actions">
          <el-button @click="openCopilot">教学副驾驶</el-button>
          <el-button :icon="Back" @click="router.push(`/projects/${projectId}/intent`)">教学意图</el-button>
          <el-button v-if="artifactCount" type="primary" :icon="View" @click="router.push(`/projects/${projectId}/preview`)">
            查看成果
          </el-button>
        </div>
      </header>

      <div class="generation-layout">
        <main class="generation-main">
          <section v-if="!plan" class="generation-empty">
            <span class="generation-empty__icon"><el-icon><MagicStick /></el-icon></span>
            <h3>尚未生成内容方案</h3>
            <p>{{ canCreatePlan ? '教学意图已就绪，可以生成本项目的内容方案。' : '当前项目尚未满足方案生成条件。' }}</p>
            <el-button
              type="primary"
              :icon="MagicStick"
              :loading="creating"
              :disabled="!canCreatePlan"
              @click="createPlan"
            >
              生成方案
            </el-button>
            <div v-if="actionError" class="generation-action-error">
              <el-alert :title="actionError" type="error" show-icon :closable="false" />
              <el-button text type="primary" :icon="Refresh" @click="retryLastAction">重试</el-button>
            </div>
          </section>

          <template v-else>
            <section class="plan-toolbar">
              <div>
                <div class="plan-toolbar__title">
                  <h3>生成方案</h3>
                  <UiStatusPill :label="plan.confirmed ? '已确认' : isDirty ? '有未保存修改' : '草稿已保存'" :tone="plan.confirmed ? 'green' : isDirty ? 'orange' : 'blue'" />
                </div>
                <p>更新于 {{ formatDateTime(plan.updatedAt) }}</p>
              </div>
            </section>

            <GenerationOutlineEditor
              v-model="pptOutline"
              title="PPT 课件大纲"
              description="课件页面顺序、标题与内容说明"
              kind="ppt"
              tone="purple"
              :disabled="!canEditPlan"
            />

            <GenerationOutlineEditor
              v-model="docOutline"
              title="教案大纲"
              description="教案章节顺序、标题与教学安排"
              kind="docx"
              tone="blue"
              :disabled="!canEditPlan"
            />

            <section class="interaction-editor">
              <header class="interaction-editor__header">
                <div class="interaction-editor__title">
                  <span><el-icon><ChatDotRound /></el-icon></span>
                  <div>
                    <h3>互动方案</h3>
                    <p>课堂互动、问答与练习安排</p>
                  </div>
                </div>
                <UiStatusPill :label="`${interactionPlan.length} 项`" tone="gray" />
              </header>

              <div v-if="interactionPlan.length" class="interaction-editor__list">
                <div v-for="(_, index) in interactionPlan" :key="index" class="interaction-editor__row">
                  <span>{{ index + 1 }}</span>
                  <el-input
                    v-model="interactionPlan[index]"
                    :disabled="!canEditPlan"
                    :aria-label="`第 ${index + 1} 项互动方案`"
                    maxlength="2000"
                    placeholder="输入互动方案"
                  />
                  <el-tooltip v-if="canEditPlan" content="删除" placement="top">
                    <el-button text circle type="danger" :icon="Delete" :aria-label="`删除第 ${index + 1} 项互动方案`" @click="removeInteraction(index)" />
                  </el-tooltip>
                </div>
              </div>
              <StatePanel v-else type="empty" title="当前没有互动方案" description="可添加互动安排后再保存方案。" />
              <el-button v-if="canEditPlan" class="interaction-editor__add" plain :icon="Plus" @click="interactionPlan.push('')">
                添加互动安排
              </el-button>
            </section>

            <div v-if="actionError" class="generation-action-error generation-action-error--bar">
              <el-alert :title="actionError" type="error" show-icon :closable="false" />
              <el-button text type="primary" :icon="Refresh" @click="retryLastAction">重试</el-button>
            </div>

            <section class="generation-actions">
              <div class="generation-actions__status">
                <el-icon :class="{ 'is-ready': plan.confirmed }"><CircleCheck /></el-icon>
                <span>{{ plan.confirmed ? '方案已确认，可以生成教学内容' : '确认方案后才能生成教学内容' }}</span>
              </div>
              <div class="generation-actions__buttons">
                <el-button
                  v-if="!plan.confirmed"
                  :icon="DocumentChecked"
                  :loading="saving"
                  :disabled="!canEditPlan || !isDirty"
                  @click="savePlan"
                >
                  保存方案
                </el-button>
                <el-button
                  v-if="!plan.confirmed"
                  type="primary"
                  :icon="CircleCheck"
                  :loading="confirming"
                  :disabled="!canConfirmPlan"
                  @click="confirmPlan"
                >
                  确认方案
                </el-button>
                <el-button
                  v-else
                  type="primary"
                  :icon="MagicStick"
                  :loading="generating"
                  :disabled="!canGenerateContent"
                  @click="generateContent"
                >
                  生成内容
                </el-button>
              </div>
            </section>
          </template>
        </main>

        <aside class="generation-aside">
          <section class="generation-side-panel">
            <header>
              <span class="is-purple"><el-icon><Aim /></el-icon></span>
              <div>
                <h3>教学意图</h3>
                <p>当前方案的生成依据</p>
              </div>
            </header>
            <dl v-if="intentRows.length" class="generation-side-list">
              <div v-for="item in intentRows" :key="item.label">
                <dt>{{ item.label }}</dt>
                <dd>{{ item.value }}</dd>
              </div>
            </dl>
            <StatePanel v-else type="empty" title="暂无教学意图" description="请返回教学意图页完成确认。" />
          </section>

          <section class="generation-side-panel">
            <header>
              <span class="is-blue"><el-icon><SetUp /></el-icon></span>
              <div>
                <h3>生成条件</h3>
                <p>来自当前工作区状态</p>
              </div>
            </header>
            <dl class="generation-condition-list">
              <div>
                <dt>项目状态</dt>
                <dd>{{ projectStatusLabel }}</dd>
              </div>
              <div>
                <dt>方案状态</dt>
                <dd>{{ plan ? (plan.confirmed ? '已确认' : '待确认') : '待生成' }}</dd>
              </div>
              <div>
                <dt>已有成果</dt>
                <dd>{{ artifactCount }} 项</dd>
              </div>
            </dl>
            <div class="generation-capabilities">
              <div v-for="item in capabilityRows" :key="item.label" :class="{ 'is-enabled': item.enabled }">
                <el-icon><component :is="item.enabled ? CircleCheck : Clock" /></el-icon>
                <span>{{ item.label }}</span>
              </div>
            </div>
          </section>
        </aside>
      </div>
    </template>
  </section>
</template>

<script setup lang="ts">
import {
  confirmGenerationPlan,
  createGenerationPlan,
  generateArtifacts,
  getGenerationWorkspace,
  updateGenerationPlan,
  type GenerationPlan,
  type GenerationPlanPayload,
  type GenerationWorkspace,
  type PlanOutlineItem,
} from '@/api/generation';
import { getProjectWorkspaceOverview, type ProjectBrief } from '@/api/workspace';
import GenerationOutlineEditor from '@/components/generation/GenerationOutlineEditor.vue';
import ProjectContextHeader from '@/components/ProjectContextHeader.vue';
import ProjectWorkspaceNav from '@/components/ProjectWorkspaceNav.vue';
import StatePanel from '@/components/StatePanel.vue';
import UiStatusPill from '@/components/ui/UiStatusPill.vue';
import { useAiGatewayStatus } from '@/composables/useAiGatewayStatus';
import { formatDateTime } from '@/utils/presentation';
import {
  Aim,
  Back,
  ChatDotRound,
  CircleCheck,
  Clock,
  Delete,
  DocumentChecked,
  MagicStick,
  Plus,
  Refresh,
  SetUp,
  View,
} from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import { computed, onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';

type FailedAction = 'create' | 'save' | 'confirm' | 'generate';

const route = useRoute();
const router = useRouter();
const projectId = computed(() => Number(route.params.projectId));
const workspace = ref<GenerationWorkspace>();
const projectContext = ref<ProjectBrief>();
const plan = ref<GenerationPlan>();
const pptOutline = ref<PlanOutlineItem[]>([]);
const docOutline = ref<PlanOutlineItem[]>([]);
const interactionPlan = ref<string[]>([]);
const savedSnapshot = ref('');
const loading = ref(true);
const creating = ref(false);
const saving = ref(false);
const confirming = ref(false);
const generating = ref(false);
const workspaceError = ref('内容生成数据读取失败，请检查服务后重试。');
const actionError = ref('');
const lastFailedAction = ref<FailedAction>();

function openCopilot() {
  void router.push({
    name: 'ai-assistant',
    query: { projectId: String(projectId.value), focus: 'generation' },
  });
}
const {
  presentation: gatewayPresentation,
  refresh: loadGatewayStatus,
} = useAiGatewayStatus();

const artifactCount = computed(() => workspace.value?.artifacts?.length || 0);
const heroStatus = computed(() => {
  if (!plan.value) return '尚无生成方案';
  if (plan.value.confirmed) return `方案已确认 · ${artifactCount.value ? `已有 ${artifactCount.value} 项成果` : '等待生成内容'}`;
  return isDirty.value ? '方案有未保存修改' : '方案草稿已保存，等待确认';
});
const projectStatusLabel = computed(() => {
  const status = workspace.value?.projectStatus || '';
  const labels: Record<string, string> = {
    CREATED: '项目已创建',
    REQUIREMENT_CONFIRMED: '需求已确认',
    MATERIAL_READY: '资料已就绪',
    INTENT_CONFIRMED: '教学意图已确认',
    GENERATED: '内容已生成',
    FINALIZED: '项目已定稿',
  };
  return labels[status] || status || '未知';
});
const currentPayload = computed<GenerationPlanPayload>(() => ({
  pptOutline: pptOutline.value.map((item, index) => ({ ...item, order: index + 1 })),
  docOutline: docOutline.value.map((item, index) => ({ ...item, order: index + 1 })),
  interactionPlan: [...interactionPlan.value],
}));
const isDirty = computed(() => Boolean(plan.value) && JSON.stringify(currentPayload.value) !== savedSnapshot.value);
const isPlanValid = computed(() => (
  pptOutline.value.length > 0
  && docOutline.value.length > 0
  && interactionPlan.value.length > 0
  && [...pptOutline.value, ...docOutline.value].every((item) => item.title.trim() && item.description.trim())
  && interactionPlan.value.every((item) => item.trim())
));

const canCreatePlan = computed(() => !gatewayPresentation.value.unavailable && !plan.value && capability(
  ['canGeneratePlan', 'canCreatePlan', 'generatePlan', 'createPlan'],
  ['GENERATE_PLAN', 'CREATE_PLAN'],
  true,
));
const canEditPlan = computed(() => Boolean(plan.value && !plan.value.confirmed && capability(
  ['canEditPlan', 'canUpdatePlan', 'editPlan', 'updatePlan'],
  ['EDIT_PLAN', 'UPDATE_PLAN'],
  true,
)));
const canConfirmPlan = computed(() => Boolean(plan.value && !plan.value.confirmed && isPlanValid.value && capability(
  ['canConfirmPlan', 'confirmPlan'],
  ['CONFIRM_PLAN'],
  true,
)));
const canGenerateContent = computed(() => Boolean(!gatewayPresentation.value.unavailable && plan.value?.confirmed && capability(
  ['canGenerate', 'canGenerateArtifacts', 'canGenerateContent', 'generateArtifacts', 'generateContent'],
  ['GENERATE_ARTIFACTS', 'GENERATE_CONTENT'],
  true,
)));

const intentRows = computed(() => {
  const intent = workspace.value?.teachingIntent;
  if (!intent) return [];
  const rows = [
    { label: '生成目标', value: textValue(intent.generationGoal) || listValue(intent.generationGoals) },
    { label: '内容依据', value: textValue(intent.contentBasis) || textValue(intent.primaryBasis) },
    { label: '教学方式', value: textValue(intent.teachingApproach) || textValue(intent.teachingFormat) },
    { label: '互动方式', value: textValue(intent.interactionMode) },
    { label: '输出类型', value: listValue(intent.outputTypes) },
  ];
  return rows.filter((item) => item.value);
});
const capabilityRows = computed(() => [
  { label: '生成方案', enabled: Boolean(plan.value) || canCreatePlan.value },
  { label: '编辑方案', enabled: canEditPlan.value },
  { label: '确认方案', enabled: Boolean(plan.value?.confirmed) || canConfirmPlan.value },
  { label: '生成内容', enabled: canGenerateContent.value },
]);

function textValue(value: unknown) {
  return typeof value === 'string' ? value.trim() : '';
}

function listValue(value: unknown) {
  return Array.isArray(value) ? value.filter((item): item is string => typeof item === 'string' && Boolean(item.trim())).join('、') : '';
}

function capability(objectKeys: string[], arrayKeys: string[], fallback: boolean) {
  const capabilities = workspace.value?.capabilities;
  if (!capabilities) return fallback;
  if (Array.isArray(capabilities)) {
    if (!capabilities.length) return false;
    const normalized = capabilities.map((item) => item.toUpperCase());
    return arrayKeys.some((key) => normalized.includes(key));
  }
  const entries = Object.entries(capabilities);
  for (const key of [...objectKeys, ...arrayKeys]) {
    const match = entries.find(([entryKey]) => entryKey.toUpperCase() === key.toUpperCase());
    if (match) return Boolean(match[1]);
  }
  return fallback;
}

function syncPlan(nextPlan?: GenerationPlan | null) {
  if (!nextPlan) {
    plan.value = undefined;
    pptOutline.value = [];
    docOutline.value = [];
    interactionPlan.value = [];
    savedSnapshot.value = '';
    return;
  }
  plan.value = nextPlan;
  pptOutline.value = nextPlan.pptOutline.map((item, index) => ({ ...item, order: index + 1 }));
  docOutline.value = nextPlan.docOutline.map((item, index) => ({ ...item, order: index + 1 }));
  interactionPlan.value = [...nextPlan.interactionPlan];
  savedSnapshot.value = JSON.stringify(currentPayload.value);
}

async function loadWorkspace() {
  loading.value = true;
  workspaceError.value = '';
  actionError.value = '';
  try {
    const [result, overview] = await Promise.all([
      getGenerationWorkspace(projectId.value),
      getProjectWorkspaceOverview(projectId.value),
    ]);
    workspace.value = result;
    projectContext.value = overview.project;
    syncPlan(result.latestPlan);
  } catch (error) {
    workspace.value = undefined;
    projectContext.value = undefined;
    workspaceError.value = resolveError(error, '内容生成数据读取失败，请检查服务后重试。');
  } finally {
    loading.value = false;
  }
}

async function createPlan() {
  if (gatewayPresentation.value.unavailable) {
    setActionError('create', undefined, 'AI 工作流当前不可用，请先检查 Kimi 或 Mock 配置。');
    return;
  }
  creating.value = true;
  clearActionError();
  try {
    const result = await createGenerationPlan(projectId.value);
    syncPlan(result);
    if (workspace.value) workspace.value.latestPlan = result;
    ElMessage.success('生成方案已创建');
  } catch (error) {
    setActionError('create', error, '方案生成失败，请稍后重试。');
  } finally {
    await loadGatewayStatus();
    creating.value = false;
  }
}

function validatePlan() {
  if (isPlanValid.value) return true;
  ElMessage.warning('PPT、教案和互动方案均需保留至少一项，并完整填写标题与描述');
  return false;
}

async function persistPlan(showSuccess = true) {
  if (!plan.value || !validatePlan()) return false;
  saving.value = true;
  clearActionError();
  try {
    const result = await updateGenerationPlan(projectId.value, plan.value.id, currentPayload.value);
    syncPlan(result);
    if (workspace.value) workspace.value.latestPlan = result;
    if (showSuccess) ElMessage.success('生成方案已保存');
    return true;
  } catch (error) {
    setActionError('save', error, '方案保存失败，请稍后重试。');
    return false;
  } finally {
    saving.value = false;
  }
}

async function savePlan() {
  await persistPlan(true);
}

async function confirmPlan() {
  if (!plan.value || !validatePlan()) return;
  confirming.value = true;
  clearActionError();
  try {
    if (isDirty.value && !(await persistPlan(false))) return;
    if (!plan.value) return;
    const result = await confirmGenerationPlan(projectId.value, plan.value.id);
    syncPlan(result);
    if (workspace.value) workspace.value.latestPlan = result;
    ElMessage.success('生成方案已确认');
  } catch (error) {
    setActionError('confirm', error, '方案确认失败，请稍后重试。');
  } finally {
    confirming.value = false;
  }
}

async function generateContent() {
  if (!plan.value?.confirmed || gatewayPresentation.value.unavailable) {
    if (gatewayPresentation.value.unavailable) setActionError('generate', undefined, 'AI 工作流当前不可用，请先检查 Kimi 或 Mock 配置。');
    return;
  }
  generating.value = true;
  clearActionError();
  try {
    const artifacts = await generateArtifacts(projectId.value, plan.value.id);
    if (workspace.value) workspace.value.artifacts = artifacts || [];
    ElMessage.success('教学内容已生成');
    await router.push(`/projects/${projectId.value}/preview`);
  } catch (error) {
    setActionError('generate', error, '内容生成失败，请稍后重试。');
  } finally {
    await loadGatewayStatus();
    generating.value = false;
  }
}

function removeInteraction(index: number) {
  interactionPlan.value.splice(index, 1);
}

function clearActionError() {
  actionError.value = '';
  lastFailedAction.value = undefined;
}

function setActionError(action: FailedAction, error: unknown, fallback: string) {
  lastFailedAction.value = action;
  actionError.value = resolveError(error, fallback);
}

function retryLastAction() {
  const retryActions: Record<FailedAction, () => void> = {
    create: () => { void createPlan(); },
    save: () => { void savePlan(); },
    confirm: () => { void confirmPlan(); },
    generate: () => { void generateContent(); },
  };
  if (lastFailedAction.value) retryActions[lastFailedAction.value]();
}

function resolveError(error: unknown, fallback: string) {
  const message = (error as { response?: { data?: { message?: string } } }).response?.data?.message;
  return message && !/(Exception|java\.|Axios)/i.test(message) ? message : fallback;
}

onMounted(() => { void Promise.all([loadWorkspace(), loadGatewayStatus()]); });
</script>

<style scoped>
.generation-page {
  width: 100%;
  max-width: 1440px;
  margin: 0 auto;
}

.generation-hero,
.plan-toolbar,
.interaction-editor,
.generation-empty,
.generation-actions,
.generation-side-panel {
  border: 1px solid var(--ui-border);
  border-radius: 8px;
  background: var(--ui-panel);
  box-shadow: var(--shadow-panel);
}

.generation-hero {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  padding: 18px 20px;
  margin-bottom: 16px;
}

.generation-hero__main,
.generation-hero__actions,
.plan-toolbar__title,
.interaction-editor__header,
.interaction-editor__title,
.generation-side-panel header,
.generation-actions,
.generation-actions__status,
.generation-actions__buttons,
.generation-action-error {
  display: flex;
  align-items: center;
}

.generation-hero__main {
  min-width: 0;
  gap: 14px;
}

.generation-hero__main > div {
  min-width: 0;
}

.generation-hero__icon {
  display: grid;
  width: 48px;
  height: 48px;
  flex: 0 0 48px;
  place-items: center;
  border-radius: 8px;
  background: var(--ui-primary-soft);
  color: var(--ui-primary);
  font-size: 24px;
}

.generation-hero__eyebrow {
  display: block;
  max-width: min(620px, 60vw);
  overflow: hidden;
  color: var(--ui-muted);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.generation-hero h2,
.generation-hero p,
.plan-toolbar h3,
.plan-toolbar p,
.interaction-editor h3,
.interaction-editor p,
.generation-empty h3,
.generation-empty p,
.generation-side-panel h3,
.generation-side-panel p {
  margin: 0;
}

.generation-hero h2 {
  margin-top: 2px;
  font-size: 21px;
}

.generation-hero p,
.plan-toolbar p,
.interaction-editor p,
.generation-side-panel p {
  margin-top: 3px;
  color: var(--ui-muted);
  font-size: 12px;
}

.generation-hero__actions {
  flex: 0 0 auto;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 10px;
}

.generation-layout {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(290px, 340px);
  align-items: start;
  gap: 16px;
}

.generation-main,
.generation-aside {
  display: grid;
  min-width: 0;
  gap: 14px;
}

.generation-empty {
  display: grid;
  min-height: 440px;
  align-content: center;
  justify-items: center;
  padding: 32px;
  text-align: center;
}

.generation-empty__icon {
  display: grid;
  width: 64px;
  height: 64px;
  place-items: center;
  border-radius: 8px;
  background: var(--ui-primary-soft);
  color: var(--ui-primary);
  font-size: 30px;
}

.generation-empty h3 {
  margin-top: 16px;
  font-size: 18px;
}

.generation-empty p {
  max-width: 420px;
  margin-top: 6px;
  color: var(--ui-muted);
}

.generation-empty > .el-button {
  margin-top: 20px;
}

.plan-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 14px 18px;
}

.plan-toolbar__title {
  flex-wrap: wrap;
  gap: 10px;
}

.plan-toolbar__provider {
  color: var(--ui-primary);
  font-size: 12px;
  font-weight: 700;
  white-space: nowrap;
}

.interaction-editor {
  min-width: 0;
  padding: 18px;
}

.interaction-editor__header {
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 8px;
}

.interaction-editor__title {
  min-width: 0;
  gap: 12px;
}

.interaction-editor__title > span {
  display: grid;
  width: 40px;
  height: 40px;
  flex: 0 0 40px;
  place-items: center;
  border-radius: 8px;
  background: #e9f8f0;
  color: var(--ui-success);
  font-size: 20px;
}

.interaction-editor__list {
  border-top: 1px solid var(--ui-border);
}

.interaction-editor__row {
  display: grid;
  grid-template-columns: 28px minmax(0, 1fr) 36px;
  align-items: center;
  gap: 10px;
  padding: 10px 0;
  border-bottom: 1px solid var(--ui-border);
}

.interaction-editor__row > span {
  display: grid;
  width: 28px;
  height: 28px;
  place-items: center;
  border-radius: 50%;
  background: var(--ui-panel-soft);
  color: var(--ui-muted);
  font-size: 12px;
  font-weight: 700;
}

.interaction-editor__row :deep(.el-button) {
  width: 32px;
  min-height: 32px;
  height: 32px;
}

.interaction-editor__add {
  width: 100%;
  margin-top: 14px;
}

.generation-side-panel {
  min-width: 0;
  padding: 16px;
}

.generation-side-panel header {
  gap: 10px;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--ui-border);
}

.generation-side-panel header > span {
  display: grid;
  width: 36px;
  height: 36px;
  flex: 0 0 36px;
  place-items: center;
  border-radius: 8px;
}

.generation-side-panel header > span.is-purple {
  background: var(--ui-primary-soft);
  color: var(--ui-primary);
}

.generation-side-panel header > span.is-blue {
  background: #edf4ff;
  color: var(--ui-info);
}

.generation-side-list,
.generation-condition-list {
  margin: 0;
}

.generation-side-list > div,
.generation-condition-list > div {
  display: grid;
  gap: 4px;
  padding: 11px 0;
  border-bottom: 1px solid var(--ui-border);
}

.generation-condition-list > div {
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: start;
  gap: 12px;
}

.generation-side-list dt,
.generation-condition-list dt,
.generation-side-list dd,
.generation-condition-list dd {
  min-width: 0;
  margin: 0;
  overflow-wrap: anywhere;
}

.generation-side-list dt,
.generation-condition-list dt {
  color: var(--ui-faint);
  font-size: 11px;
}

.generation-side-list dd,
.generation-condition-list dd {
  color: var(--ui-text);
  font-size: 13px;
  line-height: 1.55;
}

.generation-condition-list dd {
  text-align: right;
}

.generation-capabilities {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
  margin-top: 14px;
}

.generation-capabilities > div {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
  padding: 8px;
  border-radius: 6px;
  background: var(--ui-panel-soft);
  color: var(--ui-faint);
  font-size: 11px;
}

.generation-capabilities > div.is-enabled {
  background: #e9f8f0;
  color: #168d52;
}

.generation-capabilities span {
  min-width: 0;
  overflow-wrap: anywhere;
}

.generation-actions {
  justify-content: space-between;
  gap: 16px;
  padding: 14px 16px;
}

.generation-actions__status {
  min-width: 0;
  gap: 8px;
  color: var(--ui-muted);
  font-size: 12px;
}

.generation-actions__status .is-ready {
  color: var(--ui-success);
}

.generation-actions__buttons {
  flex: 0 0 auto;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 10px;
}

.generation-action-error {
  justify-content: center;
  gap: 8px;
  width: min(100%, 620px);
  margin-top: 18px;
}

.generation-action-error--bar {
  width: 100%;
  margin-top: 0;
}

.generation-action-error :deep(.el-alert) {
  min-width: 0;
}

@media (max-width: 1080px) {
  .generation-layout {
    grid-template-columns: 1fr;
  }

  .generation-aside {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 720px) {
  .generation-hero {
    align-items: flex-start;
    flex-direction: column;
    padding: 16px;
  }

  .generation-hero__eyebrow {
    max-width: calc(100vw - 120px);
  }

  .generation-hero__actions,
  .generation-hero__actions .el-button {
    width: 100%;
  }

  .generation-hero__actions :deep(.ui-status-pill) {
    width: auto;
  }

  .generation-aside {
    grid-template-columns: 1fr;
  }

  .generation-actions {
    align-items: stretch;
    flex-direction: column;
  }

  .generation-actions__buttons,
  .generation-actions__buttons .el-button {
    width: 100%;
  }

  .generation-actions__buttons .el-button + .el-button {
    margin-left: 0;
  }

  .generation-action-error {
    align-items: stretch;
    flex-direction: column;
  }
}

@media (max-width: 480px) {
  .generation-hero__icon {
    width: 42px;
    height: 42px;
    flex-basis: 42px;
  }

  .generation-capabilities {
    grid-template-columns: 1fr;
  }

  .interaction-editor {
    padding: 14px;
  }
}
</style>
