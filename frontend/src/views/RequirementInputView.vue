<template>
  <section class="page" v-loading="loading">
    <template v-if="workspace">
      <ProjectContextHeader :project="workspace.project" />
      <ProjectWorkspaceNav :project-id="workspace.project.id" />

      <div class="requirement-layout">
        <section class="panel requirement-chat">
          <div class="panel__header">
            <div>
              <h3>教学需求与智能澄清</h3>
              <p>系统按九项关键信息识别缺口，并保存每轮沟通记录。</p>
            </div>
            <div class="inline-actions">
              <el-button @click="formVisible = true">编辑需求</el-button>
              <el-button @click="clearDialogues" :disabled="workspace.dialogues.length === 0">清空对话</el-button>
              <el-button type="primary" :disabled="!workspace.canGenerateSummary" @click="router.push(`/projects/${projectId}/summary`)">需求摘要</el-button>
            </div>
          </div>

          <el-alert title="AI 助教会围绕未完成字段继续追问；回答将同步保存到需求草稿。" type="info" show-icon :closable="false" />

          <div ref="chatList" class="chat-list">
            <UiChatMessage
              v-for="message in displayMessages"
              :key="message.key"
              :role="message.role"
              :content="message.content"
              :time="message.time"
            />
          </div>

          <el-input
            v-model="draft"
            type="textarea"
            :rows="4"
            maxlength="2000"
            show-word-limit
            :placeholder="currentQuestion || '请输入您的教学需求或回复 AI 的问题...'"
            @keydown.ctrl.enter.prevent="sendMessage"
          />
          <div class="page-actions">
            <el-button type="primary" :loading="sending" :disabled="!draft.trim()" @click="sendMessage">发送回复</el-button>
            <span class="muted">Ctrl + Enter 快速发送</span>
          </div>
        </section>

        <aside class="grid requirement-aside">
          <section class="panel completeness-panel">
            <div class="completeness-ring" :style="{ '--percent': `${workspace.completeness.percentage * 3.6}deg` }">
              <strong>{{ workspace.completeness.percentage }}%</strong>
            </div>
            <div>
              <h3>需求完善进度</h3>
              <p>已收集 {{ workspace.completeness.collected }}/{{ workspace.completeness.total }} 项关键信息</p>
              <span class="tag-soft" :class="workspace.canGenerateSummary ? 'success' : 'warning'">
                {{ workspace.canGenerateSummary ? '可生成摘要' : '继续补充' }}
              </span>
            </div>
          </section>

          <section class="panel">
            <h3>关键信息收集</h3>
            <div v-for="field in workspace.completeness.fields" :key="field.code" class="field-row">
              <span>{{ field.label }}</span>
              <strong :title="field.value || '待补充'">{{ field.value || '待补充' }}</strong>
              <span :class="['field-state', field.completed ? 'is-complete' : 'is-missing']">{{ field.completed ? '✓' : '!' }}</span>
            </div>
          </section>

          <section class="panel">
            <h3>AI 建议追问</h3>
            <button v-for="question in workspace.suggestedQuestions" :key="question" class="question-button" type="button" @click="draft = question">
              <span>{{ question }}</span><b>+</b>
            </button>
            <el-empty v-if="workspace.suggestedQuestions.length === 0" description="关键信息已收集完整" :image-size="56" />
          </section>
        </aside>
      </div>

      <el-dialog v-model="formVisible" title="编辑结构化教学需求" width="min(760px, 92vw)">
        <el-form label-position="top" class="requirement-form">
          <el-form-item label="课程主题"><el-input v-model="form.topic" /></el-form-item>
          <el-form-item label="学科"><el-input v-model="form.subject" /></el-form-item>
          <el-form-item label="授课对象"><el-input v-model="form.gradeLevel" /></el-form-item>
          <el-form-item label="基础水平"><el-input v-model="form.baselineLevel" /></el-form-item>
          <el-form-item label="课时长度"><el-input v-model="form.lessonDuration" /></el-form-item>
          <el-form-item label="教学目标"><el-input v-model="form.teachingGoals" type="textarea" :rows="2" /></el-form-item>
          <el-form-item label="重点与难点"><el-input v-model="form.difficultPoints" type="textarea" :rows="2" /></el-form-item>
          <el-form-item label="教学风格"><el-input v-model="form.stylePreference" /></el-form-item>
          <el-form-item label="互动设计"><el-input v-model="form.interactionType" /></el-form-item>
          <el-form-item label="输出类型">
            <el-checkbox-group v-model="form.outputTypes">
              <el-checkbox label="PPT">教学 PPT</el-checkbox>
              <el-checkbox label="DOCX">Word 教案</el-checkbox>
              <el-checkbox label="INTERACTION">互动内容</el-checkbox>
            </el-checkbox-group>
          </el-form-item>
          <el-form-item class="requirement-form__wide" label="原始需求"><el-input v-model="form.rawRequirementText" type="textarea" :rows="3" /></el-form-item>
        </el-form>
        <template #footer>
          <el-button @click="formVisible = false">取消</el-button>
          <el-button type="primary" :loading="saving" @click="saveForm">保存草稿</el-button>
        </template>
      </el-dialog>
    </template>
  </section>
