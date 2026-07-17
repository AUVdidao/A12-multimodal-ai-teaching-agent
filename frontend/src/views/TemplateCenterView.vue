<template>
  <section class="page template-page">
    <PageHeader
      eyebrow="模板中心"
      title="从系统内置模板开始"
      description="按学科、教学场景和目标产物筛选稳定预设。模板只负责预填，项目仍通过真实项目接口创建。"
    >
      <template #actions>
        <el-button :icon="MagicStick" plain @click="router.push({ name: 'ai-assistant' })">打开 AI 助手</el-button>
      </template>
    </PageHeader>

    <section class="surface-panel template-toolbar" aria-label="模板筛选">
      <el-input
        v-model="searchText"
        class="template-search"
        clearable
        :prefix-icon="Search"
        placeholder="搜索模板名称、标签或预填内容"
      />
      <el-select v-model="subjectFilter" clearable placeholder="全部学科">
        <el-option v-for="subject in subjects" :key="subject" :label="subject" :value="subject" />
      </el-select>
      <el-select v-model="scenarioFilter" clearable placeholder="全部场景">
        <el-option v-for="scenario in scenarios" :key="scenario" :label="scenario" :value="scenario" />
      </el-select>
      <el-select v-model="outputFilter" clearable placeholder="全部产物">
        <el-option v-for="option in outputOptions" :key="option.value" :label="option.label" :value="option.value" />
      </el-select>
      <el-button :icon="RefreshLeft" :disabled="!filtersActive" @click="resetFilters">重置</el-button>
    </section>

    <StatePanel
      v-if="filteredTemplates.length === 0"
      type="empty"
      title="没有符合条件的系统内置模板"
      description="清除筛选后重新选择，或直接进入真实项目新建页从空白项目开始。"
    >
      <template #action>
        <div class="empty-actions">
          <el-button :icon="RefreshLeft" @click="resetFilters">清除筛选</el-button>
          <el-button type="primary" :icon="Plus" @click="router.push({ name: 'project-create' })">新建空白项目</el-button>
        </div>
      </template>
    </StatePanel>

    <div v-else class="template-layout">
      <section class="template-catalog" aria-labelledby="template-list-heading">
        <header class="section-heading">
          <div>
            <h2 id="template-list-heading">模板目录</h2>
            <p>显示 {{ filteredTemplates.length }} / {{ templates.length }} 个，全部为系统内置模板</p>
          </div>
          <el-tag type="info" effect="plain">非云端模板</el-tag>
        </header>

        <div class="template-grid">
          <article
            v-for="template in filteredTemplates"
            :key="template.id"
            :class="['template-card', { 'is-selected': selectedTemplate?.id === template.id }]"
          >
            <header class="template-card__header">
              <span class="template-card__icon"><el-icon><component :is="template.icon" /></el-icon></span>
              <div>
                <span>{{ template.subject }} · {{ template.scenario }}</span>
                <h3>{{ template.name }}</h3>
              </div>
              <el-icon v-if="selectedTemplate?.id === template.id" class="template-card__selected"><Check /></el-icon>
            </header>
            <p>{{ template.description }}</p>
            <div class="template-card__tags" aria-label="模板标签">
              <el-tag size="small" effect="plain">{{ template.subject }}</el-tag>
              <el-tag size="small" type="info" effect="plain">{{ template.scenario }}</el-tag>
              <span v-for="output in template.outputTypes" :key="output">{{ outputLabel(output) }}</span>
            </div>
            <div class="template-card__footer">
              <small>{{ template.lessonLabel }}</small>
              <el-button
                :type="selectedTemplate?.id === template.id ? 'primary' : 'default'"
                :icon="selectedTemplate?.id === template.id ? Check : EditPen"
                plain
                @click="selectTemplate(template)"
              >
                {{ selectedTemplate?.id === template.id ? '已选择' : '选择模板' }}
              </el-button>
            </div>
          </article>
        </div>
      </section>

      <aside class="surface-panel template-preview" aria-labelledby="template-preview-heading">
        <template v-if="selectedTemplate">
          <header class="section-heading template-preview__heading">
            <div>
              <span class="section-heading__eyebrow">系统内置预设</span>
              <h2 id="template-preview-heading">{{ selectedTemplate.name }}</h2>
              <p>{{ selectedTemplate.subject }} · {{ selectedTemplate.scenario }} · {{ selectedTemplate.lessonLabel }}</p>
            </div>
            <el-icon class="section-heading__icon"><DocumentAdd /></el-icon>
          </header>

          <div class="preset-preview">
            <strong>预填重点</strong>
            <ul>
              <li v-for="item in selectedTemplate.preview" :key="item">{{ item }}</li>
            </ul>
            <div class="preset-preview__outputs">
              <span>目标产物</span>
              <el-tag v-for="output in selectedTemplate.outputTypes" :key="output" size="small" effect="plain">
                {{ outputLabel(output) }}
              </el-tag>
            </div>
          </div>

          <el-form ref="formRef" class="template-form" label-position="top" :model="form" :rules="rules">
            <el-form-item label="项目名称" prop="projectName">
              <el-input v-model="form.projectName" maxlength="200" placeholder="请输入项目名称" />
            </el-form-item>
            <div class="form-grid">
              <el-form-item label="课程名称" prop="courseName">
                <el-input v-model="form.courseName" placeholder="请输入真实课程名称" />
              </el-form-item>
              <el-form-item label="章节主题" prop="chapterTitle">
                <el-input v-model="form.chapterTitle" placeholder="请输入真实章节主题" />
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
              <el-input v-model="form.description" type="textarea" :rows="4" maxlength="2000" show-word-limit />
            </el-form-item>
            <el-alert v-if="errorMessage" type="error" :title="errorMessage" show-icon :closable="false" />
            <div class="form-actions">
              <el-button type="primary" :icon="Plus" :loading="creating" @click="createProjectFromTemplate">
                创建项目并进入流程
              </el-button>
              <el-button :icon="RefreshLeft" :disabled="creating" @click="restorePreset">恢复预填</el-button>
            </div>
          </el-form>
        </template>
      </aside>
    </div>
  </section>
