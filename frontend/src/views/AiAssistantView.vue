<template>
  <section class="page assistant-page">
    <PageHeader
      eyebrow="AI 工作流助手"
      title="把项目推进到下一步"
      description="选择一个真实教学项目，调用当前 AI 工作流检查需求或生成方案建议。这里不是通用聊天，结果只来自项目接口。"
    >
      <template #actions>
        <el-tooltip content="刷新项目与 AI provider 状态" placement="bottom">
          <el-button circle :icon="Refresh" :loading="projectsLoading || statusLoading" aria-label="刷新项目与 AI 状态" @click="loadPage" />
        </el-tooltip>
      </template>
    </PageHeader>

    <StatePanel v-if="projectsLoading && projects.length === 0" type="loading" title="正在读取 AI 工作流入口" description="正在读取你的教学项目和当前 provider 状态。" />
    <StatePanel v-else-if="projectsError && projects.length === 0" type="error" title="项目读取失败" :description="projectsError">
      <template #action><el-button type="primary" :icon="Refresh" @click="loadPage">重新加载</el-button></template>
    </StatePanel>
    <StatePanel v-else-if="projects.length === 0" type="empty" title="还没有可使用的教学项目" description="创建真实教学项目后，AI 助手才能基于项目字段调用澄清和生成方案工作流。">
      <template #action><RouterLink class="state-link" :to="{ name: 'project-create' }">创建教学项目</RouterLink></template>
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

        <AiProviderStatusStrip
          :status="gatewayStatus"
          :loading="statusLoading"
          :error="statusError"
          @refresh="loadGatewayStatus"
        />

        <el-form class="assistant-form" label-position="top">
          <el-form-item label="教学项目">
            <el-select v-model="selectedProjectId" class="full-width" filterable :loading="projectsLoading" placeholder="选择一个真实教学项目">
              <el-option v-for="project in projects" :key="project.id" :label="projectLabel(project)" :value="project.id" />
            </el-select>
          </el-form-item>

          <div v-if="selectedProject" class="project-context">
            <div><span>课程</span><strong>{{ selectedProject.courseName }}</strong></div>
            <div><span>章节</span><strong>{{ selectedProject.chapterTitle }}</strong></div>
            <div><span>模式</span><strong>{{ modeLabel(selectedProject.modelMode) }}</strong></div>
          </div>

          <el-form-item label="当前需求描述" required>
            <el-input v-model="rawRequirement" type="textarea" :rows="6" maxlength="5000" show-word-limit resize="vertical" placeholder="补充课程、章节、学生对象、课时和希望生成的产物。" />
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
            <el-button type="primary" :icon="Search" :loading="clarifying" :disabled="!canRunWorkflow" @click="runClarificationCheck">检查需求澄清</el-button>
            <el-button type="success" :icon="DocumentChecked" :loading="planning" :disabled="!canRunWorkflow" @click="runPlanSuggestion">生成方案建议</el-button>
          </div>
          <p v-if="workflowDisabledReason" class="workflow-hint">{{ workflowDisabledReason }}</p>
        </el-form>
      </section>

      <section class="assistant-results">
        <StatePanel v-if="!clarificationResult && !planResult" type="info" title="等待一次真实工作流操作" description="先检查需求澄清，或基于当前项目字段生成方案建议。返回结果后可以进入项目的对应流程继续处理。" />

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
            <section class="plan-block"><h3>PPT 大纲</h3><StatePanel v-if="planResult.pptOutline.length === 0" type="empty" title="暂无 PPT 大纲" description="工作流没有返回 PPT 段落。" /><ol v-else class="plan-list"><li v-for="section in planResult.pptOutline" :key="section.title"><strong>{{ section.title }}</strong><span v-for="point in section.points" :key="point">{{ point }}</span><small v-if="section.materialReference">依据：{{ section.materialReference }}</small></li></ol></section>
            <section class="plan-block"><h3>Word 教案大纲</h3><StatePanel v-if="planResult.docOutline.length === 0" type="empty" title="暂无教案大纲" description="工作流没有返回教案段落。" /><ol v-else class="plan-list"><li v-for="section in planResult.docOutline" :key="section.title"><strong>{{ section.title }}</strong><span v-for="point in section.points" :key="point">{{ point }}</span><small v-if="section.materialReference">依据：{{ section.materialReference }}</small></li></ol></section>
          </div>
          <div v-if="planResult.interactionPlan.length" class="result-block"><h3>互动安排</h3><ul class="result-list"><li v-for="item in planResult.interactionPlan" :key="item">{{ item }}</li></ul></div>
          <div class="result-actions"><el-button type="primary" plain :icon="ArrowRight" @click="goToGenerationPlan">打开项目生成流程</el-button></div>
        </section>
      </section>
    </div>
  </section>
</template>

