<template>
  <section class="page summary-page">
    <PageHeader
      eyebrow="M1 · 第 5 步"
      title="教学需求确认单"
      description="核对 AI 整理后的结构化需求，教师确认后形成 M1 最终版本。"
      :project-label="project ? project.projectName : projectId ? `项目 #${projectId}` : undefined"
    >
      <template #actions><el-button :icon="Refresh" :loading="loading" @click="loadSummary">刷新状态</el-button></template>
    </PageHeader>

    <M1ProgressSteps
      :current-step="4"
      :unlocked-step="4"
      :completed-through="confirmed ? 4 : 3"
      :project-id="projectId"
    />

    <StatePanel v-if="!projectId" type="error" title="没有可用的教学项目" description="请从教学需求页面进入摘要确认。">
      <template #action><el-button size="small" type="primary" @click="router.push('/projects')">返回项目列表</el-button></template>
    </StatePanel>
    <StatePanel v-else-if="loading && !summary" type="loading" title="正在整理需求摘要" description="读取最新需求版本与确认状态，请稍候。" />
    <StatePanel v-else-if="errorMessage && !summary" type="error" title="需求摘要读取失败" :description="errorMessage">
      <template #action><el-button size="small" type="primary" @click="loadSummary">重新加载</el-button></template>
    </StatePanel>

    <div v-else-if="summary" class="summary-workspace">
      <main class="surface-panel summary-document" v-loading="loading" aria-label="教学需求确认单内容">
        <header class="summary-document__header">
          <div><span>STRUCTURED REQUIREMENT</span><h2>教学需求确认单</h2><p>{{ project?.courseName || form.subject || '课程待确认' }} · {{ project?.chapterTitle || form.topic || '课题待确认' }}</p></div>
          <StatusBadge :status="summary.status" />
        </header>

        <el-form label-position="top" @submit.prevent>
          <FormSection :icon="Reading" title="课程基础信息" description="确认授课对象、课程主题与时间安排。">
            <div class="form-grid">
              <el-form-item label="年级"><el-input v-model="form.gradeLevel" :disabled="confirmed" /></el-form-item>
              <el-form-item label="学科"><el-input v-model="form.subject" :disabled="confirmed" /></el-form-item>
              <el-form-item label="课题"><el-input v-model="form.topic" :disabled="confirmed" /></el-form-item>
              <el-form-item label="课时"><el-input v-model="form.lessonDuration" :disabled="confirmed" /></el-form-item>
            </div>
          </FormSection>

          <FormSection :icon="Notebook" title="教学设计" description="确认目标、重点与难点是否准确表达教师意图。">
            <el-form-item label="教学目标"><el-input v-model="form.teachingGoals" type="textarea" :rows="3" :disabled="confirmed" /></el-form-item>
            <div class="form-grid form-grid--two">
              <el-form-item label="教学重点"><el-input v-model="form.keyPoints" type="textarea" :rows="3" :disabled="confirmed" /></el-form-item>
              <el-form-item label="教学难点"><el-input v-model="form.difficultPoints" type="textarea" :rows="3" :disabled="confirmed" /></el-form-item>
            </div>
          </FormSection>

          <FormSection :icon="Document" title="输出偏好" description="这些内容是下一阶段的输入约束，当前尚未生成任何文件。">
            <el-form-item label="期望输出">
              <el-checkbox-group v-model="form.outputTypes" class="output-types" :disabled="confirmed">
                <el-checkbox v-for="option in outputTypeOptions" :key="option.value" :value="option.value">{{ option.label }}</el-checkbox>
              </el-checkbox-group>
            </el-form-item>
            <el-form-item label="风格偏好">
              <el-input v-model="form.stylePreference" maxlength="200" :disabled="confirmed" placeholder="未填写时保持为空，不由系统编造" />
            </el-form-item>
          </FormSection>

          <el-alert v-if="errorMessage" class="inline-alert" :title="errorMessage" type="warning" show-icon :closable="false" />
        </el-form>
      </main>

      <aside class="summary-sidebar">
        <section class="surface-panel confirmation-panel" aria-labelledby="confirmation-title">
          <span class="confirmation-panel__label">确认状态</span>
          <div class="confirmation-panel__status">
            <el-icon><component :is="confirmed ? CircleCheck : EditPen" /></el-icon>
            <div><h2 id="confirmation-title">{{ confirmed ? '需求已确认' : '等待教师确认' }}</h2><p>{{ confirmed ? '该版本已锁定，不会被误修改。' : '可继续编辑，确认后形成最终版本。' }}</p></div>
          </div>

          <dl class="confirmation-panel__meta">
            <div><dt>来源需求版本</dt><dd>#{{ summary.sourceRequirementId }}</dd></div>
            <div><dt>生成模式</dt><dd>{{ formatMode(summary.generationMode) }}</dd></div>
            <div><dt>最近更新</dt><dd>{{ formatDate(summary.updatedAt) }}</dd></div>
            <div v-if="summary.confirmedAt"><dt>确认时间</dt><dd>{{ formatDate(summary.confirmedAt) }}</dd></div>
          </dl>

          <div v-if="!confirmed" class="confirmation-panel__actions">
            <el-button :icon="EditPen" :loading="saving" :disabled="saving || confirming" @click="saveDraft">保存修改</el-button>
            <el-button :icon="CircleCheck" type="primary" :loading="confirming" :disabled="!canConfirm || saving || confirming" @click="confirmSummary">确认教学需求</el-button>
          </div>
          <el-button v-else :icon="Back" @click="openRequirements">返回需求澄清</el-button>
        </section>

        <section class="next-stage-panel" aria-label="下一阶段说明">
          <div><el-icon><component :is="confirmed ? Files : Lock" /></el-icon></div>
          <span>下一阶段</span>
          <h2>资料上传与知识库构建</h2>
          <p>{{ confirmed ? '教学需求已锁定，可以进入真实资料上传、用途绑定和本地原型检索。' : '确认教学需求后开放 M2 资料增强流程。' }}</p>
          <el-button v-if="confirmed" type="primary" @click="router.push(`/projects/${projectId}/materials`)">进入 M2 资料增强</el-button>
          <el-button v-else disabled>确认摘要后开放</el-button>
        </section>
      </aside>
    </div>
  </section>
