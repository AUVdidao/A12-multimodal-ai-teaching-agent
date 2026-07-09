<template>
  <section class="page">
    <header class="page__header">
      <h2 class="page__title">新建课件项目</h2>
      <p class="page__description">选择学科、年级和生成模式，为后续教学需求输入准备项目上下文。</p>
    </header>

    <el-card class="page-card project-create-card" shadow="never">
      <el-form label-position="top" :model="form" @submit.prevent>
        <el-row :gutter="16">
          <el-col :xs="24" :md="12">
            <el-form-item label="项目名称" required>
              <el-input v-model="form.projectName" placeholder="例如：五年级数学分数课件" />
            </el-form-item>
          </el-col>
          <el-col :xs="24" :md="12">
            <el-form-item label="生成模式" required>
              <el-segmented v-model="form.generationMode" :options="modeOptions" />
            </el-form-item>
          </el-col>
          <el-col :xs="24" :md="12">
            <el-form-item label="学科">
              <el-input v-model="form.courseName" placeholder="例如：数学" />
            </el-form-item>
          </el-col>
          <el-col :xs="24" :md="12">
            <el-form-item label="年级 / 学段">
              <el-input v-model="form.targetAudience" placeholder="例如：五年级" />
            </el-form-item>
          </el-col>
          <el-col :xs="24" :md="12">
            <el-form-item label="课题">
              <el-input v-model="form.chapterTopic" placeholder="例如：分数的意义" />
            </el-form-item>
          </el-col>
          <el-col :xs="24" :md="12">
            <el-form-item label="课时长度（分钟）">
              <el-input-number
                v-model="form.lessonDurationMinutes"
                :min="1"
                :max="240"
                controls-position="right"
              />
            </el-form-item>
          </el-col>
        </el-row>
      </el-form>
    </el-card>

    <div class="page__actions">
      <el-button type="primary" :loading="submitting" @click="handleCreateProject">
        创建项目并进入需求输入
      </el-button>
      <el-button @click="router.push('/projects')">返回项目列表</el-button>
    </div>
  </section>
</template>

<script setup lang="ts">
import { createProject, type GenerationMode, type ProjectCreatePayload } from '@/api/projects';
import { ElMessage } from 'element-plus';
import { reactive, ref } from 'vue';
import { useRouter } from 'vue-router';

const router = useRouter();
const submitting = ref(false);

const modeOptions = [
  { label: '标准', value: 'STANDARD' },
  { label: '高质量', value: 'HIGH_QUALITY' },
  { label: '经济', value: 'ECONOMY' },
  { label: '演示', value: 'MOCK' },
];

const form = reactive<ProjectCreatePayload>({
  projectName: '',
  courseName: '',
  chapterTopic: '',
  targetAudience: '',
  lessonDurationMinutes: 45,
  generationMode: 'STANDARD' as GenerationMode,
});

async function handleCreateProject() {
  if (!form.projectName.trim()) {
    ElMessage.warning('请先填写项目名称');
    return;
  }

  submitting.value = true;

  try {
    const project = await createProject({
      ...form,
      projectName: form.projectName.trim(),
      courseName: form.courseName.trim(),
      chapterTopic: form.chapterTopic.trim(),
      targetAudience: form.targetAudience.trim(),
    });
    ElMessage.success('项目已创建，请继续填写教学需求');
    router.push(`/projects/${project.id}/requirements`);
  } catch (error) {
    ElMessage.error('项目创建失败，请确认后端服务已启动后重试');
  } finally {
    submitting.value = false;
  }
}
</script>

<style scoped>
.project-create-card {
  max-width: 920px;
}

:deep(.el-segmented) {
  max-width: 100%;
}
</style>
