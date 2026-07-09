<template>
  <section class="page requirement-page">
    <header class="page__header">
      <h2 class="page__title">教学需求输入</h2>
      <p class="page__description">
        录入教师的自然语言需求和可选结构化信息。空缺字段会留给后续主动追问处理。
      </p>
    </header>

    <StatusCard
      title="已进入需求输入阶段"
      :description="stageDescription"
    />

    <el-alert
      v-if="!currentProjectId"
      class="inline-alert"
      title="请从项目创建与生成模式选择流程进入教学需求输入。"
      type="warning"
      show-icon
      :closable="false"
    />

    <el-card class="page-card requirement-card" shadow="never">
      <template #header>
        <div class="requirement-card__header">
          <strong>当前项目需求</strong>
          <el-button :loading="loading" :disabled="!currentProjectId" @click="loadLatestRequirement">
            读取最近保存
          </el-button>
        </div>
      </template>

      <el-form label-position="top" :model="form" @submit.prevent>
        <el-row :gutter="16">
          <el-col :xs="24" :md="8">
            <el-form-item label="年级 / 学段">
              <el-input v-model="form.gradeLevel" placeholder="例如：五年级" />
            </el-form-item>
          </el-col>
          <el-col :xs="24" :md="8">
            <el-form-item label="学科">
              <el-input v-model="form.subject" placeholder="例如：数学" />
            </el-form-item>
          </el-col>
          <el-col :xs="24" :md="8">
            <el-form-item label="课时长度">
              <el-input v-model="form.lessonDuration" placeholder="例如：45分钟" />
            </el-form-item>
          </el-col>
          <el-col :xs="24" :md="12">
            <el-form-item label="课题">
              <el-input v-model="form.topic" placeholder="例如：分数的意义" />
            </el-form-item>
          </el-col>
          <el-col :xs="24" :md="12">
            <el-form-item label="期望输出类型">
              <el-checkbox-group v-model="form.outputTypes" class="output-type-group">
                <el-checkbox-button label="PPT">PPT</el-checkbox-button>
                <el-checkbox-button label="DOCX">DOCX</el-checkbox-button>
                <el-checkbox-button label="INTERACTIVE">互动内容</el-checkbox-button>
              </el-checkbox-group>
            </el-form-item>
          </el-col>
          <el-col :xs="24" :md="12">
            <el-form-item label="教学目标">
              <el-input
                v-model="form.teachingGoals"
                type="textarea"
                :rows="4"
                placeholder="例如：理解分数表示整体与部分的关系"
              />
            </el-form-item>
          </el-col>
          <el-col :xs="24" :md="12">
            <el-form-item label="教师自由描述">
              <el-input
                v-model="form.rawRequirementText"
                type="textarea"
                :rows="4"
                placeholder="例如：帮我设计一节五年级数学课，主题是分数的意义。"
              />
            </el-form-item>
          </el-col>
          <el-col :xs="24" :md="12">
            <el-form-item label="教学重点">
              <el-input v-model="form.keyPoints" type="textarea" :rows="3" placeholder="可留空" />
            </el-form-item>
          </el-col>
          <el-col :xs="24" :md="12">
            <el-form-item label="教学难点">
              <el-input v-model="form.difficultPoints" type="textarea" :rows="3" placeholder="可留空" />
            </el-form-item>
          </el-col>
        </el-row>
      </el-form>

      <el-alert
        v-if="latestSavedAt"
        class="inline-alert"
        :title="`最近保存：${latestSavedAt}`"
        type="success"
        show-icon
        :closable="false"
      />

      <el-alert
        v-if="errorMessage"
        class="inline-alert"
        :title="errorMessage"
        type="warning"
        show-icon
        :closable="false"
      />
    </el-card>

    <div class="page__actions">
      <el-button type="primary" :loading="saving" :disabled="!currentProjectId" @click="saveRequirement">
        保存需求
      </el-button>
      <el-button type="primary" plain @click="router.push(dialogRoute)">
        下一步：智能澄清对话
      </el-button>
      <el-button @click="router.push('/projects')">返回项目列表</el-button>
      <el-button @click="router.push('/')">返回首页</el-button>
    </div>
  </section>
</template>

