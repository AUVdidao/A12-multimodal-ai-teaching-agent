<template>
  <section class="page">
    <header class="page__header">
      <h2 class="page__title">新建课件项目</h2>
      <p class="page__description">
        输入课程、章节、授课对象和课时信息，创建后进入生成模式选择。
      </p>
    </header>

    <StatusCard
      title="项目基础信息"
      description="当前表单已接入项目创建接口，暂不提交真实教学需求和资料文件。"
    />

    <el-card class="page-card form-card" shadow="never">
      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-width="112px"
        label-position="left"
      >
        <el-form-item label="课程名称" prop="courseName">
          <el-input v-model="form.courseName" placeholder="例如：数学" maxlength="40" show-word-limit />
        </el-form-item>

        <el-form-item label="章节主题" prop="chapterTitle">
          <el-input v-model="form.chapterTitle" placeholder="例如：分数的意义" maxlength="80" show-word-limit />
        </el-form-item>

        <el-form-item label="授课对象">
          <el-input v-model="form.targetStudents" placeholder="例如：小学五年级" maxlength="60" show-word-limit />
        </el-form-item>

        <el-form-item label="课时长度">
          <el-input-number v-model="form.lessonDuration" :min="1" :max="240" />
          <span class="form-hint">分钟</span>
        </el-form-item>

        <el-form-item label="项目描述">
          <el-input
            v-model="form.description"
            type="textarea"
            :rows="4"
            maxlength="240"
            show-word-limit
            placeholder="补充课程背景、课堂目标或希望生成的内容风格"
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
          <el-button type="primary" :loading="submitting" @click="handleSubmit">
            创建并选择生成模式
          </el-button>
          <el-button @click="router.push('/projects')">返回项目列表</el-button>
        </div>
      </el-form>
    </el-card>
  </section>
</template>

<script setup lang="ts">
import StatusCard from '@/components/StatusCard.vue';
import { createProject, type ProjectPayload } from '@/api/projects';
import type { FormInstance, FormRules } from 'element-plus';
import { reactive, ref } from 'vue';
import { useRouter } from 'vue-router';

const router = useRouter();
const formRef = ref<FormInstance>();
const submitting = ref(false);
const errorMessage = ref('');

const form = reactive<ProjectPayload>({
  courseName: '',
  chapterTitle: '',
  targetStudents: '',
  lessonDuration: 40,
  description: '',
});

const rules: FormRules<ProjectPayload> = {
  courseName: [{ required: true, message: '请输入课程名称', trigger: 'blur' }],
  chapterTitle: [{ required: true, message: '请输入章节主题', trigger: 'blur' }],
};

async function handleSubmit() {
  if (!formRef.value) {
    return;
  }

  const valid = await formRef.value.validate().catch(() => false);
  if (!valid) {
    return;
  }

  submitting.value = true;
  errorMessage.value = '';

  try {
    const project = await createProject(form);
    router.push(`/projects/${project.id}/mode`);
  } catch (error) {
    errorMessage.value = '项目创建失败，请确认后端服务已启动并稍后重试。';
  } finally {
    submitting.value = false;
  }
}
</script>
