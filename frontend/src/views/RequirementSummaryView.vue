<template>
  <section class="page summary-page">
    <header class="page__header page__header--with-action">
      <div>
        <h2 class="page__title">结构化需求摘要</h2>
        <p class="page__description">项目 {{ projectId || '-' }} · 教师确认版本</p>
      </div>
      <el-button :icon="Refresh" :loading="loading" @click="loadSummary">刷新</el-button>
    </header>

    <el-steps :active="3" finish-status="success" class="m1-steps" simple>
      <el-step title="需求输入" />
      <el-step title="智能澄清" />
      <el-step title="摘要确认" />
    </el-steps>

    <el-alert
      v-if="!projectId"
      title="缺少有效项目 ID，请从需求输入页面进入。"
      type="warning"
      show-icon
      :closable="false"
    />

    <el-card v-else class="page-card summary-form-card" shadow="never" v-loading="loading">
      <div v-if="summary" class="summary-meta">
        <div>
          <span>摘要状态</span>
          <el-tag :type="summary.status === 'CONFIRMED' ? 'success' : 'warning'">
            {{ summary.status === 'CONFIRMED' ? '已确认' : '草稿' }}
          </el-tag>
        </div>
        <div>
          <span>来源需求版本</span>
          <strong>#{{ summary.sourceRequirementId }}</strong>
        </div>
        <div>
          <span>生成模式</span>
          <strong>{{ formatMode(summary.generationMode) }}</strong>
        </div>
      </div>

      <el-form label-position="top" @submit.prevent>
        <div class="form-grid">
          <el-form-item label="年级">
            <el-input v-model="form.gradeLevel" :disabled="confirmed" />
          </el-form-item>
          <el-form-item label="学科">
            <el-input v-model="form.subject" :disabled="confirmed" />
          </el-form-item>
          <el-form-item label="课题">
            <el-input v-model="form.topic" :disabled="confirmed" />
          </el-form-item>
          <el-form-item label="课时">
            <el-input v-model="form.lessonDuration" :disabled="confirmed" />
          </el-form-item>
        </div>

        <el-form-item label="教学目标">
          <el-input v-model="form.teachingGoals" type="textarea" :rows="3" :disabled="confirmed" />
        </el-form-item>
        <div class="form-grid form-grid--wide">
          <el-form-item label="教学重点">
            <el-input v-model="form.keyPoints" type="textarea" :rows="3" :disabled="confirmed" />
          </el-form-item>
          <el-form-item label="教学难点">
            <el-input v-model="form.difficultPoints" type="textarea" :rows="3" :disabled="confirmed" />
          </el-form-item>
        </div>
        <el-form-item label="输出类型">
          <el-checkbox-group v-model="form.outputTypes" class="output-types" :disabled="confirmed">
            <el-checkbox v-for="option in outputTypeOptions" :key="option.value" :value="option.value">
              {{ option.label }}
            </el-checkbox>
          </el-checkbox-group>
        </el-form-item>
        <el-form-item label="风格偏好">
          <el-input
            v-model="form.stylePreference"
            maxlength="200"
            :disabled="confirmed"
            placeholder="未填写时保持为空，不由系统编造"
          />
        </el-form-item>

        <el-alert
          v-if="errorMessage"
          class="inline-alert"
          :title="errorMessage"
          type="warning"
          show-icon
          :closable="false"
        />

        <div class="page__actions">
          <el-button
            v-if="!confirmed"
            :icon="EditPen"
            :loading="saving"
            @click="saveDraft"
          >
            保存修改
          </el-button>
          <el-button
            v-if="!confirmed"
            :icon="CircleCheck"
            type="primary"
            :loading="confirming"
            :disabled="!canConfirm"
            @click="confirmSummary"
          >
            确认需求
          </el-button>
          <el-button @click="openRequirements">返回需求澄清</el-button>
        </div>
      </el-form>
    </el-card>

    <el-alert
      v-if="confirmed"
      class="next-stage-alert"
      title="需求摘要已确认"
      description="下一阶段：资料上传与知识库构建。该能力属于 M2，当前尚未实现。"
      type="success"
      show-icon
      :closable="false"
    />

    <div v-if="confirmed" class="page__actions">
      <el-button :icon="Right" type="primary" @click="openMaterials">
        查看下一阶段入口
      </el-button>
      <el-button @click="router.push('/projects')">返回项目列表</el-button>
    </div>
  </section>
</template>

<script setup lang="ts">
import {
  confirmRequirementSummary as confirmRequirementSummaryApi,
  generateRequirementSummary,
  getLatestRequirementSummary,
  updateRequirementSummary,
  type RequirementSummary,
  type RequirementSummaryPayload,
} from '@/api/requirementSummaries';
import { CircleCheck, EditPen, Refresh, Right } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
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