<script setup lang="ts">
import {
  getLatestRequirementInput,
  saveRequirementInput,
  type RequirementInput,
  type RequirementInputPayload,
} from '@/api/requirements';
import StatusCard from '@/components/StatusCard.vue';
import { ElMessage } from 'element-plus';
import { computed, onMounted, reactive, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';

const route = useRoute();
const router = useRouter();

const loading = ref(false);
const saving = ref(false);
const errorMessage = ref('');
const latestSavedAt = ref('');

const form = reactive<RequirementInputPayload>({
  gradeLevel: '',
  subject: '',
  topic: '',
  lessonDuration: '',
  teachingGoals: '',
  keyPoints: '',
  difficultPoints: '',
  outputTypes: [],
  rawRequirementText: '',
});

const currentProjectId = computed(() => {
  const routeProjectId = normalizeRouteValue(route.params.projectId);
  const queryProjectId = normalizeRouteValue(route.query.projectId);
  return routeProjectId || queryProjectId;
});

const stageDescription = computed(() => {
  if (currentProjectId.value) {
    return `项目 ${currentProjectId.value} 已完成生成模式保存，可填写教学需求并保存为后续澄清输入。`;
  }
  return '当前缺少项目上下文，请从项目创建与生成模式选择流程进入。';
});

const dialogRoute = computed(() => {
  if (!currentProjectId.value) {
    return { path: '/dialog' };
  }
  return {
    path: '/dialog',
    query: { projectId: currentProjectId.value },
  };
});

watch(
  currentProjectId,
  (projectId) => {
    resetForm();
    if (projectId) {
      loadLatestRequirement();
    }
  },
  { immediate: true },
);

onMounted(() => {
  if (!currentProjectId.value) {
    errorMessage.value = '未获取到项目 ID，无法保存教学需求。';
  }
});

async function loadLatestRequirement() {
  if (!currentProjectId.value) {
    errorMessage.value = '未获取到项目 ID，无法读取教学需求。';
    return;
  }

  loading.value = true;
  errorMessage.value = '';

  try {
    const latest = await getLatestRequirementInput(currentProjectId.value);
    if (latest) {
      fillForm(latest);
      latestSavedAt.value = formatDate(latest.updatedAt || latest.createdAt);
    } else {
      latestSavedAt.value = '';
    }
  } catch (error) {
    errorMessage.value = resolveErrorMessage(error, '教学需求读取失败，请确认后端服务已启动。');
  } finally {
    loading.value = false;
  }
}

async function saveRequirement() {
  if (!currentProjectId.value) {
    errorMessage.value = '未获取到项目 ID，无法保存教学需求。';
    return;
  }

  const payload = trimPayload(form);
  if (!payload.topic && !payload.rawRequirementText) {
    errorMessage.value = '课题和教师自由描述至少填写一个。';
    ElMessage.warning(errorMessage.value);
    return;
  }

  saving.value = true;
  errorMessage.value = '';

  try {
    const saved = await saveRequirementInput(currentProjectId.value, payload);
    fillForm(saved);
    latestSavedAt.value = formatDate(saved.updatedAt || saved.createdAt);
    ElMessage.success('教学需求已保存');
  } catch (error) {
    errorMessage.value = resolveErrorMessage(error, '教学需求保存失败，请稍后重试。');
  } finally {
    saving.value = false;
  }
}

function fillForm(requirement: RequirementInput) {
  form.gradeLevel = requirement.gradeLevel || '';
  form.subject = requirement.subject || '';
  form.topic = requirement.topic || '';
  form.lessonDuration = requirement.lessonDuration || '';
  form.teachingGoals = requirement.teachingGoals || '';
  form.keyPoints = requirement.keyPoints || '';
  form.difficultPoints = requirement.difficultPoints || '';
  form.outputTypes = requirement.outputTypes || [];
  form.rawRequirementText = requirement.rawRequirementText || '';
}

function resetForm() {
  form.gradeLevel = '';
  form.subject = '';
  form.topic = '';
  form.lessonDuration = '';
  form.teachingGoals = '';
  form.keyPoints = '';
  form.difficultPoints = '';
  form.outputTypes = [];
  form.rawRequirementText = '';
  latestSavedAt.value = '';
  errorMessage.value = '';
}

function trimPayload(payload: RequirementInputPayload): RequirementInputPayload {
  return {
    gradeLevel: payload.gradeLevel.trim(),
    subject: payload.subject.trim(),
    topic: payload.topic.trim(),
    lessonDuration: payload.lessonDuration.trim(),
    teachingGoals: payload.teachingGoals.trim(),
    keyPoints: payload.keyPoints.trim(),
    difficultPoints: payload.difficultPoints.trim(),
    outputTypes: payload.outputTypes,
    rawRequirementText: payload.rawRequirementText.trim(),
  };
}

function normalizeRouteValue(value: unknown) {
  if (Array.isArray(value)) {
    return value[0] || '';
  }
  return typeof value === 'string' ? value : '';
}

function formatDate(value: string) {
  if (!value) {
    return '';
  }
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(value));
}

function resolveErrorMessage(error: unknown, fallback: string) {
  const candidate = error as { response?: { data?: { message?: string } } };
  return candidate.response?.data?.message || fallback;
}
</script>

<style scoped>
.requirement-page {
  max-width: 1080px;
}

.requirement-card {
  margin-top: 18px;
}

.requirement-card__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.output-type-group {
  display: flex;
  flex-wrap: wrap;
  gap: 0;
}
</style>
