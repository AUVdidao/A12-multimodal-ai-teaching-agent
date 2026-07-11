<template>
  <section class="page create-page">
    <PageHeader eyebrow="M1 · 第 1 步" title="创建教学项目" description="先建立课程上下文，后续需求、对话与摘要都会归档到这个项目中。" />
    <M1ProgressSteps :current-step="0" :unlocked-step="0" />

    <div class="create-layout">
      <div class="surface-panel create-form">
        <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @submit.prevent>
          <FormSection :icon="Reading" title="课程信息" description="填写教师最熟悉的课程与章节信息，无需使用技术字段。">
            <div class="form-grid">
              <el-form-item label="课程名称" prop="courseName">
                <el-input v-model="form.courseName" placeholder="例如：初中生物" maxlength="40" show-word-limit />
              </el-form-item>
              <el-form-item label="章节主题" prop="chapterTitle">
                <el-input v-model="form.chapterTitle" placeholder="例如：绿色植物的光合作用" maxlength="80" show-word-limit />
              </el-form-item>
              <el-form-item label="授课对象">
                <el-input v-model="form.targetStudents" placeholder="例如：八年级学生" maxlength="60" show-word-limit />
              </el-form-item>
              <el-form-item label="课时长度">
                <el-input-number v-model="form.lessonDuration" :min="1" :max="240" controls-position="right" />
                <span class="form-hint">分钟</span>
              </el-form-item>
            </div>
          </FormSection>

          <FormSection :icon="Document" title="项目说明" description="可选填写课程背景，帮助团队快速理解本次备课方向。">
            <el-form-item label="补充说明">
              <el-input v-model="form.description" type="textarea" :rows="4" maxlength="240" show-word-limit placeholder="例如：面向校内公开课，希望强调探究活动与生活案例。" />
            </el-form-item>
          </FormSection>

          <el-alert v-if="errorMessage" class="inline-alert" :title="errorMessage" type="warning" show-icon :closable="false" />

          <PrimaryActionBar>
            <template #info>创建完成后将进入生成模式选择，不会立即调用 AI。</template>
            <template #secondary><el-button @click="router.push('/projects')">返回项目列表</el-button></template>
            <el-button type="primary" :icon="Right" :loading="submitting" :disabled="submitting" @click="handleSubmit">创建并继续</el-button>
          </PrimaryActionBar>
        </el-form>
      </div>

      <aside class="creation-guide" aria-label="创建项目说明">
        <span>为什么先创建项目</span>
        <h2>让每一次教学共创都有清晰上下文</h2>
        <ul>
          <li><el-icon><CircleCheck /></el-icon><div><strong>统一归档</strong><p>需求版本、澄清记录和确认摘要都归属于同一项目。</p></div></li>
          <li><el-icon><CircleCheck /></el-icon><div><strong>随时继续</strong><p>从项目列表重新进入，已保存内容不会丢失。</p></div></li>
          <li><el-icon><CircleCheck /></el-icon><div><strong>边界清楚</strong><p>当前只完成需求澄清，资料与内容生成仍属于下一阶段。</p></div></li>
        </ul>
      </aside>
    </div>
  </section>
</template>

<script setup lang="ts">
import { createProject, type ProjectPayload } from '@/api/projects';
import FormSection from '@/components/FormSection.vue';
import M1ProgressSteps from '@/components/M1ProgressSteps.vue';
import PageHeader from '@/components/PageHeader.vue';
import PrimaryActionBar from '@/components/PrimaryActionBar.vue';
import { CircleCheck, Document, Reading, Right } from '@element-plus/icons-vue';
import { ElMessage, type FormInstance, type FormRules } from 'element-plus';
import { reactive, ref } from 'vue';
import { useRouter } from 'vue-router';

const router = useRouter();
const formRef = ref<FormInstance>();
const submitting = ref(false);
const errorMessage = ref('');
const form = reactive<ProjectPayload>({ courseName: '', chapterTitle: '', targetStudents: '', lessonDuration: 40, description: '' });
const rules: FormRules<ProjectPayload> = {
  courseName: [{ required: true, message: '请填写课程名称', trigger: 'blur' }],
  chapterTitle: [{ required: true, message: '请填写章节主题', trigger: 'blur' }],
};

async function handleSubmit() {
  if (!formRef.value || submitting.value) return;
  const valid = await formRef.value.validate().catch(() => false);
  if (!valid) return;
  submitting.value = true;
  errorMessage.value = '';
  try {
    const project = await createProject(form);
    ElMessage.success('教学项目已创建');
    router.push(`/projects/${project.id}/mode`);
  } catch {
    errorMessage.value = '项目创建失败，请检查服务状态后重试。';
  } finally {
    submitting.value = false;
  }
}
</script>

<style scoped>
.create-layout {
  display: grid;
  grid-template-columns: minmax(0, 1.45fr) minmax(280px, 0.55fr);
  align-items: start;
  gap: 24px;
}

.create-form {
  padding: 26px;
}

.form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 16px;
}

.creation-guide {
  padding: 24px 0 24px 24px;
  border-left: 1px solid var(--color-border);
}

.creation-guide > span {
  color: var(--color-primary);
  font-size: 11px;
  font-weight: 800;
}

.creation-guide h2 {
  margin: 7px 0 20px;
  font-size: 18px;
  line-height: 1.45;
}

.creation-guide ul {
  display: grid;
  gap: 20px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.creation-guide li {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  color: var(--color-success);
}

.creation-guide strong,
.creation-guide p {
  display: block;
  margin: 0;
}

.creation-guide strong {
  color: var(--color-text);
  font-size: 13px;
}

.creation-guide p {
  margin-top: 4px;
  color: var(--color-text-muted);
  font-size: 11px;
  line-height: 1.6;
}

@media (max-width: 900px) {
  .create-layout {
    grid-template-columns: 1fr;
  }

  .creation-guide {
    padding: 22px 0 0;
    border-top: 1px solid var(--color-border);
    border-left: 0;
  }
}

@media (max-width: 640px) {
  .create-form {
    padding: 18px;
  }

  .form-grid {
    grid-template-columns: 1fr;
  }
}
</style>
