<template>
  <section class="planning-page">
    <ProjectWorkspaceNav :project-id="projectId" />
    <header class="planning-hero">
      <div>
        <span class="planning-eyebrow">阶段5 · 上游规划边界</span>
        <h1>Planning Agent</h1>
        <p>读取确认后的模板语义能力，提出 DRAFT Specification Proposal；不会生成或修改 PPTX。</p>
      </div>
      <el-tag type="warning">开发完成候选边界</el-tag>
    </header>

    <el-alert
      title="真实 Planning 当前 NOT_RUN / LIVE_VERIFICATION_PENDING；教师必须显式选择已验证的模型连接并显式触发，服务端不会使用默认 Kimi 或服务器 Key。"
      type="info"
      :closable="false"
      show-icon
    />

    <div class="planning-grid">
      <el-card shadow="never">
        <template #header><span>1. 读取 Capability View</span></template>
        <el-form label-position="top">
          <el-form-item label="Template ID"><el-input-number v-model="templateId" :min="1" controls-position="right" /></el-form-item>
          <el-form-item label="Confirmed Profile Version ID"><el-input-number v-model="profileVersionId" :min="1" controls-position="right" /></el-form-item>
          <el-button :loading="loadingCapability" type="primary" @click="loadCapability">读取只读能力</el-button>
        </el-form>
        <div v-if="capability" class="capability-summary">
          <strong>{{ capability.displayName }}</strong>
          <span>View v{{ capability.profileVersion }} · {{ capability.checksum }}</span>
          <span>页面角色：{{ capability.pageRoles.join('、') || '未声明' }}</span>
          <span>语义布局：{{ capability.semanticLayouts.map((item) => item.name).join('、') || '未声明' }}</span>
          <span>限制：{{ capability.limitations.join('；') || '无' }}</span>
        </div>
      </el-card>

      <el-card shadow="never">
        <template #header><span>2. 教师确认上下文与显式提案</span></template>
        <el-form label-position="top">
          <el-form-item label="提案操作">
            <el-select v-model="operation" style="width: 100%"><el-option label="首次提案" value="INITIAL_PROPOSAL" /><el-option label="显式 Patch" value="PATCH" /><el-option label="新 DRAFT 版本" value="NEW_DRAFT_VERSION" /></el-select>
          </el-form-item>
          <el-form-item label="我的模型连接">
            <el-select v-model="modelConnectionId" placeholder="请选择已验证的连接" style="width: 100%" :loading="loadingConnections">
              <el-option v-for="connection in connections" :key="connection.id" :label="`${connection.name} · ${connection.modelId}`" :value="connection.id" :disabled="!connection.enabled || connection.verificationStatus !== 'VERIFIED'" />
            </el-select>
            <small v-if="connections.length === 0">请先在“我的模型连接”中新增并测试连接。</small>
          </el-form-item>
          <el-form-item label="课程名称"><el-input v-model="courseName" /></el-form-item>
          <el-form-item label="服务端已确认上下文 revision"><el-input v-model="confirmedContextVersion" placeholder="intent:&lt;id&gt;:&lt;canonical-checksum&gt;" /></el-form-item>
          <el-form-item label="主题"><el-input v-model="topic" /></el-form-item>
          <el-form-item label="已确认教学目标（每行一项）"><el-input v-model="objectivesText" type="textarea" :rows="2" /></el-form-item>
          <el-form-item label="逐页大纲（封面之后每页一项）"><el-input v-model="outlineText" type="textarea" :rows="4" /></el-form-item>
          <el-form-item label="页数"><el-input-number v-model="targetSlideCount" :min="1" :max="200" controls-position="right" /></el-form-item>
          <el-form-item v-if="operation !== 'INITIAL_PROPOSAL'" label="当前 Specification 版本 / checksum">
            <div class="base-fields"><el-input-number v-model="baseVersion" :min="1" /><el-input v-model="baseChecksum" placeholder="64 位 checksum" /></div>
          </el-form-item>
          <el-checkbox v-model="teacherConfirmed">我确认以上教学上下文，可供 Agent 只读使用</el-checkbox>
          <el-button class="submit-button" :loading="submitting" type="primary" :disabled="!capability || !modelConnectionId" @click="submitProposal">教师明确触发提案</el-button>
        </el-form>
      </el-card>
    </div>

    <el-card v-if="result" class="result-card" shadow="never">
      <template #header><span>Proposal 已保存为新 DRAFT 版本</span></template>
      <el-descriptions :column="2" border>
        <el-descriptions-item label="运行状态">{{ result.executionStatus }}</el-descriptions-item>
        <el-descriptions-item label="Provider">{{ result.requestedProvider }} → {{ result.usedProvider }} / {{ result.usedModel }}</el-descriptions-item>
        <el-descriptions-item label="Specification">v{{ result.specification.version }} · {{ result.specification.status }}</el-descriptions-item>
        <el-descriptions-item label="Trace ID">{{ result.traceId }}</el-descriptions-item>
      </el-descriptions>
    </el-card>
    <el-alert v-if="errorMessage" :title="errorMessage" type="error" show-icon :closable="false" />
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { ElMessage } from 'element-plus';
import ProjectWorkspaceNav from '@/components/ProjectWorkspaceNav.vue';
import { createPlanningProposal, getPlanningCapabilityView, type CapabilityView, type PlanningOperation, type PlanningResponse } from '@/api/planning';
import { useRoute } from 'vue-router';
import { getModelConnections, type ModelConnection } from '@/api/aiCredentials';

