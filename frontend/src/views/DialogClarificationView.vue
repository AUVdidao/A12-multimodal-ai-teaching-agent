<template>
  <section class="page dialog-page">
    <PageHeader eyebrow="M1 · 对话记录" title="智能澄清记录" description="查看教师与 AI 在需求澄清过程中的历史消息，所有内容按项目归档。" :project-label="currentProjectId ? `项目 #${currentProjectId}` : undefined">
      <template #actions><el-button :icon="Refresh" :loading="loading" @click="loadMessages">刷新记录</el-button></template>
    </PageHeader>

    <M1ProgressSteps v-if="currentProjectId" :current-step="3" :unlocked-step="3" :completed-through="2" :project-id="currentProjectId" />

    <section v-if="!currentProjectId" class="surface-panel dialog-chooser">
      <div><span>选择项目</span><h2>读取已有澄清记录</h2><p>通常应从教学需求页进入；也可输入项目编号查看已有消息。</p></div>
      <el-input-number v-model="projectIdInput" :min="1" :precision="0" controls-position="right" aria-label="项目编号" placeholder="项目编号" />
      <el-button type="primary" :icon="Search" @click="loadMessages">读取记录</el-button>
    </section>

    <div v-else class="dialog-workspace">
      <section class="surface-panel conversation-panel" aria-labelledby="conversation-title">
        <header class="conversation-panel__header">
          <div><span>需求澄清会话</span><h2 id="conversation-title">教师与 AI 对话</h2></div>
          <small>{{ messages.length }} 条消息 · 自动按轮次保存</small>
        </header>

        <div class="conversation-scroll" v-loading="loading" tabindex="0" aria-label="澄清对话历史">
          <StatePanel v-if="!loading && messages.length === 0" type="empty" title="暂无澄清记录" description="请返回教学需求页保存需求并启动 AI 澄清。" />
          <DialogueBubble
            v-for="message in messages"
            :key="message.id"
            :sender="message.sender"
            :content="message.content"
            :round-no="message.roundNo"
            :created-at="message.createdAt"
          />
        </div>
      </section>

      <aside class="surface-panel composer-panel">
        <div class="composer-panel__identity">
          <span><el-icon><ChatDotRound /></el-icon></span>
          <div><strong>补充一条澄清消息</strong><small>下一条将作为第 {{ form.roundNo }} 轮保存</small></div>
        </div>

        <el-form label-position="top" @submit.prevent>
          <el-form-item label="消息角色">
            <el-radio-group v-model="form.sender" @change="form.roundNo = suggestRoundNo(form.sender)">
              <el-radio-button value="TEACHER">教师</el-radio-button>
              <el-radio-button value="AI">AI</el-radio-button>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="消息内容">
            <el-input v-model="form.content" type="textarea" :autosize="{ minRows: 5, maxRows: 9 }" maxlength="1000" show-word-limit placeholder="输入本轮需要补充的澄清内容" />
          </el-form-item>
        </el-form>

        <el-alert v-if="errorMessage" class="inline-alert" :title="errorMessage" type="warning" show-icon :closable="false" />

        <PrimaryActionBar>
          <template #info>会话标识和轮次由系统按当前项目维护。</template>
          <el-button type="primary" :icon="ChatDotRound" :loading="saving" :disabled="saving" @click="submitMessage">保存消息</el-button>
        </PrimaryActionBar>

        <div class="composer-panel__back">
          <el-button text type="primary" @click="router.push(requirementsRoute)">返回需求与 AI 澄清</el-button>
          <el-button text @click="router.push('/projects')">项目列表</el-button>
        </div>
      </aside>
    </div>
  </section>
</template>

