<template>
  <section class="page template-page">
    <PageHeader
      eyebrow="模板中心"
      title="从教学模板开始"
      description="模板是系统内置的起点，不代表云端模板库。选择后会预填项目表单，并通过真实项目接口创建项目。"
    >
      <template #actions>
        <RouterLink class="page-link" :to="{ name: 'ai-assistant' }">
          <el-icon><MagicStick /></el-icon>
          <span>打开 AI 助手</span>
        </RouterLink>
      </template>
    </PageHeader>

    <StatePanel
      v-if="templates.length === 0"
      type="empty"
      title="暂无系统内置模板"
      description="当前没有可用的模板，请直接创建教学项目。"
    >
      <template #action>
        <el-button type="primary" :icon="Plus" @click="router.push({ name: 'project-create' })">直接新建项目</el-button>
      </template>
    </StatePanel>

    <div v-else class="template-layout">
      <section class="template-list" aria-labelledby="template-list-heading">
        <header class="section-heading">
          <div>
            <h2 id="template-list-heading">系统内置模板</h2>
            <p>{{ templates.length }} 个可直接预填的教学起点</p>
          </div>
          <el-tag type="info" effect="plain">本地内置</el-tag>
        </header>

        <div class="template-grid">
          <article
            v-for="template in templates"
            :key="template.id"
            :class="['template-card', { 'is-selected': selectedTemplate?.id === template.id }]"
          >
            <div class="template-card__topline">
              <span class="template-card__icon"><el-icon><component :is="template.icon" /></el-icon></span>
              <el-tag size="small" type="info" effect="plain">系统内置</el-tag>
            </div>
            <h3>{{ template.name }}</h3>
            <p>{{ template.description }}</p>
            <dl class="template-card__facts">
              <div><dt>适用场景</dt><dd>{{ template.scenario }}</dd></div>
              <div><dt>预填产物</dt><dd>{{ template.outputs }}</dd></div>
            </dl>
            <el-button
              class="template-card__action"
              :type="selectedTemplate?.id === template.id ? 'primary' : 'default'"
              :icon="selectedTemplate?.id === template.id ? Check : EditPen"
              @click="selectTemplate(template)"
            >
              {{ selectedTemplate?.id === template.id ? '已选择，查看预填内容' : '使用此模板' }}
            </el-button>
          </article>
        </div>
      </section>

      <section class="surface-panel template-form-panel" aria-labelledby="template-form-heading">
        <header class="section-heading">
          <div>
            <h2 id="template-form-heading">项目预填内容</h2>
            <p>{{ selectedTemplate ? `已选择：${selectedTemplate.name}` : '先选择一个系统内置模板' }}</p>
          </div>
          <el-icon class="section-heading__icon"><DocumentAdd /></el-icon>
        </header>

        <StatePanel
          v-if="!selectedTemplate"
          type="info"
          title="等待选择模板"
          description="选择模板后，你仍可以修改项目名称、课程和章节，再创建真实项目。"
        />
        <el-form v-else ref="formRef" class="template-form" label-position="top" :model="form" :rules="rules">
          <el-form-item label="项目名称" prop="projectName">
            <el-input v-model="form.projectName" maxlength="200" placeholder="例如：人工智能基础概念与应用" />
          </el-form-item>
          <div class="form-grid">
            <el-form-item label="课程名称" prop="courseName">
              <el-input v-model="form.courseName" placeholder="例如：人工智能基础" />
            </el-form-item>
            <el-form-item label="章节主题" prop="chapterTitle">
              <el-input v-model="form.chapterTitle" placeholder="例如：人工智能的基本概念" />
            </el-form-item>
          </div>
          <div class="form-grid">
            <el-form-item label="授课对象" prop="targetStudents">
              <el-input v-model="form.targetStudents" placeholder="例如：大学本科一年级" />
            </el-form-item>
            <el-form-item label="课时长度（分钟）" prop="lessonDuration">
              <el-input-number v-model="form.lessonDuration" class="full-width" :min="1" :max="1440" :precision="0" controls-position="right" />
            </el-form-item>
          </div>
          <el-form-item label="项目说明" prop="description">
            <el-input v-model="form.description" type="textarea" :rows="4" maxlength="2000" show-word-limit placeholder="项目创建后可在需求流程中继续补充。" />
          </el-form-item>
          <el-alert v-if="errorMessage" type="error" :title="errorMessage" show-icon :closable="false" />
          <div class="form-actions">
            <el-button type="primary" :icon="Plus" :loading="creating" @click="createProjectFromTemplate">创建真实项目并进入流程</el-button>
            <el-button :disabled="creating" @click="resetSelection">重新选择模板</el-button>
          </div>
        </el-form>
      </section>
    </div>
  </section>