<script setup lang="ts">
import { runClarification, runGenerationPlan, type ClarificationResponse, type GenerationPlanResponse, type GenerationMode } from '@/api/aiAssistant';
import { listProjects, type TeachingProject } from '@/api/projects';
import AiProviderStatusStrip from '@/components/ai/AiProviderStatusStrip.vue';
import PageHeader from '@/components/PageHeader.vue';
import StatePanel from '@/components/StatePanel.vue';
import { useAiGatewayStatus } from '@/composables/useAiGatewayStatus';
import { DocumentChecked, MagicStick, Refresh, Search, ArrowRight } from '@element-plus/icons-vue';
import { computed, onMounted, ref, watch } from 'vue';
import { RouterLink, useRouter } from 'vue-router';

const router = useRouter();
const projects = ref<TeachingProject[]>([]);
const selectedProjectId = ref<number>();
const rawRequirement = ref('');
const outputTypes = ref<string[]>(['PPT', 'DOCX', 'INTERACTION']);
const clarificationResult = ref<ClarificationResponse>();
const planResult = ref<GenerationPlanResponse>();
const projectsLoading = ref(false);
const clarifying = ref(false);
const planning = ref(false);
const projectsError = ref('');
const actionError = ref('');
const {
  status: gatewayStatus,
  loading: statusLoading,
  error: statusError,
  presentation: providerPresentation,
  refresh: loadGatewayStatus,
} = useAiGatewayStatus();

const selectedProject = computed(() => projects.value.find((project) => project.id === selectedProjectId.value));
const suggestedFields = computed(() => Object.entries(clarificationResult.value?.suggestedFields || {}));
const providerIsUnavailable = computed(() => providerPresentation.value.unavailable);
const canRunWorkflow = computed(() => Boolean(selectedProject.value && rawRequirement.value.trim() && outputTypes.value.length && !providerIsUnavailable.value && !clarifying.value && !planning.value));
const workflowDisabledReason = computed(() => {
  if (providerIsUnavailable.value) return 'Dify 尚未达到可调用条件，且 Mock 回退未启用，工作流不可执行。';
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
    return;
  }
  rawRequirement.value = project.description?.trim() || `${project.courseName}，章节主题：${project.chapterTitle}，面向${project.targetStudents || '目标学生'}。`;
  outputTypes.value = ['PPT', 'DOCX', 'INTERACTION'];
});

async function loadPage() {
  projectsLoading.value = true;
  projectsError.value = '';
  const [projectResult] = await Promise.allSettled([listProjects(), loadGatewayStatus()]);
  if (projectResult.status === 'fulfilled') {
    projects.value = projectResult.value;
    if (!projects.value.some((project) => project.id === selectedProjectId.value)) selectedProjectId.value = projects.value[0]?.id;
  } else {
    projectsError.value = resolveError(projectResult.reason, '暂时无法读取教学项目，请稍后重试。');
  }
  projectsLoading.value = false;
}

async function runClarificationCheck() {
  if (!selectedProject.value || !canRunWorkflow.value) return;
  clarifying.value = true;
  actionError.value = '';
  try {
    clarificationResult.value = await runClarification({
      projectId: selectedProject.value.id,
      rawRequirement: rawRequirement.value.trim(),
      knownFields: knownFieldsForProject(selectedProject.value),
      generationMode: generationMode(selectedProject.value.modelMode),
    });
  } catch (error) {
    actionError.value = resolveError(error, '需求澄清工作流执行失败，请稍后重试。');
  } finally {
    await loadGatewayStatus();
    clarifying.value = false;
  }
}

async function runPlanSuggestion() {
  if (!selectedProject.value || !canRunWorkflow.value) return;
  planning.value = true;
  actionError.value = '';
  try {
    planResult.value = await runGenerationPlan({
      projectId: selectedProject.value.id,
      courseName: selectedProject.value.courseName,
      chapterTopic: selectedProject.value.chapterTitle,
      targetAudience: selectedProject.value.targetStudents,
      outputTypes: outputTypes.value,
      generationMode: generationMode(selectedProject.value.modelMode),
    });
  } catch (error) {
    actionError.value = resolveError(error, '生成方案工作流执行失败，请稍后重试。');
  } finally {
    await loadGatewayStatus();
    planning.value = false;
  }
}

function goToRequirements() {
  if (selectedProject.value) void router.push({ name: 'project-requirements', params: { projectId: selectedProject.value.id } });
}

function goToGenerationPlan() {
  if (selectedProject.value) void router.push({ name: 'project-plan', params: { projectId: selectedProject.value.id } });
}

