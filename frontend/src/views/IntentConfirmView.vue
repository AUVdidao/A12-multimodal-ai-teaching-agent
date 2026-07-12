<template>
  <section class="page intent-page">
    <PageHeader eyebrow="M2 · 最终确认" title="资料增强教学意图确认单" description="教师需求保持最高优先级，资料和知识命中只作为可追溯的增强依据。" :project-label="projectLabel">
      <template #actions><el-button :icon="Refresh" :loading="loading" @click="loadWorkspace">刷新状态</el-button></template>
    </PageHeader>

    <M2ProgressSteps v-if="projectId" :current-step="4" :project-id="projectId" :has-materials="hasMaterials" :has-usages="hasUsages" :has-parsed="hasParsed" :has-knowledge="hasKnowledge" :intent-confirmed="confirmed" />
    <StatePanel v-if="!projectId" type="error" title="没有可用的教学项目" description="请从知识检索页进入教学意图确认。" />
    <StatePanel v-else-if="loading && !intent" type="loading" title="正在融合教学意图" description="读取教师确认需求、资料用途和真实知识命中。" />
    <StatePanel v-else-if="errorMessage && !intent" type="error" title="教学意图生成失败" :description="errorMessage"><template #action><el-button type="primary" @click="loadWorkspace">重新生成</el-button></template></StatePanel>

    <template v-else-if="intent">
      <div class="intent-workspace">
        <main class="surface-panel intent-document" v-loading="saving || confirming">
          <header class="intent-document__header"><div><span>ENHANCED TEACHING INTENT</span><h2>生成前教学意图</h2><p>需求摘要 #{{ intent.requirementSummaryId }} · {{ confirmed ? '最终确认版本' : '可编辑草稿' }}</p></div><StatusBadge :status="intent.status" /></header>
          <el-form label-position="top" @submit.prevent>
            <FormSection :icon="Aim" title="生成目标" description="保持教师已确认的教学目标，不由资料覆盖。"><el-form-item label="目标描述"><el-input v-model="form.generationGoal" type="textarea" :rows="3" maxlength="4000" :disabled="confirmed" /></el-form-item></FormSection>
            <FormSection :icon="Reading" title="内容依据" description="说明资料如何增强内容组织，并保留明确边界。"><el-form-item label="增强依据"><el-input v-model="form.contentBasis" type="textarea" :rows="4" maxlength="6000" :disabled="confirmed" /></el-form-item></FormSection>
            <FormSection :icon="Guide" title="教学组织" description="确认教学方法、互动方式和视觉表达。">
              <div class="form-grid"><el-form-item label="教学方法"><el-input v-model="form.teachingApproach" type="textarea" :rows="3" maxlength="4000" :disabled="confirmed" /></el-form-item><el-form-item label="互动方式"><el-input v-model="form.interactionMode" type="textarea" :rows="3" maxlength="500" :disabled="confirmed" /></el-form-item></div>
              <el-form-item label="输出类型"><el-checkbox-group v-model="form.outputTypes" :disabled="confirmed"><el-checkbox value="PPT">PPT 课件</el-checkbox><el-checkbox value="LESSON_PLAN">Word 教案</el-checkbox><el-checkbox value="INTERACTION">互动内容</el-checkbox></el-checkbox-group></el-form-item>
              <el-form-item label="风格偏好"><el-input v-model="form.stylePreference" maxlength="500" :disabled="confirmed" /></el-form-item>
            </FormSection>
            <el-alert v-if="errorMessage" :title="errorMessage" type="warning" show-icon :closable="false" />
          </el-form>
        </main>

        <aside class="intent-sidebar">
          <section class="surface-panel status-panel">
            <span>确认状态</span><div class="status-panel__title"><el-icon><component :is="confirmed ? CircleCheck : EditPen" /></el-icon><div><h2>{{ confirmed ? '教学意图已确认' : '等待教师确认' }}</h2><p>{{ confirmed ? '该版本已经锁定，刷新后仍保持确认状态。' : '可编辑并保存，确认后完成当前 M2 流程。' }}</p></div></div>
            <dl><div><dt>证据数量</dt><dd>{{ intent.evidenceItems.length }}</dd></div><div><dt>最近更新</dt><dd>{{ formatDateTime(intent.updatedAt) }}</dd></div><div v-if="intent.confirmedAt"><dt>确认时间</dt><dd>{{ formatDateTime(intent.confirmedAt) }}</dd></div></dl>
            <div v-if="!confirmed" class="status-panel__actions"><el-button :icon="EditPen" :loading="saving" @click="saveDraft">保存草稿</el-button><el-button type="primary" :icon="CircleCheck" :loading="confirming" :disabled="!canConfirm" @click="confirmIntent">确认教学意图</el-button></div>
          </section>
          <EvidencePanel :evidence="intent.evidenceItems" />
          <section class="next-stage-panel"><div><el-icon><Lock /></el-icon></div><span>当前边界</span><h2>内容生成尚未开放</h2><p>{{ confirmed ? 'M2 已完成。后续内容生成将以这份已确认的教学意图作为输入。' : '确认教学意图后将完成当前 M2 资料增强流程。' }}</p></section>
        </aside>
      </div>

      <PrimaryActionBar>
        <template #info>{{ confirmed ? 'M2 资料增强闭环已确认，可作为后续内容生成输入。' : '确认前仍可调整教学方法和互动方式。' }}</template>
        <template #secondary><el-button @click="router.push(`/projects/${projectId}/knowledge`)">返回知识检索</el-button></template>
        <el-button v-if="!confirmed" type="primary" :disabled="!canConfirm" @click="confirmIntent">确认并完成 M2</el-button>
        <span v-else class="m2-complete-note">M2 已确认，内容生成将在后续阶段开放。</span>
      </PrimaryActionBar>
    </template>
  </section>
