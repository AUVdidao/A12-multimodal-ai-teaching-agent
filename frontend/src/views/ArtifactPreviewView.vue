<template>
  <section class="preview-page">
    <StatePanel v-if="loading" type="loading" title="正在加载教学成果" description="正在读取成果列表与预览内容。" />

    <StatePanel
      v-else-if="!workspace && !artifactListLoaded"
      type="error"
      title="教学成果加载失败"
      :description="artifactListError || workspaceError"
    >
      <template #action>
        <el-button type="primary" :icon="Refresh" @click="loadPreview">重新加载</el-button>
      </template>
    </StatePanel>

    <template v-else>
      <ProjectContextHeader v-if="projectContext" :project="projectContext" />
      <ProjectWorkspaceNav :project-id="projectId" />
      <AiProviderStatusStrip
        :status="gatewayStatus"
        :loading="gatewayStatusLoading"
        :error="gatewayStatusError"
        compact
        @refresh="loadGatewayStatus"
      />

      <header class="preview-hero">
        <div class="preview-hero__main">
          <span><el-icon><View /></el-icon></span>
          <div>
            <small>{{ workspace?.projectName || '教学成果' }}</small>
            <h2>成果预览</h2>
            <p>{{ artifacts.length ? `共 ${artifacts.length} 项生成成果` : '尚未生成教学成果' }}</p>
          </div>
        </div>
        <div class="preview-hero__actions">
          <UiStatusPill v-if="workspace" :label="providerLabel" tone="purple" dot />
          <el-button :icon="Back" @click="router.push(`/projects/${projectId}/plan`)">返回内容生成</el-button>
        </div>
      </header>

      <div v-if="workspaceError" class="preview-notice">
        <el-alert :title="workspaceError" type="warning" show-icon :closable="false" />
        <el-button text type="primary" :icon="Refresh" @click="loadWorkspace">重试</el-button>
      </div>

      <div v-if="artifactListError" class="preview-notice">
        <el-alert :title="artifactListError" type="error" show-icon :closable="false" />
        <el-button text type="primary" :icon="Refresh" @click="loadArtifactList">重试</el-button>
      </div>

      <section v-if="!artifacts.length && artifactListLoaded" class="preview-empty">
        <span><el-icon><Files /></el-icon></span>
        <h3>尚未生成教学成果</h3>
        <p>确认生成方案并完成内容生成后，成果会显示在这里。</p>
        <el-button type="primary" :icon="Back" @click="router.push(`/projects/${projectId}/plan`)">返回内容生成</el-button>
      </section>

      <StatePanel
        v-else-if="!artifacts.length"
        type="error"
        title="成果列表暂时不可用"
        :description="artifactListError"
      >
        <template #action>
          <el-button type="primary" :icon="Refresh" @click="loadArtifactList">重新加载</el-button>
        </template>
      </StatePanel>

      <template v-else>
        <section class="artifact-panel">
          <header class="artifact-panel__header">
            <div>
              <span>成果内容</span>
              <h3>{{ activeSummary?.title || activeTabLabel }}</h3>
            </div>
            <dl v-if="activeSummary" class="artifact-meta">
              <div><dt>成果版本</dt><dd>v{{ activeSummary.versionNumber }}</dd></div>
              <div><dt>结构版本</dt><dd>{{ activeSummary.schemaVersion }}</dd></div>
              <div><dt>生成时间</dt><dd>{{ formatDateTime(activeSummary.createdAt) }}</dd></div>
            </dl>
          </header>

          <el-tabs v-model="activeType" class="artifact-tabs">
            <el-tab-pane v-for="tab in artifactTabs" :key="tab.type" :name="tab.type">
              <template #label>
                <span class="artifact-tab-label">
                  <el-icon><component :is="tab.icon" /></el-icon>
                  {{ tab.label }}
                  <small>{{ artifactCounts[tab.type] }}</small>
                </span>
              </template>

              <StatePanel
                v-if="!artifactForType(tab.type)"
                type="empty"
                :title="`暂无${tab.label}成果`"
                description="本次生成未返回此类型成果。"
              />

              <div v-else class="artifact-preview-body" v-loading="activeDetailLoading">
                <div v-if="activeDetailError" class="preview-notice preview-notice--detail">
                  <el-alert :title="activeDetailError" type="error" show-icon :closable="false" />
                  <el-button text type="primary" :icon="Refresh" @click="loadArtifactDetail(tab.type, true)">重试</el-button>
                </div>

                <StatePanel
                  v-if="activeDetailLoading && !hasRenderableContent(activeArtifact)"
                  type="loading"
                  title="正在加载成果详情"
                  description="正在读取完整预览内容。"
                />
                <StatePanel
                  v-else-if="!activeArtifact || !hasRenderableContent(activeArtifact)"
                  type="empty"
                  title="成果详情没有可预览内容"
                  description="请返回生成页重新生成内容。"
                />
                <PptArtifactPreview v-else-if="tab.type === 'PPT'" :artifact="activeArtifact" />
                <DocxArtifactPreview v-else-if="tab.type === 'DOCX'" :artifact="activeArtifact" />
                <InteractionArtifactPreview v-else :artifact="activeArtifact" />
              </div>
            </el-tab-pane>
          </el-tabs>
        </section>

        <section class="revision-panel">
          <div class="revision-panel__heading">
            <span><el-icon><EditPen /></el-icon></span>
            <div>
              <h3>版本修改</h3>
              <p>仅修改当前成果并生成新的非定稿版本</p>
            </div>
            <UiStatusPill :label="providerLabel" tone="purple" dot />
          </div>
          <el-alert
            v-if="revisionError"
            :title="revisionError"
            type="error"
            show-icon
            :closable="false"
          />
          <el-alert
            v-if="revisionSuccess"
            :title="revisionSuccess"
            type="success"
            show-icon
            :closable="false"
          />
          <el-input
            v-model="revisionInstruction"
            type="textarea"
            :rows="3"
            maxlength="4000"
            show-word-limit
            :disabled="revisionSubmitting || !activeSummary || gatewayPresentation.unavailable"
            placeholder="请输入需要调整的教学内容或表达方式"
            aria-label="版本修改意见"
          />
          <div class="revision-panel__footer">
            <span>本次成果提供方：{{ providerLabel }}<template v-if="workspace?.provider?.toUpperCase().includes('MOCK')">（Mock，不代表真实模型）</template></span>
            <el-button
              type="primary"
              :icon="EditPen"
              :loading="revisionSubmitting"
              :disabled="!activeSummary || !revisionInstruction.trim() || gatewayPresentation.unavailable"
              @click="submitRevision"
            >提交修改</el-button>
          </div>
        </section>
      </template>
    </template>
  </section>