</template>

<script setup lang="ts">
import { createProject, type ProjectPayload } from '@/api/projects';
import PageHeader from '@/components/PageHeader.vue';
import StatePanel from '@/components/StatePanel.vue';
import { Check, Collection, DocumentAdd, EditPen, MagicStick, Plus } from '@element-plus/icons-vue';
import { ElMessage, type FormInstance, type FormRules } from 'element-plus';
import { reactive, ref } from 'vue';
import { RouterLink, useRouter } from 'vue-router';

interface SystemTemplate {
  id: string;
  name: string;
  description: string;
  scenario: string;
  outputs: string;
  icon: typeof Collection;
  values: ProjectPayload;
}

const templates: SystemTemplate[] = [
  {
    id: 'lesson-standard',
    name: '标准新课',
    description: '适合从课程目标开始，逐步完成需求、资料、意图和内容生成。',
    scenario: '概念讲授与课堂导入',
    outputs: '课件、教案、互动',
    icon: Collection,
    values: {
      projectName: '标准新课教学设计',
      courseName: '',
      chapterTitle: '',
      targetStudents: '大学本科一年级',
      lessonDuration: 45,
      description: '从教学目标和重点难点出发，形成一套完整的新课教学内容。',
    },
  },
  {
    id: 'lesson-interaction',
    name: '互动练习课',
    description: '适合已有明确主题，需要强化课堂提问、练习和即时反馈的课程。',
    scenario: '课堂检测与互动问答',
    outputs: '课件、互动',
    icon: MagicStick,
    values: {
      projectName: '互动练习课教学设计',
      courseName: '',
      chapterTitle: '',
      targetStudents: '高中学生',
      lessonDuration: 40,
      description: '围绕一个教学主题设计分层练习、课堂提问和反馈环节。',
    },
  },
  {
    id: 'lesson-review',
    name: '复习巩固课',
    description: '适合梳理知识结构、突出易错点并安排课后巩固任务的复习场景。',
    scenario: '知识梳理与迁移练习',
    outputs: '教案、互动',
    icon: DocumentAdd,
    values: {
      projectName: '复习巩固课教学设计',
      courseName: '',
      chapterTitle: '',
      targetStudents: '初中学生',
      lessonDuration: 45,
      description: '围绕知识结构、典型错误和迁移练习组织复习教学流程。',
    },
  },
];

const router = useRouter();
const selectedTemplate = ref<SystemTemplate>();
const formRef = ref<FormInstance>();
const creating = ref(false);
const errorMessage = ref('');
const form = reactive<ProjectPayload>({
  projectName: '',
  courseName: '',
  chapterTitle: '',
  targetStudents: '',
  lessonDuration: 45,
  description: '',
});

const rules: FormRules<ProjectPayload> = {
  projectName: [{ required: true, message: '请输入项目名称', trigger: 'blur' }],
  courseName: [{ required: true, message: '请输入课程名称', trigger: 'blur' }],
  chapterTitle: [{ required: true, message: '请输入章节主题', trigger: 'blur' }],
  targetStudents: [{ required: true, message: '请输入授课对象', trigger: 'blur' }],
  lessonDuration: [{ required: true, type: 'number', min: 1, max: 1440, message: '请输入 1 到 1440 之间的分钟数', trigger: 'change' }],
};

function selectTemplate(template: SystemTemplate) {
  selectedTemplate.value = template;
  Object.assign(form, template.values);
  errorMessage.value = '';
  formRef.value?.clearValidate();
}

function resetSelection() {
  selectedTemplate.value = undefined;
  Object.assign(form, { projectName: '', courseName: '', chapterTitle: '', targetStudents: '', lessonDuration: 45, description: '' });
  errorMessage.value = '';
}

