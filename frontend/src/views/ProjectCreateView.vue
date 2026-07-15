<template>
  <section class="page">
    <header class="page-hero">
      <div>
        <h2>新建教学项目</h2>
        <p>先建立项目容器，后续需求、资料、知识和教学意图都会沉淀在同一个项目中。</p>
      </div>
      <el-button @click="router.push('/projects')">返回项目列表</el-button>
    </header>

    <div class="grid cols-2">
      <el-card shadow="never">
        <el-form ref="formRef" label-position="top" :model="form" :rules="rules">
          <el-form-item label="项目名称" prop="projectName">
            <el-input v-model="form.projectName" placeholder="例如：人工智能基础概念与应用" />
          </el-form-item>
          <el-form-item label="课程名称" prop="courseName">
            <el-input v-model="form.courseName" placeholder="例如：人工智能基础" />
          </el-form-item>
          <el-form-item label="章节主题" prop="chapterTitle">
            <el-input v-model="form.chapterTitle" placeholder="例如：人工智能的基本概念" />
          </el-form-item>
          <el-form-item label="授课对象" prop="targetStudents">
            <el-input v-model="form.targetStudents" placeholder="例如：大学本科一年级" />
          </el-form-item>
          <el-form-item label="课时长度（分钟）" prop="lessonDuration">
            <el-input-number v-model="form.lessonDuration" :min="1" :max="1440" :precision="0" controls-position="right" />
          </el-form-item>
          <div class="page-actions">
            <el-button type="primary" :loading="creating" @click="createProjectAndEnter">创建并进入项目</el-button>
            <el-button :disabled="creating" @click="router.push('/projects')">取消</el-button>
          </div>
          <el-alert v-if="errorMessage" :title="errorMessage" type="error" show-icon :closable="false" />
        </el-form>
      </el-card>

      <section class="panel">
        <h3>创建后流程</h3>
        <p>项目创建完成后，先选择生成模式，再逐步完善教学需求、资料与教学意图。</p>
        <div class="step-strip" style="grid-template-columns: 1fr; margin-top: 18px">
          <span class="is-active">创建项目</span>
          <span>选择模式</span>
          <span>教学需求与澄清</span>
          <span>资料与知识增强</span>
          <span>教学意图确认</span>
        </div>
      </section>
    </div>
  </section>
</template>

<script setup lang="ts">
import { createProject } from '@/api/projects';
import { ElMessage, type FormInstance, type FormRules } from 'element-plus';
import { reactive, ref } from 'vue';
import { useRouter } from 'vue-router';

const router = useRouter();
const formRef = ref<FormInstance>();
const creating = ref(false);
const errorMessage = ref('');
const form = reactive({
  projectName: '',
  courseName: '',
  chapterTitle: '',
  targetStudents: '',
  lessonDuration: 90,
});

const rules: FormRules<typeof form> = {
  projectName: [{ required: true, message: '请输入项目名称', trigger: 'blur' }],
  courseName: [{ required: true, message: '请输入课程名称', trigger: 'blur' }],
  chapterTitle: [{ required: true, message: '请输入章节主题', trigger: 'blur' }],
  targetStudents: [{ required: true, message: '请输入授课对象', trigger: 'blur' }],
  lessonDuration: [{ required: true, type: 'number', min: 1, max: 1440, message: '请输入 1 到 1440 之间的分钟数', trigger: 'change' }],
};

async function createProjectAndEnter() {
  if (!formRef.value || creating.value) return;
  const valid = await formRef.value.validate().catch(() => false);
  if (!valid) return;

  creating.value = true;
  errorMessage.value = '';
  try {
    const project = await createProject({
      projectName: form.projectName.trim(),
      courseName: form.courseName.trim(),
      chapterTitle: form.chapterTitle.trim(),
      targetStudents: form.targetStudents.trim(),
      lessonDuration: form.lessonDuration,
    });
    ElMessage.success('项目已创建');
    await router.push({ name: 'project-mode', params: { projectId: project.id } });
  } catch (error) {
    errorMessage.value = resolveError(error, '项目创建失败，请检查填写内容后重试。');
  } finally {
    creating.value = false;
  }
}

function resolveError(error: unknown, fallback: string) {
  const message = (error as { response?: { data?: { message?: string } } }).response?.data?.message;
  return message && !/(Exception|java\.|Axios)/i.test(message) ? message : fallback;
}
</script>