</template>

<script setup lang="ts">
import { getProject, type TeachingProject } from '@/api/projects';
import {
  confirmRequirementSummary as confirmRequirementSummaryApi,
  generateRequirementSummary,
  getLatestRequirementSummary,
  updateRequirementSummary,
  type RequirementSummary,
  type RequirementSummaryPayload,
} from '@/api/requirementSummaries';
import FormSection from '@/components/FormSection.vue';
import M1ProgressSteps from '@/components/M1ProgressSteps.vue';
import PageHeader from '@/components/PageHeader.vue';
import StatePanel from '@/components/StatePanel.vue';
import StatusBadge from '@/components/StatusBadge.vue';
import { Back, CircleCheck, Document, EditPen, Files, Lock, Notebook, Reading, Refresh } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { computed, onMounted, reactive, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';

const route = useRoute();
const router = useRouter();
const projectId = computed(() => {
  const candidate = route.params.projectId || route.query.projectId;
  const value = Array.isArray(candidate) ? candidate[0] : candidate;
  const numericValue = Number(value);
  return Number.isInteger(numericValue) && numericValue > 0 ? numericValue : null;
});

const form = reactive<RequirementSummaryPayload>({ gradeLevel: '', subject: '', topic: '', lessonDuration: '', teachingGoals: '', keyPoints: '', difficultPoints: '', outputTypes: [], stylePreference: '' });
const outputTypeOptions = [
  { value: 'PPT', label: 'PPT 课件' },
  { value: 'LESSON_PLAN', label: 'Word 教案' },
  { value: 'INTERACTION', label: '互动内容' },
];
const project = ref<TeachingProject | null>(null);
const summary = ref<RequirementSummary | null>(null);
const loading = ref(false);
const saving = ref(false);
const confirming = ref(false);
const errorMessage = ref('');
const confirmed = computed(() => summary.value?.status === 'CONFIRMED');
const canConfirm = computed(() => hasText(form.gradeLevel) && hasText(form.subject) && hasText(form.topic) && hasText(form.lessonDuration) && hasText(form.teachingGoals) && form.outputTypes.length > 0);

onMounted(loadSummary);

async function loadSummary() {
  if (!projectId.value) {
    errorMessage.value = '缺少有效项目 ID。';
    return;
  }
  loading.value = true;
  errorMessage.value = '';
  try {
    const [projectResult, latestSummary] = await Promise.all([getProject(projectId.value), getLatestRequirementSummary(projectId.value)]);
    project.value = projectResult;
    summary.value = latestSummary || await generateRequirementSummary(projectId.value);
    applySummary(summary.value);
  } catch (error) {
    errorMessage.value = resolveErrorMessage(error, '需求摘要生成失败，请返回需求页确认信息是否完整。');
  } finally {
    loading.value = false;
  }
}

async function saveDraft() {
  if (!projectId.value || !summary.value || confirmed.value || saving.value) return;
  saving.value = true;
  errorMessage.value = '';
  try {
    summary.value = await updateRequirementSummary(projectId.value, summary.value.id, payload());
    applySummary(summary.value);
    ElMessage.success('摘要修改已保存');
  } catch (error) {
    errorMessage.value = resolveErrorMessage(error, '摘要保存失败，请稍后重试。');
  } finally {
    saving.value = false;
  }
}

async function confirmSummary() {
  if (!projectId.value || !summary.value || confirmed.value || !canConfirm.value || confirming.value) return;
  try {
    await ElMessageBox.confirm('确认后该摘要将锁定为 M1 最终版本，不能继续编辑。', '确认教学需求', {
      confirmButtonText: '确认并锁定',
      cancelButtonText: '继续检查',
      type: 'warning',
      autofocus: false,
    });
  } catch {
    return;
  }
  confirming.value = true;
  errorMessage.value = '';
  try {
    summary.value = await updateRequirementSummary(projectId.value, summary.value.id, payload());
    summary.value = await confirmRequirementSummaryApi(projectId.value, summary.value.id);
    applySummary(summary.value);
    ElMessage.success('教学需求已确认');
  } catch (error) {
    errorMessage.value = resolveErrorMessage(error, '摘要确认失败，请稍后重试。');
  } finally {
    confirming.value = false;
  }
}

function payload(): RequirementSummaryPayload {
  return { gradeLevel: form.gradeLevel, subject: form.subject, topic: form.topic, lessonDuration: form.lessonDuration, teachingGoals: form.teachingGoals, keyPoints: form.keyPoints, difficultPoints: form.difficultPoints, outputTypes: [...form.outputTypes], stylePreference: form.stylePreference };
}

function applySummary(value: RequirementSummary) {
  form.gradeLevel = value.gradeLevel || '';
  form.subject = value.subject || '';
  form.topic = value.topic || '';
  form.lessonDuration = value.lessonDuration || '';
  form.teachingGoals = value.teachingGoals || '';
  form.keyPoints = value.keyPoints || '';
  form.difficultPoints = value.difficultPoints || '';
  form.outputTypes = [...(value.outputTypes || [])];
  form.stylePreference = value.stylePreference || '';
}

function formatMode(value: string) {
  return ({ STANDARD: '标准模式', QUALITY: '高质量模式', HIGH_QUALITY: '高质量模式', ECONOMY: '经济模式', MOCK: 'Mock 模式' } as Record<string, string>)[value] || value;
}

function formatDate(value?: string) {
  if (!value) return '-';
  return new Intl.DateTimeFormat('zh-CN', { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }).format(new Date(value));
}

function openRequirements() {
  if (projectId.value) router.push({ name: 'project-requirements', params: { projectId: projectId.value } });
}

function hasText(value?: string) {
  return Boolean(value?.trim());
}

function resolveErrorMessage(error: unknown, fallback: string) {
  const candidate = error as { response?: { data?: { message?: string } } };
  const message = candidate.response?.data?.message;
  return message && !/(Exception|java\.|Axios)/i.test(message) ? message : fallback;
}
</script>

<style scoped>
.summary-workspace {
  display: grid;
  grid-template-columns: minmax(0, 1.45fr) minmax(300px, 0.55fr);
  align-items: start;
  gap: 22px;
}

.summary-document {
  min-width: 0;
  padding: 26px;
}

.summary-document__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 20px;
  margin-bottom: 26px;
  padding-bottom: 20px;
  border-bottom: 2px solid var(--color-text);
}