</template>

<script setup lang="ts">
import {
  getArtifact,
  getArtifacts,
  getGenerationWorkspace,
  type Artifact,
  type ArtifactType,
  type GenerationWorkspace,
  reviseArtifact,
} from '@/api/generation';
import { getProjectWorkspaceOverview, type ProjectBrief } from '@/api/workspace';
import AiProviderStatusStrip from '@/components/ai/AiProviderStatusStrip.vue';
import DocxArtifactPreview from '@/components/generation/DocxArtifactPreview.vue';
import InteractionArtifactPreview from '@/components/generation/InteractionArtifactPreview.vue';
import PptArtifactPreview from '@/components/generation/PptArtifactPreview.vue';
import ProjectContextHeader from '@/components/ProjectContextHeader.vue';
import ProjectWorkspaceNav from '@/components/ProjectWorkspaceNav.vue';
import StatePanel from '@/components/StatePanel.vue';
import UiStatusPill from '@/components/ui/UiStatusPill.vue';
import { useAiGatewayStatus } from '@/composables/useAiGatewayStatus';
import { formatDateTime } from '@/utils/presentation';
import { ElMessage } from 'element-plus';
import { Back, ChatDotRound, DataBoard, Document, EditPen, Files, Refresh, View } from '@element-plus/icons-vue';
import { computed, onMounted, reactive, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';

const route = useRoute();
const router = useRouter();
const projectId = computed(() => Number(route.params.projectId));
const workspace = ref<GenerationWorkspace>();
const projectContext = ref<ProjectBrief>();
const artifacts = ref<Artifact[]>([]);
const loading = ref(true);
const artifactListLoaded = ref(false);
const workspaceError = ref('');
const artifactListError = ref('');
const activeType = ref<ArtifactType>('PPT');
const details = reactive<Record<number, Artifact>>({});
const detailLoading = reactive<Record<number, boolean>>({});
const detailErrors = reactive<Record<number, string>>({});
const revisionInstruction = ref('');
const revisionSubmitting = ref(false);
const revisionError = ref('');
const revisionSuccess = ref('');
const {
  status: gatewayStatus,
  loading: gatewayStatusLoading,
  error: gatewayStatusError,
  presentation: gatewayPresentation,
  refresh: loadGatewayStatus,
} = useAiGatewayStatus();

const artifactTabs = [
  { type: 'PPT' as const, label: 'PPT 课件', icon: DataBoard },
  { type: 'DOCX' as const, label: '教案', icon: Document },
  { type: 'INTERACTION' as const, label: '互动内容', icon: ChatDotRound },
];
const artifactsByType = computed(() => {
  const result: Record<ArtifactType, Artifact[]> = { PPT: [], DOCX: [], INTERACTION: [] };
  artifacts.value.forEach((artifact) => {
    if (result[artifact.type]) result[artifact.type].push(artifact);
  });
  Object.values(result).forEach((items) => items.sort((left, right) => (
    new Date(right.createdAt).getTime() - new Date(left.createdAt).getTime() || right.id - left.id
  )));
  return result;
});
const artifactCounts = computed<Record<ArtifactType, number>>(() => ({
  PPT: artifactsByType.value.PPT.length,
  DOCX: artifactsByType.value.DOCX.length,
  INTERACTION: artifactsByType.value.INTERACTION.length,
}));
const activeSummary = computed(() => artifactForType(activeType.value));
const activeArtifact = computed(() => {
  const summary = activeSummary.value;
  return summary ? details[summary.id] || summary : undefined;
});
const activeDetailLoading = computed(() => Boolean(activeSummary.value && detailLoading[activeSummary.value.id]));
const activeDetailError = computed(() => activeSummary.value ? detailErrors[activeSummary.value.id] || '' : '');
const activeTabLabel = computed(() => artifactTabs.find((tab) => tab.type === activeType.value)?.label || '教学成果');
const providerLabel = computed(() => {
  const provider = workspace.value?.provider || '';
  return provider.toUpperCase().includes('MOCK') ? 'Mock AI' : provider || 'AI';
});

function artifactForType(type: ArtifactType) {
  return artifactsByType.value[type][0];
}

function hasRenderableContent(artifact?: Artifact) {
  if (!artifact?.content) return false;
  return Array.isArray(artifact.content)
    ? artifact.content.length > 0
    : Object.keys(artifact.content).length > 0;
}

function chooseInitialType() {
  if (artifactForType(activeType.value)) return;
  const firstAvailable = artifactTabs.find((tab) => artifactForType(tab.type));
  if (firstAvailable) activeType.value = firstAvailable.type;
}

async function loadWorkspace() {
  workspaceError.value = '';
  try {
    const [generationWorkspace, overview] = await Promise.all([
      getGenerationWorkspace(projectId.value),
      getProjectWorkspaceOverview(projectId.value),
    ]);
    workspace.value = generationWorkspace;
    projectContext.value = overview.project;
  } catch (error) {
    workspace.value = undefined;
    projectContext.value = undefined;
    workspaceError.value = resolveError(error, '项目信息读取失败，成果预览仍可继续使用。');
  }
}

async function loadArtifactList() {
  artifactListError.value = '';
  artifactListLoaded.value = false;
  try {
    artifacts.value = (await getArtifacts(projectId.value)) || [];
    artifactListLoaded.value = true;
    chooseInitialType();
    await loadArtifactDetail(activeType.value);
  } catch (error) {
    const fallback = workspace.value?.artifacts || [];
    artifacts.value = fallback;
    artifactListLoaded.value = fallback.length > 0;
    artifactListError.value = resolveError(error, fallback.length
      ? '成果列表刷新失败，当前展示工作区中的成果。'
      : '成果列表读取失败，请稍后重试。');
    chooseInitialType();
    if (fallback.length) await loadArtifactDetail(activeType.value);
  }
}

async function submitRevision() {
  const source = activeSummary.value;
  const instruction = revisionInstruction.value.trim();
  if (gatewayPresentation.value.unavailable) {
    revisionError.value = 'AI 工作流当前不可用，请先检查 Dify 或 Mock 配置。';
    return;
  }
  if (!source || !instruction) {
    ElMessage.warning('请选择已有成果并填写修改说明');
    return;
  }

  revisionSubmitting.value = true;
  revisionError.value = '';
  revisionSuccess.value = '';
  try {
    const result = await reviseArtifact(projectId.value, source.id, instruction);
    revisionSuccess.value = `已创建 v${result.version.versionNumber}，${result.changeSummary}`
      + (result.mockProvider ? ' 当前使用 Mock provider。' : '');
    revisionInstruction.value = '';
    await loadArtifactList();
  } catch (error) {
    revisionError.value = resolveError(error, '成果修改失败，请检查当前版本状态后重试。');
    ElMessage.error(revisionError.value);
  } finally {
    await loadGatewayStatus();
    revisionSubmitting.value = false;
  }
}

async function loadArtifactDetail(type: ArtifactType, force = false) {
  const summary = artifactForType(type);
  if (!summary || detailLoading[summary.id] || (!force && details[summary.id])) return;
  detailLoading[summary.id] = true;
  detailErrors[summary.id] = '';
  try {
    details[summary.id] = await getArtifact(projectId.value, summary.id);
  } catch (error) {
    detailErrors[summary.id] = resolveError(error, '成果详情读取失败，请重试。');
  } finally {
    detailLoading[summary.id] = false;
  }
}

async function loadPreview() {
  loading.value = true;
  await Promise.all([loadWorkspace(), loadGatewayStatus()]);
  await loadArtifactList();
  loading.value = false;
}

function resolveError(error: unknown, fallback: string) {
  const message = (error as { response?: { data?: { message?: string } } }).response?.data?.message;
  return message && !/(Exception|java\.|Axios)/i.test(message) ? message : fallback;
}

watch(activeType, (type) => { void loadArtifactDetail(type); });
onMounted(loadPreview);
</script>

<style scoped>
.preview-page {
  width: 100%;
  max-width: 1440px;
  margin: 0 auto;
}

.preview-hero,
.artifact-panel,
.preview-empty,
.revision-panel {
  border: 1px solid var(--ui-border);
  border-radius: 8px;
  background: var(--ui-panel);
  box-shadow: var(--shadow-panel);
}

.preview-hero,
.preview-hero__main,
.preview-hero__actions,
.artifact-panel__header,
.artifact-meta,
.revision-panel__heading,
.revision-panel__footer,
.preview-notice {
  display: flex;
  align-items: center;
}

.preview-hero {
  justify-content: space-between;
  gap: 20px;
  padding: 18px 20px;
  margin-bottom: 16px;
}

.preview-hero__main {
  min-width: 0;
  gap: 14px;
}

.preview-hero__main > span {
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

.preview-hero__main > div {
  min-width: 0;
}

.preview-hero small {
  display: block;
  max-width: min(620px, 60vw);
  overflow: hidden;
  color: var(--ui-muted);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.preview-hero h2,
.preview-hero p,
.artifact-panel__header h3,
.preview-empty h3,
.preview-empty p,
.revision-panel h3,
.revision-panel p {
  margin: 0;
}

.preview-hero h2 {
  margin-top: 2px;
  font-size: 21px;
}

.preview-hero p {
  margin-top: 3px;
  color: var(--ui-muted);
  font-size: 12px;
}

.preview-hero__actions {
  flex: 0 0 auto;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 10px;
}

.preview-notice {
  align-items: stretch;
  gap: 8px;
  margin-bottom: 12px;
}

.preview-notice :deep(.el-alert) {
  min-width: 0;
  flex: 1;
}

.preview-empty {
  display: grid;
  min-height: 460px;
  align-content: center;
  justify-items: center;
  padding: 32px;
  text-align: center;
}

.preview-empty > span {
  display: grid;
  width: 64px;
  height: 64px;
  place-items: center;
  border-radius: 8px;
  background: var(--ui-primary-soft);
  color: var(--ui-primary);
  font-size: 28px;
}

.preview-empty h3 {
  margin-top: 16px;
  font-size: 18px;
}

.preview-empty p {
  margin-top: 6px;
  color: var(--ui-muted);
}

.preview-empty .el-button {
  margin-top: 20px;
}

.artifact-panel {
  min-width: 0;
  overflow: hidden;
}

.artifact-panel__header {
  justify-content: space-between;
  gap: 18px;
  min-height: 74px;
  padding: 14px 18px;
  border-bottom: 1px solid var(--ui-border);
}

.artifact-panel__header > div {
  min-width: 0;
}

.artifact-panel__header > div > span {
  color: var(--ui-faint);
  font-size: 11px;
}

.artifact-panel__header h3 {
  max-width: min(640px, 48vw);
  margin-top: 3px;
  overflow: hidden;
  font-size: 17px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.artifact-meta {
  flex: 0 0 auto;
  gap: 18px;
  margin: 0;
}

.artifact-meta > div {
  display: grid;
  gap: 2px;
}

.artifact-meta dt,
.artifact-meta dd {
  margin: 0;
  text-align: right;
  white-space: nowrap;
}

.artifact-meta dt {
  color: var(--ui-faint);
  font-size: 10px;
}

.artifact-meta dd {
  color: var(--ui-text);
  font-size: 12px;
}

.artifact-tabs :deep(.el-tabs__header) {
  padding: 0 18px;
  margin: 0;
  background: var(--ui-panel-soft);
}

.artifact-tabs :deep(.el-tabs__nav-wrap::after) {
  height: 1px;
  background: var(--ui-border);
}

.artifact-tabs :deep(.el-tabs__item) {
  height: 50px;
}

.artifact-tabs :deep(.el-tabs__content) {
  padding: 18px;
  overflow: visible;
}

.artifact-tab-label {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  white-space: nowrap;
}

.artifact-tab-label small {
  display: grid;
  min-width: 20px;
  height: 20px;
  place-items: center;
  border-radius: 6px;
  background: #edf0f5;
  color: var(--ui-muted);
  font-size: 10px;
}

.artifact-preview-body {
  min-width: 0;
  min-height: 260px;
}

.preview-notice--detail {
  margin-bottom: 14px;
}

.revision-panel {
  display: grid;
  gap: 14px;
  padding: 16px 18px;
  margin-top: 16px;
}

.revision-panel__heading {
  min-width: 0;
  gap: 10px;
}

.revision-panel__heading > span {
  display: grid;
  width: 36px;
  height: 36px;
  flex: 0 0 36px;
  place-items: center;
  border-radius: 8px;
  background: #f1f4f8;
  color: var(--ui-muted);
}

.revision-panel__heading > div {
  min-width: 0;
  flex: 1;
}

.revision-panel h3 {
  font-size: 15px;
}

.revision-panel p {
  margin-top: 2px;
  color: var(--ui-muted);
  font-size: 12px;
}

.revision-panel__footer {
  justify-content: space-between;
  gap: 12px;
}

.revision-panel__footer > span {
  display: inline-flex;
  align-items: center;
  min-width: 0;
  gap: 6px;
  color: var(--ui-faint);
  font-size: 12px;
  overflow-wrap: anywhere;
}

@media (max-width: 800px) {
  .artifact-panel__header {
    align-items: flex-start;
    flex-direction: column;
  }

  .artifact-panel__header h3 {
    max-width: calc(100vw - 70px);
  }

  .artifact-meta {
    width: 100%;
    justify-content: space-between;
  }

  .artifact-meta dt,
  .artifact-meta dd {
    text-align: left;
  }
}

@media (max-width: 640px) {
  .preview-hero {
    align-items: flex-start;
    flex-direction: column;
    padding: 16px;
  }

  .preview-hero small {
    max-width: calc(100vw - 120px);
  }

  .preview-hero__actions,
  .preview-hero__actions .el-button {
    width: 100%;
  }

  .preview-hero__actions :deep(.ui-status-pill) {
    width: auto;
  }

  .preview-notice {
    flex-direction: column;
  }

  .artifact-tabs :deep(.el-tabs__header) {
    padding: 0 12px;
  }

  .artifact-tabs :deep(.el-tabs__nav-prev),
  .artifact-tabs :deep(.el-tabs__nav-next) {
    display: none;
  }

  .artifact-tabs :deep(.el-tabs__nav-scroll) {
    overflow: visible;
  }

  .artifact-tabs :deep(.el-tabs__nav) {
    display: grid;
    width: 100%;
    float: none;
    grid-template-columns: repeat(3, minmax(0, 1fr));
    transform: none !important;
  }

  .artifact-tabs :deep(.el-tabs__item) {
    min-width: 0;
    justify-content: center;
    padding: 0 4px !important;
  }

  .artifact-tab-label {
    min-width: 0;
    gap: 4px;
    font-size: 12px;
  }

  .artifact-tab-label small {
    min-width: 18px;
    height: 18px;
  }

  .artifact-tabs :deep(.el-tabs__content) {
    padding: 12px;
  }

  .artifact-panel__header h3 {
    max-width: none;
    overflow: visible;
    overflow-wrap: anywhere;
    text-overflow: clip;
    white-space: normal;
  }

  .artifact-meta {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 10px;
  }

  .artifact-meta > div:last-child {
    grid-column: 1 / -1;
  }

  .revision-panel__footer {
    align-items: stretch;
    flex-direction: column;
  }

  .revision-panel__footer .el-button {
    width: 100%;
  }
}
</style>