async function createProjectFromTemplate() {
  if (!selectedTemplate.value || !formRef.value || creating.value) return;
  const valid = await formRef.value.validate().catch(() => false);
  if (!valid) return;
  creating.value = true;
  errorMessage.value = '';
  try {
    const project = await createProject({
      projectName: form.projectName?.trim(),
      courseName: form.courseName?.trim() || '',
      chapterTitle: form.chapterTitle?.trim() || '',
      targetStudents: form.targetStudents?.trim(),
      lessonDuration: form.lessonDuration,
      description: form.description?.trim(),
    });
    ElMessage.success('项目已创建');
    await router.push({ name: 'project-mode', params: { projectId: project.id } });
  } catch (error) {
    errorMessage.value = resolveError(error, '项目创建失败，请稍后重试。');
  } finally {
    creating.value = false;
  }
}

function resolveError(error: unknown, fallback: string) {
  const message = (error as { response?: { data?: { message?: string } } }).response?.data?.message;
  return message && !/(Exception|java\.|Axios)/i.test(message) ? message : fallback;
}
</script>

<style scoped>
.template-page { min-width: 0; }
.page-link { display: inline-flex; align-items: center; justify-content: center; gap: 7px; min-height: var(--control-height); padding: 0 14px; border: 1px solid var(--color-primary-border); border-radius: var(--radius-md); background: var(--color-primary-soft); color: var(--color-primary); font-size: 13px; font-weight: 700; text-decoration: none; }
.page-link:hover { border-color: var(--color-primary); color: var(--color-primary-hover); }
.template-layout { display: grid; grid-template-columns: minmax(0, 1.3fr) minmax(340px, 0.7fr); align-items: start; gap: 20px; }
.template-list, .template-form-panel { min-width: 0; }
.template-form-panel { padding: 20px; }
.section-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 16px; }
.section-heading h2 { margin: 0; color: var(--color-text); font-size: 18px; line-height: 1.4; }
.section-heading p { margin: 5px 0 0; color: var(--color-text-muted); font-size: 12px; line-height: 1.5; }
.section-heading__icon { color: var(--color-primary); font-size: 22px; }
.template-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 14px; }
.template-card { display: flex; min-width: 0; flex-direction: column; padding: 17px; border: 1px solid var(--color-border); border-radius: var(--radius-lg); background: var(--color-surface); box-shadow: var(--shadow-card); }
.template-card.is-selected { border-color: var(--color-primary); box-shadow: 0 0 0 2px var(--color-primary-soft); }
.template-card__topline { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
.template-card__icon { display: grid; width: 36px; height: 36px; place-items: center; border-radius: var(--radius-md); background: var(--color-primary-soft); color: var(--color-primary); font-size: 19px; }
.template-card h3 { margin: 16px 0 0; color: var(--color-text); font-size: 16px; }
.template-card > p { min-height: 66px; margin: 7px 0 0; color: var(--color-text-secondary); font-size: 13px; line-height: 1.65; }
.template-card__facts { display: grid; gap: 9px; padding: 12px 0; margin: 14px 0 16px; border-top: 1px solid var(--color-border); border-bottom: 1px solid var(--color-border); }
.template-card__facts div { display: grid; grid-template-columns: 62px minmax(0, 1fr); gap: 8px; }
.template-card__facts dt { color: var(--color-text-muted); font-size: 11px; }
.template-card__facts dd { margin: 0; color: var(--color-text-secondary); font-size: 12px; line-height: 1.45; overflow-wrap: anywhere; }
.template-card__action { width: 100%; margin-top: auto; }
.template-form { display: grid; gap: 0; }
.form-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px; }
.full-width { width: 100%; }
.form-actions { display: flex; flex-wrap: wrap; gap: 10px; margin-top: 18px; }
.form-actions .el-button { flex: 1 1 190px; }
@media (max-width: 1180px) { .template-layout { grid-template-columns: 1fr; } .template-grid { grid-template-columns: repeat(3, minmax(0, 1fr)); } }
@media (max-width: 800px) { .template-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 560px) { .template-grid, .form-grid { grid-template-columns: 1fr; } .template-form-panel { padding: 17px; } .template-card > p { min-height: 0; } .page-link { width: 100%; } .form-actions, .form-actions .el-button { width: 100%; } }
</style>