</template>

<script setup lang="ts">
import { createProject, type ProjectPayload } from '@/api/projects';
import PageHeader from '@/components/PageHeader.vue';
import StatePanel from '@/components/StatePanel.vue';
import {
  Check,
  Collection,
  DocumentAdd,
  EditPen,
  Files,
  MagicStick,
  Plus,
  Reading,
  RefreshLeft,
  Search,
} from '@element-plus/icons-vue';
import { ElMessage, type FormInstance, type FormRules } from 'element-plus';
import { computed, nextTick, reactive, ref, type Component } from 'vue';
import { useRouter } from 'vue-router';

type TemplateOutput = 'PPT' | 'DOCX' | 'INTERACTION';

interface SystemTemplate {
  id: string;
  name: string;
  description: string;
  subject: string;
  scenario: string;
  lessonLabel: string;
  outputTypes: TemplateOutput[];
  preview: string[];
  icon: Component;
  values: ProjectPayload;
}

const outputOptions: Array<{ value: TemplateOutput; label: string }> = [
  { value: 'PPT', label: '课件 PPT' },
  { value: 'DOCX', label: 'Word 教案' },
  { value: 'INTERACTION', label: '互动内容' },
];

const templates: SystemTemplate[] = [
  {
    id: 'lesson-standard',
    name: '标准新课设计',
    description: '从目标、重点难点到课堂活动，适合作为完整新课流程的通用起点。',
    subject: '通用',
    scenario: '新课讲授',
    lessonLabel: '建议 45 分钟',
    outputTypes: ['PPT', 'DOCX', 'INTERACTION'],
    preview: ['明确知识与能力目标', '组织导入、讲解、练习和总结', '预留课堂互动与即时反馈'],
    icon: Collection,
    values: {
      projectName: '标准新课教学设计',
      courseName: '',
      chapterTitle: '',
      targetStudents: '大学本科一年级',
      lessonDuration: 45,
      description: '围绕教学目标、重点难点和课堂活动，形成完整的新课教学内容。',
    },
  },
  {
    id: 'lesson-inquiry',
    name: '探究实验课',
    description: '围绕问题、假设、观察与结论组织探究过程，适合理工科实验场景。',
    subject: '理工科',
    scenario: '探究实验',
    lessonLabel: '建议 50 分钟',
    outputTypes: ['PPT', 'DOCX'],
    preview: ['提出可验证的问题', '分解实验步骤与安全提示', '安排证据记录和结论表达'],
    icon: Files,
    values: {
      projectName: '探究实验课教学设计',
      courseName: '科学探究',
      chapterTitle: '',
      targetStudents: '高中学生',
      lessonDuration: 50,
      description: '以问题驱动实验探究，包含实验步骤、观察记录、证据分析与结论表达。',
    },
  },
  {
    id: 'lesson-interaction',
    name: '互动练习课',
    description: '强化分层提问、课堂练习和即时反馈，适合已有明确知识主题的课堂。',
    subject: '通用',
    scenario: '课堂练习',
    lessonLabel: '建议 40 分钟',
    outputTypes: ['PPT', 'INTERACTION'],
    preview: ['按难度组织分层练习', '设置课堂提问与反馈节点', '依据学生回答安排讲评'],
    icon: MagicStick,
    values: {
      projectName: '互动练习课教学设计',
      courseName: '',
      chapterTitle: '',
      targetStudents: '高中学生',
      lessonDuration: 40,
      description: '围绕一个真实教学主题设计分层练习、课堂提问和即时反馈。',
    },
  },
  {
    id: 'lesson-review',
    name: '复习巩固课',
    description: '梳理知识结构、辨析易错点并安排迁移练习，适合单元或阶段复习。',
    subject: '通用',
    scenario: '复习巩固',
    lessonLabel: '建议 45 分钟',
    outputTypes: ['DOCX', 'INTERACTION'],
    preview: ['构建知识结构', '聚焦典型错误与辨析', '安排迁移练习和课后巩固'],
    icon: Reading,
    values: {
      projectName: '复习巩固课教学设计',
      courseName: '',
      chapterTitle: '',
      targetStudents: '初中学生',
      lessonDuration: 45,
      description: '围绕知识结构、典型错误和迁移练习组织复习教学流程。',
    },
  },
  {
    id: 'lesson-case',
    name: '案例研讨课',
    description: '以真实案例材料组织分析、观点表达和课堂讨论，适合人文社科课程。',
    subject: '人文社科',
    scenario: '案例研讨',
    lessonLabel: '建议 60 分钟',
    outputTypes: ['PPT', 'DOCX', 'INTERACTION'],
    preview: ['建立案例背景与问题线索', '设置分组分析任务', '组织观点比较与教师总结'],
    icon: DocumentAdd,
    values: {
      projectName: '案例研讨课教学设计',
      courseName: '',
      chapterTitle: '',
      targetStudents: '大学本科生',
      lessonDuration: 60,
      description: '基于案例材料组织问题分析、小组研讨、观点表达和课堂总结。',
    },
  },
  {
    id: 'lesson-language-task',
    name: '语言任务课',
    description: '以真实语境任务串联输入、练习和表达，适合语言学习课堂。',
    subject: '语言学习',
    scenario: '任务教学',
    lessonLabel: '建议 45 分钟',
    outputTypes: ['PPT', 'INTERACTION'],
    preview: ['设置真实语境任务', '安排输入理解与表达练习', '提供评价标准和同伴反馈'],
    icon: EditPen,
    values: {
      projectName: '语言任务课教学设计',
      courseName: '语言学习',
      chapterTitle: '',
      targetStudents: '中学生',
      lessonDuration: 45,
      description: '在真实语境中组织输入理解、语言练习、任务表达和同伴反馈。',
    },
  },
];