const form = reactive<RequirementSummaryPayload>({
  gradeLevel: '',
  subject: '',
  topic: '',
  lessonDuration: '',
  teachingGoals: '',
  keyPoints: '',
  difficultPoints: '',
  outputTypes: [],
  stylePreference: '',
});

const outputTypeOptions = [
  { value: 'PPT', label: 'PPT 课件' },
  { value: 'LESSON_PLAN', label: 'Word 教案' },
  { value: 'INTERACTION', label: '互动内容' },
];

const summary = ref<RequirementSummary | null>(null);
const loading = ref(false);
const saving = ref(false);
const confirming = ref(false);
const errorMessage = ref('');

const confirmed = computed(() => summary.value?.status === 'CONFIRMED');
const canConfirm = computed(
  () =>
    hasText(form.gradeLevel) &&
    hasText(form.subject) &&
    hasText(form.topic) &&
    hasText(form.lessonDuration) &&
    hasText(form.teachingGoals) &&
    form.outputTypes.length > 0,
);

onMounted(loadSummary);

async function loadSummary() {
  if (!projectId.value) {
    errorMessage.value = '缺少有效项目 ID。';
    return;
  }

  loading.value = true;
  errorMessage.value = '';
  try {
    summary.value = await getLatestRequirementSummary(projectId.value);
    if (!summary.value) {
      summary.value = await generateRequirementSummary(projectId.value);
    }
    applySummary(summary.value);
  } catch (error) {
    errorMessage.value = resolveErrorMessage(error, '需求摘要生成失败。');
  } finally {
    loading.value = false;
  }
}

async function saveDraft() {
  if (!projectId.value || !summary.value || confirmed.value) {
    return;
  }

  saving.value = true;
  errorMessage.value = '';
  try {
    summary.value = await updateRequirementSummary(projectId.value, summary.value.id, payload());
    applySummary(summary.value);
    ElMessage.success('摘要草稿已保存');
  } catch (error) {
    errorMessage.value = resolveErrorMessage(error, '摘要保存失败。');
  } finally {
    saving.value = false;
  }
}

async function confirmSummary() {
  if (!projectId.value || !summary.value || confirmed.value || !canConfirm.value) {
    return;
  }

  confirming.value = true;
  errorMessage.value = '';
  try {
    summary.value = await updateRequirementSummary(projectId.value, summary.value.id, payload());
    summary.value = await confirmRequirementSummaryApi(projectId.value, summary.value.id);
    applySummary(summary.value);
    ElMessage.success('需求摘要已确认');
  } catch (error) {
    errorMessage.value = resolveErrorMessage(error, '摘要确认失败。');
  } finally {
    confirming.value = false;
  }
}

function payload(): RequirementSummaryPayload {
  return {
    gradeLevel: form.gradeLevel,
    subject: form.subject,
    topic: form.topic,
    lessonDuration: form.lessonDuration,
    teachingGoals: form.teachingGoals,
    keyPoints: form.keyPoints,
    difficultPoints: form.difficultPoints,
    outputTypes: [...form.outputTypes],
    stylePreference: form.stylePreference,
  };
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
  const modes: Record<string, string> = {
    STANDARD: '标准模式',
    QUALITY: '高质量模式',
    HIGH_QUALITY: '高质量模式',
    ECONOMY: '经济模式',
    MOCK: 'Mock 模式',
  };
  return modes[value] || value;
}

function openRequirements() {
  if (projectId.value) {
    router.push({ name: 'project-requirements', params: { projectId: projectId.value } });
  }
}

function openMaterials() {
  router.push({ path: '/materials', query: { projectId: String(projectId.value) } });
}

function hasText(value?: string) {
  return Boolean(value?.trim());
}

function resolveErrorMessage(error: unknown, fallback: string) {
  const candidate = error as { response?: { data?: { message?: string } } };
  return candidate.response?.data?.message || fallback;
}
</script>

<style scoped>
.summary-page {
  max-width: 1080px;
}

.m1-steps {
  margin-bottom: 20px;
}

.summary-form-card {
  max-width: 960px;
}

.summary-meta {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
  margin-bottom: 20px;
  padding-bottom: 18px;
  border-bottom: 1px solid #e5e7eb;
}

.summary-meta div {
  min-height: 64px;
  padding: 12px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #f9fafb;
}

.summary-meta span,
.summary-meta strong {
  display: block;
}

.summary-meta span {
  margin-bottom: 8px;
  color: #667085;
  font-size: 12px;
}

.form-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 14px;
}

.form-grid--wide {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.output-types {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
}

.next-stage-alert {
  max-width: 960px;
  margin-top: 20px;
}

@media (max-width: 860px) {
  .summary-meta,
  .form-grid,
  .form-grid--wide {
    grid-template-columns: 1fr;
  }
}
</style>