function projectLabel(project: TeachingProject) { return `${project.projectName} · ${project.courseName} · ${project.chapterTitle}`; }
function knownFieldsForProject(project: TeachingProject) { return ['courseName', 'chapterTopic', ...(project.targetStudents ? ['targetAudience'] : []), ...(project.lessonDuration ? ['lessonDurationMinutes'] : [])]; }
function generationMode(value?: string): GenerationMode { return value === 'QUALITY' || value === 'HIGH_QUALITY' || value === 'ECONOMY' || value === 'MOCK' ? value : 'STANDARD'; }
function modeLabel(value?: string) { return value === 'HIGH_QUALITY' ? '高质量' : value === 'QUALITY' ? '质量优先' : value === 'ECONOMY' ? '经济模式' : value === 'MOCK' ? 'Mock' : '标准模式'; }
function resolveError(error: unknown, fallback: string) {
  const message = (error as { response?: { data?: { message?: string } } }).response?.data?.message;
  return message && !/(Exception|java\.|Axios)/i.test(message) ? message : fallback;
}

onMounted(loadPage);
</script>

<style scoped>
.assistant-page { min-width: 0; }
.assistant-layout { display: grid; grid-template-columns: minmax(320px, 0.72fr) minmax(0, 1.28fr); align-items: start; gap: 20px; }
.assistant-control-panel, .result-panel { min-width: 0; padding: 20px; }
.assistant-results { display: grid; min-width: 0; gap: 16px; }
.panel-heading, .result-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; }
.panel-heading > .el-icon { color: var(--color-primary); font-size: 24px; }
.panel-heading__eyebrow, .result-heading span { color: var(--color-primary); font-size: 12px; font-weight: 700; }
.panel-heading h2, .result-heading h2 { margin: 5px 0 0; color: var(--color-text); font-size: 18px; line-height: 1.4; overflow-wrap: anywhere; }
.assistant-form { margin-top: 18px; }.full-width { width: 100%; }
.project-context { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 9px; padding: 11px; margin: -4px 0 16px; border: 1px solid var(--color-border); border-radius: var(--radius-md); background: var(--color-surface-subtle); }
.project-context div { min-width: 0; }.project-context span, .project-context strong { display: block; overflow-wrap: anywhere; }.project-context span { color: var(--color-text-muted); font-size: 11px; }.project-context strong { margin-top: 3px; color: var(--color-text); font-size: 12px; line-height: 1.4; }
.output-types { display: flex; flex-wrap: wrap; gap: 8px 14px; }.assistant-alert { margin-bottom: 14px; }.assistant-actions { display: flex; flex-wrap: wrap; gap: 10px; margin-top: 4px; }.workflow-hint { margin: 10px 0 0; color: var(--color-text-muted); font-size: 12px; line-height: 1.5; }
.result-heading { padding-bottom: 15px; border-bottom: 1px solid var(--color-border); }.result-next-action { padding: 12px; margin: 16px 0 0; border-left: 3px solid var(--color-primary); background: var(--color-primary-soft); color: var(--color-text-secondary); font-size: 13px; line-height: 1.65; overflow-wrap: anywhere; }.result-block { margin-top: 18px; }.result-block h3, .plan-block h3 { margin: 0 0 9px; color: var(--color-text); font-size: 14px; }.result-list { display: grid; gap: 8px; padding-left: 21px; margin: 0; color: var(--color-text-secondary); font-size: 13px; line-height: 1.65; }.suggestion-list { display: grid; gap: 8px; margin: 0; }.suggestion-list div { display: grid; grid-template-columns: 150px minmax(0, 1fr); gap: 10px; padding: 9px 0; border-bottom: 1px solid var(--color-border); }.suggestion-list dt { color: var(--color-text-muted); font-size: 12px; overflow-wrap: anywhere; }.suggestion-list dd { margin: 0; color: var(--color-text-secondary); font-size: 13px; overflow-wrap: anywhere; }.plan-columns { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px; margin-top: 18px; }.plan-block { min-width: 0; padding: 14px; border: 1px solid var(--color-border); border-radius: var(--radius-md); background: var(--color-surface-subtle); }.plan-list { display: grid; gap: 10px; padding-left: 20px; margin: 0; }.plan-list li { display: grid; gap: 5px; color: var(--color-text-secondary); font-size: 12px; line-height: 1.5; }.plan-list strong { color: var(--color-text); font-size: 13px; }.plan-list small { color: var(--color-text-muted); }.result-actions { display: flex; justify-content: flex-end; margin-top: 18px; }.state-link { display: inline-flex; min-height: var(--control-height); align-items: center; padding: 0 14px; border-radius: var(--radius-md); background: var(--color-primary); color: #fff; font-size: 13px; font-weight: 700; text-decoration: none; }
@media (max-width: 980px) { .assistant-layout { grid-template-columns: 1fr; } }
@media (max-width: 600px) { .assistant-control-panel, .result-panel { padding: 16px; }.project-context, .plan-columns { grid-template-columns: 1fr; }.assistant-actions, .assistant-actions .el-button, .result-actions, .result-actions .el-button { width: 100%; }.suggestion-list div { grid-template-columns: 1fr; gap: 3px; }.result-heading { flex-direction: column; } }
</style>