const router = useRouter();
const searchText = ref('');
const subjectFilter = ref('');
const scenarioFilter = ref('');
const outputFilter = ref<TemplateOutput | ''>('');
const selectedTemplate = ref<SystemTemplate>(templates[0]);
const formRef = ref<FormInstance>();
const creating = ref(false);
const errorMessage = ref('');
const form = reactive<ProjectPayload>({ ...templates[0].values });

const subjects = computed(() => [...new Set(templates.map((template) => template.subject))]);
const scenarios = computed(() => [...new Set(templates.map((template) => template.scenario))]);
const filtersActive = computed(() => Boolean(searchText.value.trim() || subjectFilter.value || scenarioFilter.value || outputFilter.value));
const filteredTemplates = computed(() => {
  const keyword = searchText.value.trim().toLocaleLowerCase();
  return templates.filter((template) => {
    const searchable = [
      template.name,
      template.description,
      template.subject,
      template.scenario,
      template.lessonLabel,
      ...template.preview,
      ...template.outputTypes.map(outputLabel),
    ].join(' ').toLocaleLowerCase();
    return (!keyword || searchable.includes(keyword))
      && (!subjectFilter.value || template.subject === subjectFilter.value)
      && (!scenarioFilter.value || template.scenario === scenarioFilter.value)
      && (!outputFilter.value || template.outputTypes.includes(outputFilter.value));
  });
});