</template>

<script setup lang="ts">
import { getKnowledgeOverview } from '@/api/knowledge';
import { listMaterials } from '@/api/materials';
import { getProject } from '@/api/projects';
import { confirmTeachingIntent, generateTeachingIntent, getLatestTeachingIntent, updateTeachingIntent, type TeachingIntent, type TeachingIntentPayload } from '@/api/teachingIntents';
import EvidencePanel from '@/components/EvidencePanel.vue';
import FormSection from '@/components/FormSection.vue';
import M2ProgressSteps from '@/components/M2ProgressSteps.vue';
import PageHeader from '@/components/PageHeader.vue';
import PrimaryActionBar from '@/components/PrimaryActionBar.vue';
import StatePanel from '@/components/StatePanel.vue';
import StatusBadge from '@/components/StatusBadge.vue';
import { formatDateTime } from '@/utils/materialLabels';
import { Aim, CircleCheck, EditPen, Guide, Lock, Reading, Refresh } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { computed, onMounted, reactive, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';

const route = useRoute();
const router = useRouter();
const projectId = computed(() => { const value = Number(route.params.projectId); return Number.isInteger(value) && value > 0 ? value : null; });
const projectLabel = ref<string>();
const intent = ref<TeachingIntent | null>(null);
const form = reactive<TeachingIntentPayload>({ generationGoal: '', contentBasis: '', teachingApproach: '', interactionMode: '', outputTypes: [], stylePreference: '' });
const loading = ref(false); const saving = ref(false); const confirming = ref(false); const errorMessage = ref('');
const hasMaterials = ref(false); const hasUsages = ref(false); const hasParsed = ref(false); const hasKnowledge = ref(false);
const confirmed = computed(() => intent.value?.status === 'CONFIRMED');
const canConfirm = computed(() => Boolean(form.generationGoal.trim() && form.contentBasis.trim() && form.teachingApproach.trim() && form.interactionMode.trim() && form.outputTypes.length && intent.value?.evidenceItems.length));

onMounted(loadWorkspace);
async function loadWorkspace() {
  if (!projectId.value) return;
  loading.value = true; errorMessage.value = '';
  try {
    const [project, materials, overview, latest] = await Promise.all([getProject(projectId.value), listMaterials(projectId.value), getKnowledgeOverview(projectId.value), getLatestTeachingIntent(projectId.value)]);
    projectLabel.value = project.projectName; hasMaterials.value = materials.length > 0; hasUsages.value = hasMaterials.value && materials.every((item) => item.usageTypes.length > 0); hasParsed.value = materials.some((item) => item.parseStatus === 'SUCCEEDED'); hasKnowledge.value = overview.chunkCount > 0;
    intent.value = latest || await generateTeachingIntent(projectId.value); applyIntent(intent.value);
  } catch (error) { errorMessage.value = resolveError(error, '教学意图生成失败，请先完成资料解析和有命中的知识检索。'); }
  finally { loading.value = false; }
}
async function saveDraft() {
  if (!projectId.value || !intent.value || confirmed.value || saving.value) return;
  saving.value = true; errorMessage.value = '';
  try { intent.value = await updateTeachingIntent(projectId.value, intent.value.id, payload()); applyIntent(intent.value); ElMessage.success('教学意图草稿已保存'); }
  catch (error) { errorMessage.value = resolveError(error, '教学意图保存失败。'); }
  finally { saving.value = false; }
}
async function confirmIntent() {
  if (!projectId.value || !intent.value || confirmed.value || !canConfirm.value || confirming.value) return;
  try { await ElMessageBox.confirm('确认后教学意图将锁定，M2 资料增强闭环完成。', '确认教学意图', { confirmButtonText: '确认并锁定', cancelButtonText: '继续检查', type: 'warning', autofocus: false }); } catch { return; }
  confirming.value = true; errorMessage.value = '';
  try { intent.value = await updateTeachingIntent(projectId.value, intent.value.id, payload()); intent.value = await confirmTeachingIntent(projectId.value, intent.value.id); applyIntent(intent.value); ElMessage.success('教学意图已确认，M2 已完成'); }
  catch (error) { errorMessage.value = resolveError(error, '教学意图确认失败。'); }
  finally { confirming.value = false; }
}
function payload(): TeachingIntentPayload { return { generationGoal: form.generationGoal, contentBasis: form.contentBasis, teachingApproach: form.teachingApproach, interactionMode: form.interactionMode, outputTypes: [...form.outputTypes], stylePreference: form.stylePreference }; }
function applyIntent(value: TeachingIntent) { form.generationGoal = value.generationGoal || ''; form.contentBasis = value.contentBasis || ''; form.teachingApproach = value.teachingApproach || ''; form.interactionMode = value.interactionMode || ''; form.outputTypes = [...(value.outputTypes || [])]; form.stylePreference = value.stylePreference || ''; }
function resolveError(error: unknown, fallback: string) { const message = (error as { response?: { data?: { message?: string } } }).response?.data?.message; return message && !/(Exception|java\.|Axios)/i.test(message) ? message : fallback; }
</script>

<style scoped>
.intent-workspace { display: grid; grid-template-columns: minmax(0, 1.22fr) minmax(310px, .78fr); align-items: start; gap: 20px; }
.intent-document { min-width: 0; padding: 24px; }
.intent-document__header { display: flex; align-items: flex-start; justify-content: space-between; gap: 18px; margin-bottom: 24px; padding-bottom: 18px; border-bottom: 2px solid var(--color-text); }
.intent-document__header span:first-child, .status-panel > span, .next-stage-panel > span { color: var(--color-primary); font-size: 10px; font-weight: 800; }
.intent-document__header h2, .intent-document__header p, .status-panel h2, .status-panel p, .next-stage-panel h2, .next-stage-panel p { margin: 0; }
.intent-document__header h2 { margin-top: 5px; font-size: 20px; }
.intent-document__header p { margin-top: 4px; color: var(--color-text-muted); font-size: 10px; }
.form-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px; }
.intent-sidebar { display: grid; gap: 15px; }
.status-panel { position: sticky; top: 88px; padding: 20px; }
.status-panel__title { display: flex; align-items: flex-start; gap: 10px; margin-top: 11px; }
.status-panel__title > .el-icon { color: var(--color-success); font-size: 22px; }
.status-panel h2 { font-size: 15px; }
.status-panel p { margin-top: 4px; color: var(--color-text-muted); font-size: 10px; line-height: 1.5; }
.status-panel dl { margin: 16px 0; border-top: 1px solid var(--color-border); }
.status-panel dl div { display: flex; justify-content: space-between; gap: 12px; padding: 10px 0; border-bottom: 1px solid var(--color-border); }
.status-panel dt, .status-panel dd { margin: 0; font-size: 10px; }
.status-panel dt { color: var(--color-text-muted); }
.status-panel dd { font-weight: 700; text-align: right; }
.status-panel__actions { display: grid; gap: 8px; }
.status-panel__actions .el-button { width: 100%; margin-left: 0; }
.next-stage-panel { padding: 20px; border: 1px dashed var(--color-border-strong); border-radius: var(--radius-lg); background: var(--color-surface-subtle); }
.next-stage-panel > div { display: grid; width: 34px; height: 34px; margin-bottom: 12px; place-items: center; border-radius: var(--radius-md); background: #eef1f5; color: var(--color-text-muted); }
.next-stage-panel h2 { margin-top: 5px; font-size: 14px; }
.next-stage-panel p { margin: 7px 0 13px; color: var(--color-text-muted); font-size: 10px; line-height: 1.6; }
.m2-complete-note { color: var(--color-text-muted); font-size: 12px; }
@media (max-width: 980px) { .intent-workspace { grid-template-columns: 1fr; } .status-panel { position: static; } }
@media (max-width: 640px) { .intent-document { padding: 18px; } .intent-document__header { flex-direction: column; } .form-grid { grid-template-columns: 1fr; } }
</style>
