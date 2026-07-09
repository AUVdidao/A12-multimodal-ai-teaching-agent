<template>
  <section class="page">
    <header class="page__header">
      <h2 class="page__title">教学需求输入</h2>
      <p class="page__description">
        录入教师的原始描述和可选结构化信息。空缺字段会留给后续主动追问处理。
      </p>
    </header>

    <el-alert
      v-if="!activeProjectId"
      class="requirement-alert"
      title="请先输入项目 ID，或从新建项目页面进入"
      type="warning"
      show-icon
      :closable="false"
    />

    <el-card class="page-card requirement-card" shadow="never">
      <div class="requirement-toolbar">
        <el-form-item label="当前项目 ID">
          <el-input-number v-model="manualProjectId" :min="1" controls-position="right" />
        </el-form-item>
        <el-button :loading="loading" @click="handleLoadLatest">读取最近需求</el-button>
      </div>

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
    </el-card>

    <div class="page__actions">
      <el-button type="primary" :loading="saving" @click="handleSave">保存需求</el-button>
      <el-button @click="router.push('/dialog')">下一步：智能澄清对话</el-button>
      <el-button @click="router.push('/projects/new')">返回新建项目</el-button>
    </div>
  </section>
</template>

<script setup lang="ts">
import {
  getLatestRequirementInput,
  saveRequirementInput,
  type RequirementInputPayload,
  type RequirementInputResponse,
} from '@/api/requirements';
import { ElMessage } from 'element-plus';
import { computed, onMounted, reactive, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';

const route = useRoute();
const router = useRouter();
const loading = ref(false);
const saving = ref(false);
const manualProjectId = ref<number | undefined>(undefined);

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

const routeProjectId = computed(() => {
  const rawProjectId = route.params.projectId ?? route.query.projectId;
  const value = Array.isArray(rawProjectId) ? rawProjectId[0] : rawProjectId;
  const parsed = Number(value);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : undefined;
});

const activeProjectId = computed(() => routeProjectId.value ?? manualProjectId.value);

watch(
  routeProjectId,
  (projectId) => {
    if (projectId) {
      manualProjectId.value = projectId;
      handleLoadLatest();
    }
  },
  { immediate: true },
);

onMounted(() => {
  if (!routeProjectId.value) {
    resetForm();
  }
});

async function handleLoadLatest() {
  if (!activeProjectId.value) {
    ElMessage.warning('请先填写项目 ID');
    return;
  }

  loading.value = true;

  try {
    const latest = await getLatestRequirementInput(activeProjectId.value);
    if (latest) {
      fillForm(latest);
      ElMessage.success('已回显最近一次教学需求');
    } else {
      resetForm();
      ElMessage.info('当前项目还没有保存过教学需求');
    }
  } catch (error) {
    ElMessage.error('读取教学需求失败，请确认项目 ID 是否存在');
  } finally {
    loading.value = false;
  }
}

async function handleSave() {
  if (!activeProjectId.value) {
    ElMessage.warning('请先填写项目 ID');
    return;
  }

  if (!form.topic.trim() && !form.rawRequirementText.trim()) {
    ElMessage.warning('课题和教师自由描述至少填写一个');
    return;
  }

  saving.value = true;

  try {
    const saved = await saveRequirementInput(activeProjectId.value, trimPayload(form));
    fillForm(saved);
    ElMessage.success('教学需求已保存');
  } catch (error) {
    ElMessage.error('保存教学需求失败，请检查输入内容或后端服务状态');
  } finally {
    saving.value = false;
  }
}

function fillForm(requirement: RequirementInputResponse) {
  form.gradeLevel = requirement.gradeLevel ?? '';
  form.subject = requirement.subject ?? '';
  form.topic = requirement.topic ?? '';
  form.lessonDuration = requirement.lessonDuration ?? '';
  form.teachingGoals = requirement.teachingGoals ?? '';
  form.keyPoints = requirement.keyPoints ?? '';
  form.difficultPoints = requirement.difficultPoints ?? '';
  form.outputTypes = requirement.outputTypes ?? [];
  form.rawRequirementText = requirement.rawRequirementText ?? '';
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
</script>

<style scoped>
.requirement-card {
  max-width: 1040px;
}

.requirement-alert {
  max-width: 720px;
  margin-bottom: 16px;
}

.requirement-toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-end;
  gap: 12px;
  margin-bottom: 18px;
  padding-bottom: 16px;
  border-bottom: 1px solid #e5e7eb;
}

.requirement-toolbar :deep(.el-form-item) {
  margin-bottom: 0;
}

.output-type-group {
  display: flex;
  flex-wrap: wrap;
  gap: 0;
}
</style>