const route = useRoute();
const projectId = computed(() => String(route.params.projectId));
const templateId = ref(Number(route.query.templateId || 1));
const profileVersionId = ref(Number(route.query.profileVersionId || 1));
const operation = ref<PlanningOperation>('INITIAL_PROPOSAL');
const modelConnectionId = ref<number>();
const connections = ref<ModelConnection[]>([]);
const courseName = ref('');
const confirmedContextVersion = ref('');
const topic = ref('');
const objectivesText = ref('');
const outlineText = ref('');
const targetSlideCount = ref(1);
const baseVersion = ref<number>();
const baseChecksum = ref('');
const teacherConfirmed = ref(false);
const loadingCapability = ref(false);
const loadingConnections = ref(false);
const submitting = ref(false);
const capability = ref<CapabilityView>();
const result = ref<PlanningResponse>();
const errorMessage = ref('');

const lines = (value: string) => value.split(/\r?\n/).map((item) => item.trim()).filter(Boolean);

async function loadConnections() {
  loadingConnections.value = true;
  try {
    const response = await getModelConnections();
    if (response.code === 0) connections.value = response.data ?? [];
  } finally { loadingConnections.value = false; }
}

async function loadCapability() {
  loadingCapability.value = true;
  errorMessage.value = '';
  try { capability.value = await getPlanningCapabilityView(projectId.value, templateId.value, profileVersionId.value); }
  catch { capability.value = undefined; errorMessage.value = '读取 Capability View 失败，请确认 Profile 已 CONFIRMED 且属于当前项目。'; }
  finally { loadingCapability.value = false; }
}

async function submitProposal() {
  if (!teacherConfirmed.value) { ElMessage.warning('请先确认教学上下文'); return; }
  submitting.value = true;
  errorMessage.value = '';
  try {
    result.value = await createPlanningProposal(projectId.value, {
      modelConnectionId: modelConnectionId.value!, templateId: templateId.value, templateProfileVersionId: profileVersionId.value, operation: operation.value,
      baseSpecificationVersion: baseVersion.value || null, baseSpecificationChecksum: baseChecksum.value || null,
      confirmedContextVersion: confirmedContextVersion.value, targetSlideCount: targetSlideCount.value, slideCountTolerance: 0,
      locale: 'zh-CN', teachingContext: { courseName: courseName.value, topic: topic.value, teachingObjectives: lines(objectivesText.value), outline: lines(outlineText.value), lessonPlan: [], teacherConfirmed: teacherConfirmed.value },
      evidence: [], teacherInstruction: undefined, explicitTeacherTrigger: true,
    });
    ElMessage.success('Planning Proposal 已保存为新的 DRAFT');
  } catch { errorMessage.value = '提案被服务端拒绝，请检查页数、版本 checksum、Profile 状态和教师确认边界。'; }
  finally { submitting.value = false; }
}

onMounted(loadConnections);
</script>

<style scoped>
.planning-page { width: 100%; max-width: 1280px; margin: 0 auto; display: grid; gap: 16px; }
.planning-hero { display: flex; align-items: center; justify-content: space-between; gap: 20px; padding: 22px; border: 1px solid var(--ui-border); border-radius: 8px; background: var(--ui-panel); }
.planning-eyebrow { color: var(--ui-primary); font-size: 12px; font-weight: 700; }
.planning-hero h1 { margin: 6px 0 4px; font-size: 24px; }
.planning-hero p { margin: 0; color: var(--ui-muted); font-size: 13px; }
.planning-grid { display: grid; grid-template-columns: minmax(300px, .8fr) minmax(420px, 1.2fr); gap: 16px; }
.capability-summary { display: grid; gap: 6px; margin-top: 18px; padding-top: 14px; border-top: 1px solid var(--ui-border); color: var(--ui-muted); font-size: 12px; line-height: 1.5; }
.capability-summary strong { color: var(--ui-text); font-size: 15px; }
.base-fields { display: grid; grid-template-columns: 140px minmax(0, 1fr); gap: 8px; width: 100%; }
.submit-button { width: 100%; margin-top: 18px; }
.result-card { border-left: 3px solid var(--ui-success); }
@media (max-width: 900px) { .planning-grid { grid-template-columns: 1fr; } .planning-hero { align-items: flex-start; flex-direction: column; } }
</style>