.summary-document__header span:first-child {
  color: var(--color-primary);
  font-size: 9px;
  font-weight: 800;
}

.summary-document__header h2,
.summary-document__header p {
  margin: 0;
}

.summary-document__header h2 {
  margin-top: 5px;
  font-size: 21px;
}

.summary-document__header p {
  margin-top: 5px;
  color: var(--color-text-muted);
  font-size: 11px;
}

.form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 16px;
}

.form-grid--two {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.output-types {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 18px;
}

.summary-sidebar {
  display: grid;
  gap: 18px;
}

.confirmation-panel {
  position: sticky;
  top: 90px;
  padding: 22px;
}

.confirmation-panel__label,
.next-stage-panel > span {
  color: var(--color-primary);
  font-size: 10px;
  font-weight: 800;
}

.confirmation-panel__status {
  display: flex;
  align-items: flex-start;
  gap: 11px;
  margin-top: 12px;
}

.confirmation-panel__status > .el-icon {
  flex: 0 0 auto;
  margin-top: 2px;
  color: var(--color-success);
  font-size: 23px;
}

.confirmation-panel__status h2,
.confirmation-panel__status p {
  margin: 0;
}

.confirmation-panel__status h2 {
  font-size: 16px;
}

.confirmation-panel__status p {
  margin-top: 4px;
  color: var(--color-text-muted);
  font-size: 10px;
  line-height: 1.5;
}

.confirmation-panel__meta {
  display: grid;
  gap: 0;
  margin: 20px 0;
  border-top: 1px solid var(--color-border);
}

.confirmation-panel__meta div {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 11px 0;
  border-bottom: 1px solid var(--color-border);
}

.confirmation-panel__meta dt,
.confirmation-panel__meta dd {
  margin: 0;
  font-size: 10px;
}

.confirmation-panel__meta dt {
  color: var(--color-text-muted);
}

.confirmation-panel__meta dd {
  color: var(--color-text);
  font-weight: 700;
  text-align: right;
}

.confirmation-panel__actions {
  display: grid;
  gap: 10px;
}

.confirmation-panel__actions .el-button,
.confirmation-panel > .el-button {
  width: 100%;
  margin-left: 0;
}

.next-stage-panel {
  padding: 22px;
  border: 1px dashed var(--color-border-strong);
  border-radius: var(--radius-lg);
  background: var(--color-surface-subtle);
}

.next-stage-panel > div {
  display: grid;
  width: 34px;
  height: 34px;
  margin-bottom: 14px;
  place-items: center;
  border-radius: var(--radius-md);
  background: #eef1f5;
  color: var(--color-text-muted);
}

.next-stage-panel h2,
.next-stage-panel p {
  margin: 0;
}

.next-stage-panel h2 {
  margin-top: 6px;
  font-size: 15px;
}

.next-stage-panel p {
  margin: 7px 0 14px;
  color: var(--color-text-muted);
  font-size: 10px;
  line-height: 1.6;
}

.next-stage-panel .el-button {
  width: 100%;
}

@media (max-width: 980px) {
  .summary-workspace {
    grid-template-columns: 1fr;
  }

  .confirmation-panel {
    position: static;
  }
}

@media (max-width: 640px) {
  .summary-document {
    padding: 18px;
  }

  .summary-document__header {
    align-items: flex-start;
    flex-direction: column;
  }

  .form-grid,
  .form-grid--two {
    grid-template-columns: 1fr;
  }
}
</style>