<script setup lang="ts">
import { listProjectDialogues, saveDialogueMessage, type DialogueMessage, type DialogueSender } from '@/api/dialogues';
import DialogueBubble from '@/components/DialogueBubble.vue';
import M1ProgressSteps from '@/components/M1ProgressSteps.vue';
import PageHeader from '@/components/PageHeader.vue';
import PrimaryActionBar from '@/components/PrimaryActionBar.vue';
import StatePanel from '@/components/StatePanel.vue';
import { ChatDotRound, Refresh, Search } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import { computed, onMounted, reactive, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';

const route = useRoute();
const router = useRouter();
const projectIdInput = ref<number>();
const sessionId = ref('');
const messages = ref<DialogueMessage[]>([]);
const loading = ref(false);
const saving = ref(false);
const errorMessage = ref('');
const form = reactive({ sender: 'TEACHER' as DialogueSender, content: '', roundNo: 1 });
const currentProjectId = computed(() => projectIdInput.value && projectIdInput.value > 0 ? String(projectIdInput.value) : '');
const requirementsRoute = computed(() => currentProjectId.value ? { name: 'project-requirements', params: { projectId: currentProjectId.value } } : { path: '/requirements' });

watch(() => route.query.projectId, applyProjectFromRoute, { immediate: true });
watch(projectIdInput, (projectId) => {
  if (projectId) sessionId.value = defaultSessionId(projectId);
});
onMounted(() => { if (currentProjectId.value) loadMessages(); });

async function loadMessages() {
  if (!currentProjectId.value) {
    errorMessage.value = '请先选择需要查看的教学项目。';
    return;
  }
  loading.value = true;
  errorMessage.value = '';
  try {
    messages.value = sortMessages(await listProjectDialogues(currentProjectId.value));
    sessionId.value = defaultSessionId(Number(currentProjectId.value));
    form.roundNo = suggestRoundNo(form.sender);
  } catch (error) {
    messages.value = [];
    errorMessage.value = resolveErrorMessage(error, '对话记录读取失败，请稍后重试。');
  } finally {
    loading.value = false;
  }
}

async function submitMessage() {
  const content = form.content.trim();
  if (!currentProjectId.value) {
    errorMessage.value = '请先选择教学项目。';
    return;
  }
  if (!content) {
    errorMessage.value = '请填写需要保存的澄清内容。';
    return;
  }
  saving.value = true;
  errorMessage.value = '';
  try {
    const saved = await saveDialogueMessage(currentProjectId.value, { sessionId: sessionId.value, sender: form.sender, content, roundNo: form.roundNo });
    messages.value = sortMessages([...messages.value, saved]);
    form.content = '';
    form.sender = form.sender === 'TEACHER' ? 'AI' : 'TEACHER';
    form.roundNo = suggestRoundNo(form.sender);
    ElMessage.success('澄清消息已保存');
  } catch (error) {
    errorMessage.value = resolveErrorMessage(error, '消息保存失败，请稍后重试。');
  } finally {
    saving.value = false;
  }
}

function suggestRoundNo(sender: DialogueSender) {
  if (messages.value.length === 0) return 1;
  const lastMessage = messages.value[messages.value.length - 1];
  const maxRoundNo = Math.max(...messages.value.map((message) => message.roundNo || 1));
  if (sender === 'AI' && lastMessage.sender === 'TEACHER') return lastMessage.roundNo;
  if (sender === 'TEACHER' && lastMessage.sender !== 'TEACHER') return maxRoundNo + 1;
  return maxRoundNo;
}

function sortMessages(items: DialogueMessage[]) {
  return [...items].sort((left, right) => new Date(left.createdAt).getTime() - new Date(right.createdAt).getTime() || left.id - right.id);
}

function applyProjectFromRoute() {
  const value = Array.isArray(route.query.projectId) ? route.query.projectId[0] : route.query.projectId;
  const numericProjectId = Number(value);
  if (Number.isFinite(numericProjectId) && numericProjectId > 0) {
    projectIdInput.value = numericProjectId;
    sessionId.value = defaultSessionId(numericProjectId);
  }
}

function defaultSessionId(projectId: number) {
  return `project-${projectId}-clarification`;
}

function resolveErrorMessage(error: unknown, fallback: string) {
  const candidate = error as { response?: { data?: { message?: string } } };
  const message = candidate.response?.data?.message;
  return message && !/(Exception|java\.|Axios)/i.test(message) ? message : fallback;
}
</script>

<style scoped>
.dialog-chooser {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto auto;
  align-items: end;
  gap: 18px;
  padding: 24px;
}

.dialog-chooser span {
  color: var(--color-primary);
  font-size: 10px;
  font-weight: 800;
}

.dialog-chooser h2,
.dialog-chooser p {
  margin: 0;
}

.dialog-chooser h2 {
  margin-top: 5px;
  font-size: 17px;
}

.dialog-chooser p {
  margin-top: 4px;
  color: var(--color-text-muted);
  font-size: 11px;
}

.dialog-workspace {
  display: grid;
  grid-template-columns: minmax(0, 1.35fr) minmax(320px, 0.65fr);
  align-items: start;
  gap: 20px;
}

.conversation-panel,
.composer-panel {
  min-width: 0;
  padding: 22px;
}

.conversation-panel__header {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 18px;
  padding-bottom: 15px;
  border-bottom: 1px solid var(--color-border);
}

.conversation-panel__header span {
  color: var(--color-primary);
  font-size: 10px;
  font-weight: 800;
}

.conversation-panel__header h2 {
  margin: 4px 0 0;
  font-size: 17px;
}

.conversation-panel__header small {
  color: var(--color-text-muted);
  font-size: 9px;
}

.conversation-scroll {
  display: grid;
  min-height: 340px;
  max-height: 560px;
  gap: 13px;
  overflow-y: auto;
  padding: 4px;
}

.composer-panel {
  position: sticky;
  top: 90px;
}

.composer-panel__identity {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 20px;
}

.composer-panel__identity > span {
  display: grid;
  width: 36px;
  height: 36px;
  place-items: center;
  border-radius: var(--radius-md);
  background: var(--color-ai-soft);
  color: var(--color-ai);
}

.composer-panel__identity strong,
.composer-panel__identity small {
  display: block;
}

.composer-panel__identity strong {
  font-size: 13px;
}

.composer-panel__identity small {
  margin-top: 3px;
  color: var(--color-text-muted);
  font-size: 9px;
}

.composer-panel__back {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  margin-top: 16px;
}

@media (max-width: 920px) {
  .dialog-workspace,
  .dialog-chooser {
    grid-template-columns: 1fr;
  }

  .composer-panel {
    position: static;
  }
}
</style>