</template>

<script setup lang="ts">
import { getClarificationQuestions } from '@/api/clarification';
import { clearProjectDialogues, saveDialogueMessage } from '@/api/dialogues';
import { saveTeachingRequirement, type TeachingRequirementPayload } from '@/api/requirements';
import { getRequirementWorkspace, type RequirementWorkspace } from '@/api/workspace';
import ProjectContextHeader from '@/components/ProjectContextHeader.vue';
import ProjectWorkspaceNav from '@/components/ProjectWorkspaceNav.vue';
import UiChatMessage from '@/components/ui/UiChatMessage.vue';
import { formatDateTime } from '@/utils/presentation';
import { ElMessage, ElMessageBox } from 'element-plus';
import { computed, nextTick, onMounted, reactive, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';

const route = useRoute();
const router = useRouter();
const projectId = computed(() => Number(route.params.projectId));
const workspace = ref<RequirementWorkspace>();
const loading = ref(true);
const saving = ref(false);
const sending = ref(false);
const draft = ref('');
const formVisible = ref(false);
const chatList = ref<HTMLElement>();
const sessionId = computed(() => `project-${projectId.value}-requirement`);
const form = reactive<TeachingRequirementPayload>(emptyRequirement());

const currentQuestion = computed(() => workspace.value?.suggestedQuestions?.[0] || '');
const displayMessages = computed(() => {
  const messages = (workspace.value?.dialogues || []).map((message) => ({
    key: String(message.id),
    role: message.sender === 'TEACHER' ? 'teacher' as const : 'ai' as const,
    content: message.content,
    time: formatDateTime(message.createdAt),
  }));
  if (messages.length === 0 && currentQuestion.value) {
    messages.push({ key: 'suggested-question', role: 'ai', content: currentQuestion.value, time: '现在' });
  }
  return messages;
});

function emptyRequirement(): TeachingRequirementPayload {
  return {
    gradeLevel: '',
    subject: '',
    topic: '',
    baselineLevel: '',
    lessonDuration: '',
    teachingGoals: '',
    keyPoints: '',
    difficultPoints: '',
    stylePreference: '',
    interactionType: '',
    outputTypes: [],
    rawRequirementText: '',
  };
}

function syncForm() {
  Object.assign(form, emptyRequirement(), workspace.value?.latestRequirement || {});
  form.outputTypes = [...(workspace.value?.latestRequirement?.outputTypes || [])];
}

async function loadWorkspace() {
  loading.value = true;
  try {
    workspace.value = await getRequirementWorkspace(projectId.value);
    syncForm();
    await nextTick();
    chatList.value?.scrollTo({ top: chatList.value.scrollHeight });
  } finally {
    loading.value = false;
  }
}

async function saveForm() {
  if (!form.topic?.trim() && !form.rawRequirementText?.trim()) {
    ElMessage.warning('课程主题与原始需求至少填写一项');
    return;
  }
  saving.value = true;
  try {
    await saveTeachingRequirement(projectId.value, { ...form, outputTypes: [...form.outputTypes] });
    formVisible.value = false;
    await loadWorkspace();
    ElMessage.success('需求草稿已保存');
  } finally {
    saving.value = false;
  }
}

function applyAnswerToMissingField(content: string) {
  const code = workspace.value?.completeness.fields.find((field) => !field.completed)?.code;
  const mappings: Record<string, keyof TeachingRequirementPayload> = {
    topic: 'topic',
    teachingGoals: 'teachingGoals',
    audience: 'gradeLevel',
    baselineLevel: 'baselineLevel',
    lessonDuration: 'lessonDuration',
    keyDifficulties: 'difficultPoints',
    stylePreference: 'stylePreference',
    interactionType: 'interactionType',
  };
  const field = code ? mappings[code] : undefined;
  if (field) form[field] = content as never;
  form.rawRequirementText = [form.rawRequirementText, content].filter(Boolean).join('\n');
}

async function sendMessage() {
  const content = draft.value.trim();
  if (!content || sending.value) return;
  sending.value = true;
  try {
    const nextRound = Math.max(0, ...(workspace.value?.dialogues.map((item) => item.roundNo) || [0])) + 1;
    await saveDialogueMessage(projectId.value, { sessionId: sessionId.value, sender: 'TEACHER', content, roundNo: nextRound });
    applyAnswerToMissingField(content);
    await saveTeachingRequirement(projectId.value, { ...form, outputTypes: [...form.outputTypes] });
    const clarification = await getClarificationQuestions(projectId.value, form);
    const question = clarification.questions[0];
    if (question) {
      await saveDialogueMessage(projectId.value, { sessionId: sessionId.value, sender: 'AI', content: question, roundNo: nextRound });
    }
    draft.value = '';
    await loadWorkspace();
  } finally {
    sending.value = false;
  }
}

async function clearDialogues() {
  await ElMessageBox.confirm('将清空当前项目的全部澄清对话，结构化需求草稿会保留。', '清空对话', { type: 'warning' });
  await clearProjectDialogues(projectId.value);
  await loadWorkspace();
  ElMessage.success('对话已清空');
}

onMounted(loadWorkspace);
</script>

<style scoped>
.requirement-layout {
  display: grid;
  grid-template-columns: minmax(0, 1.55fr) minmax(330px, 0.72fr);
  gap: 16px;
}

.requirement-chat {
  min-width: 0;
}

.chat-list {
  display: grid;
  gap: 12px;
  min-height: 360px;
  max-height: 500px;
  margin: 16px 0;
  padding-right: 6px;
  overflow: auto;
}

.completeness-panel {
  display: flex;
  align-items: center;
  gap: 20px;
}

.completeness-ring {
  display: grid;
  width: 92px;
  height: 92px;
  flex: 0 0 92px;
  place-items: center;
  border-radius: 50%;
  background: conic-gradient(#3478f6 var(--percent), #edf1f7 0);
}

.completeness-ring::before {
  grid-area: 1 / 1;
  width: 70px;
  height: 70px;
  border-radius: 50%;
  background: #fff;
  content: '';
}

.completeness-ring strong {
  z-index: 1;
  grid-area: 1 / 1;
  font-size: 20px;
}

.field-row {
  display: grid;
  grid-template-columns: 92px minmax(0, 1fr) 24px;
  align-items: center;
  gap: 10px;
  min-height: 40px;
  border-bottom: 1px solid var(--ui-border);
}

.field-row strong {
  overflow: hidden;
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.field-state {
  display: grid;
  width: 20px;
  height: 20px;
  place-items: center;
  border-radius: 50%;
  font-weight: 800;
}

.field-state.is-complete {
  background: #e7f8ee;
  color: #1fa45d;
}

.field-state.is-missing {
  background: #fff3e5;
  color: #e68416;
}

.question-button {
  display: flex;
  width: 100%;
  min-height: 42px;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 8px 10px;
  border: 0;
  border-bottom: 1px solid var(--ui-border);
  background: transparent;
  color: #4c5872;
  cursor: pointer;
  text-align: left;
}

.question-button:hover {
  color: var(--ui-primary);
}

.question-button b {
  color: #3478f6;
  font-size: 20px;
}

.requirement-form {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 18px;
}

.requirement-form__wide {
  grid-column: 1 / -1;
}

@media (max-width: 1080px) {
  .requirement-layout {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 760px) {
  .requirement-form {
    grid-template-columns: 1fr;
  }

  .requirement-form__wide {
    grid-column: auto;
  }
}
</style>