const rules: FormRules<ProjectPayload> = {
  projectName: [{ required: true, message: '请输入项目名称', trigger: 'blur' }],
  courseName: [{ required: true, message: '请输入真实课程名称', trigger: 'blur' }],
  chapterTitle: [{ required: true, message: '请输入真实章节主题', trigger: 'blur' }],
  targetStudents: [{ required: true, message: '请输入授课对象', trigger: 'blur' }],
  lessonDuration: [{ required: true, type: 'number', min: 1, max: 1440, message: '请输入 1 到 1440 之间的分钟数', trigger: 'change' }],
};

function outputLabel(output: TemplateOutput) {
  return outputOptions.find((option) => option.value === output)?.label || output;
}

function selectTemplate(template: SystemTemplate) {
  selectedTemplate.value = template;
  restorePreset();
}

function restorePreset() {
  if (!selectedTemplate.value) return;
  Object.assign(form, selectedTemplate.value.values);
  errorMessage.value = '';
  void nextTick(() => formRef.value?.clearValidate());
}

function resetFilters() {
  searchText.value = '';
  subjectFilter.value = '';
  scenarioFilter.value = '';
  outputFilter.value = '';
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
      courseName: form.courseName.trim(),
      chapterTitle: form.chapterTitle.trim(),
      targetStudents: form.targetStudents?.trim(),
      lessonDuration: form.lessonDuration,
      description: form.description?.trim(),
    });
    ElMessage.success(`已使用“${selectedTemplate.value.name}”创建项目`);
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
.template-toolbar { display: grid; grid-template-columns: minmax(240px, 1.45fr) repeat(3, minmax(130px, 0.65fr)) auto; gap: 10px; padding: 14px; margin-bottom: 16px; }
.template-toolbar > * { min-width: 0; }
.template-layout { display: grid; grid-template-columns: minmax(0, 1.3fr) minmax(360px, 0.7fr); align-items: start; gap: 18px; }
.template-catalog, .template-preview { min-width: 0; }
.template-preview { position: sticky; top: 0; padding: 18px; }
.section-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 14px; margin-bottom: 14px; }
.section-heading h2 { margin: 0; color: var(--color-text); font-size: 18px; line-height: 1.4; }
.section-heading p { margin: 4px 0 0; color: var(--color-text-muted); font-size: 12px; line-height: 1.5; }
.section-heading__eyebrow { display: block; margin-bottom: 3px; color: var(--color-primary); font-size: 11px; font-weight: 700; }
.section-heading__icon { flex: 0 0 auto; color: var(--color-primary); font-size: 22px; }
.template-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 11px; }
.template-card { display: flex; min-width: 0; flex-direction: column; padding: 14px; border: 1px solid var(--color-border); border-radius: var(--radius-md); background: var(--color-surface); transition: border-color 140ms ease, box-shadow 140ms ease; }
.template-card:hover { border-color: var(--color-primary-border); box-shadow: var(--shadow-card); }
.template-card.is-selected { border-color: var(--color-primary); box-shadow: 0 0 0 2px var(--color-primary-soft); }
.template-card__header { display: grid; grid-template-columns: 34px minmax(0, 1fr) auto; align-items: center; gap: 10px; }
.template-card__icon { display: grid; width: 34px; height: 34px; place-items: center; border-radius: var(--radius-md); background: var(--color-primary-soft); color: var(--color-primary); font-size: 18px; }
.template-card__header span { color: var(--color-text-muted); font-size: 10px; }
.template-card h3 { margin: 2px 0 0; color: var(--color-text); font-size: 15px; }
.template-card__selected { color: var(--color-primary); font-size: 18px; }
.template-card > p { margin: 10px 0 0; color: var(--color-text-secondary); font-size: 12px; line-height: 1.55; }
.template-card__tags { display: flex; align-items: center; flex-wrap: wrap; gap: 6px; margin-top: 11px; }
.template-card__tags > span { padding: 3px 7px; border-radius: 5px; background: var(--color-surface-subtle); color: var(--color-text-secondary); font-size: 10px; }
.template-card__footer { display: flex; align-items: center; justify-content: space-between; gap: 10px; padding-top: 11px; margin-top: auto; }
.template-card__footer small { color: var(--color-text-muted); font-size: 10px; }
.template-card__footer .el-button { flex: 0 0 auto; }
.preset-preview { padding: 12px; margin-bottom: 16px; border: 1px solid var(--color-border); border-radius: var(--radius-md); background: var(--color-surface-subtle); }
.preset-preview > strong { color: var(--color-text); font-size: 12px; }
.preset-preview ul { display: grid; gap: 5px; padding-left: 18px; margin: 8px 0 12px; color: var(--color-text-secondary); font-size: 12px; line-height: 1.5; }
.preset-preview__outputs { display: flex; align-items: center; flex-wrap: wrap; gap: 6px; padding-top: 10px; border-top: 1px solid var(--color-border); }
.preset-preview__outputs > span { margin-right: 3px; color: var(--color-text-muted); font-size: 11px; }
.template-form { display: grid; gap: 0; }
.form-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; }
.full-width { width: 100%; }
.form-actions, .empty-actions { display: flex; flex-wrap: wrap; gap: 9px; }
.form-actions { margin-top: 14px; }
.form-actions .el-button { flex: 1 1 180px; }
@media (max-width: 1180px) { .template-toolbar { grid-template-columns: minmax(240px, 1fr) repeat(2, minmax(140px, 0.55fr)); } .template-toolbar > :nth-child(4) { grid-column: 1 / 2; } .template-layout { grid-template-columns: 1fr; } .template-preview { position: static; } }
@media (max-width: 760px) { .template-toolbar { grid-template-columns: repeat(2, minmax(0, 1fr)); } .template-search { grid-column: 1 / -1; } .template-toolbar > :nth-child(4) { grid-column: auto; } .template-grid { grid-template-columns: 1fr; } }
@media (max-width: 520px) { .template-toolbar, .form-grid { grid-template-columns: 1fr; } .template-search { grid-column: auto; } .template-toolbar .el-button, .form-actions, .form-actions .el-button, .empty-actions, .empty-actions .el-button { width: 100%; } .template-preview { padding: 15px; } .template-card__footer { align-items: stretch; flex-direction: column; } .template-card__footer .el-button { width: 100%; } }
</style>
