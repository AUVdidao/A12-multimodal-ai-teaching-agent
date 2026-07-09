<template>
  <section class="page dialog-page">
    <header class="page__header page__header--with-action">
      <div>
        <h2 class="page__title">智能澄清对话</h2>
        <p class="page__description">教师与 AI 的需求澄清记录会按时间顺序保存在当前项目下。</p>
      </div>
      <el-button :icon="Refresh" :loading="loading" @click="loadMessages">
        刷新
      </el-button>
    </header>

    <el-card class="page-card dialog-toolbar" shadow="never">
      <el-form label-position="top" class="dialog-form" @submit.prevent>
        <el-form-item label="项目 ID">
          <el-input-number
            v-model="projectIdInput"
            :min="1"
            :precision="0"
            controls-position="right"
            placeholder="项目 ID"
          />
        </el-form-item>
        <el-form-item label="会话 ID">
          <el-input v-model="sessionId" />
        </el-form-item>
        <el-form-item label="发送方">
          <el-radio-group v-model="form.sender">
            <el-radio-button label="TEACHER">教师</el-radio-button>
            <el-radio-button label="AI">AI</el-radio-button>
            <el-radio-button label="SYSTEM">系统</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="轮次">
          <el-input-number
            v-model="form.roundNo"
            :min="1"
            :precision="0"
            controls-position="right"
          />
        </el-form-item>
      </el-form>
    </el-card>

    <section class="dialog-timeline" v-loading="loading">
      <el-empty v-if="!loading && messages.length === 0" description="暂无对话记录" />

      <article
        v-for="message in messages"
        :key="message.id"
        :class="messageClass(message.sender)"
      >
        <div class="dialog-message__meta">
          <span>{{ formatSender(message.sender) }}</span>
          <span>第 {{ message.roundNo }} 轮</span>
          <time :datetime="message.createdAt">{{ formatDate(message.createdAt) }}</time>
        </div>
        <p>{{ message.content }}</p>
      </article>
    </section>

    <el-card class="page-card dialog-composer" shadow="never">
      <el-input
        v-model="form.content"
        type="textarea"
        :autosize="{ minRows: 3, maxRows: 6 }"
        maxlength="1000"
        show-word-limit
        placeholder="输入本轮澄清内容"
      />
      <div class="dialog-composer__actions">
        <el-button :icon="ChatDotRound" type="primary" :loading="saving" @click="submitMessage">
          保存消息
        </el-button>
        <el-button :icon="Select" @click="useNextSender">切换下一句</el-button>
      </div>
    </el-card>

    <el-alert
      v-if="errorMessage"
      class="inline-alert"
      :title="errorMessage"
      type="warning"
      show-icon
      :closable="false"
    />

    <div class="page__actions">
      <el-button type="primary" @click="router.push(summaryRoute)">下一步：需求摘要确认</el-button>
      <el-button @click="router.push(requirementsRoute)">返回需求输入</el-button>
    </div>
  </section>
</template>

<script setup lang="ts">
import {
  listProjectDialogues,
  saveDialogueMessage,
  type DialogueMessage,
  type DialogueSender,
} from '@/api/dialogues';
import { ChatDotRound, Refresh, Select } from '@element-plus/icons-vue';
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

const form = reactive({
  sender: 'TEACHER' as DialogueSender,
  content: '',
  roundNo: 1,
});

const currentProjectId = computed(() => {
  if (!projectIdInput.value || projectIdInput.value < 1) {
    return '';
  }
  return String(projectIdInput.value);
});

const requirementsRoute = computed(() => {
  if (!currentProjectId.value) {
    return { path: '/requirements' };
  }
  return {
    path: '/requirements',
    query: { projectId: currentProjectId.value },
  };
});

const summaryRoute = computed(() => {
  if (!currentProjectId.value) {
    return { path: '/summary' };
  }
  return {
    path: '/summary',
    query: { projectId: currentProjectId.value },
  };
});

watch(
  () => route.query.projectId,
  () => applyProjectFromRoute(),
  { immediate: true },
);

watch(projectIdInput, (projectId) => {
  if (projectId && !sessionId.value) {
    sessionId.value = defaultSessionId(projectId);
  }
});

onMounted(() => {
  if (currentProjectId.value) {
    loadMessages();
  }
});

async function loadMessages() {
  if (!currentProjectId.value) {
    errorMessage.value = '请选择项目后读取对话历史。';
    return;
  }

  loading.value = true;
  errorMessage.value = '';

  try {
    messages.value = sortMessages(await listProjectDialogues(currentProjectId.value));
    if (!sessionId.value) {
      sessionId.value = defaultSessionId(Number(currentProjectId.value));
    }
    form.roundNo = suggestRoundNo(form.sender);
  } catch (error) {
    messages.value = [];
    errorMessage.value = resolveErrorMessage(error, '对话历史读取失败，请确认后端服务已启动。');
  } finally {
    loading.value = false;
  }
}

async function submitMessage() {
  const content = form.content.trim();
  if (!currentProjectId.value) {
    errorMessage.value = '请选择项目后保存对话消息。';
    return;
  }
  if (!sessionId.value.trim()) {
    errorMessage.value = '请填写会话 ID。';
    return;
  }
  if (!content) {
    errorMessage.value = '请输入本轮澄清内容。';
    return;
  }

  saving.value = true;
  errorMessage.value = '';

  try {
    const saved = await saveDialogueMessage(currentProjectId.value, {
      sessionId: sessionId.value.trim(),
      sender: form.sender,
      content,
      roundNo: form.roundNo,
    });
    messages.value = sortMessages([...messages.value, saved]);
    form.content = '';
    useNextSender();
  } catch (error) {
    errorMessage.value = resolveErrorMessage(error, '对话消息保存失败，请稍后重试。');
  } finally {
    saving.value = false;
  }
}

function useNextSender() {
  form.sender = form.sender === 'TEACHER' ? 'AI' : 'TEACHER';
  form.roundNo = suggestRoundNo(form.sender);
}

function suggestRoundNo(sender: DialogueSender) {
  if (messages.value.length === 0) {
    return 1;
  }

  const lastMessage = messages.value[messages.value.length - 1];
  const maxRoundNo = Math.max(...messages.value.map((message) => message.roundNo || 1));
  if (sender === 'AI' && lastMessage.sender === 'TEACHER') {
    return lastMessage.roundNo;
  }
  if (sender === 'TEACHER' && lastMessage.sender !== 'TEACHER') {
    return maxRoundNo + 1;
  }
  return maxRoundNo;
}

function sortMessages(items: DialogueMessage[]) {
  return [...items].sort((left, right) => {
    const timeDiff = new Date(left.createdAt).getTime() - new Date(right.createdAt).getTime();
    if (timeDiff !== 0) {
      return timeDiff;
    }
    return left.id - right.id;
  });
}

function messageClass(sender: DialogueSender) {
  const normalizedSender = sender === 'ASSISTANT' ? 'AI' : sender;
  return [
    'dialog-message',
    `dialog-message--${normalizedSender.toLowerCase()}`,
  ];
}

function formatSender(sender: DialogueSender) {
  const senderMap: Record<DialogueSender, string> = {
    TEACHER: '教师',
    AI: 'AI',
    ASSISTANT: 'AI',
    SYSTEM: '系统',
  };
  return senderMap[sender] || sender;
}

function formatDate(value: string) {
  if (!value) {
    return '-';
  }
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(value));
}

function applyProjectFromRoute() {
  const projectId = normalizeQueryValue(route.query.projectId);
  if (!projectId) {
    return;
  }

  const numericProjectId = Number(projectId);
  if (Number.isFinite(numericProjectId) && numericProjectId > 0) {
    projectIdInput.value = numericProjectId;
    sessionId.value = defaultSessionId(numericProjectId);
  }
}

function normalizeQueryValue(value: unknown) {
  if (Array.isArray(value)) {
    return value[0];
  }
  if (typeof value === 'string') {
    return value;
  }
  return '';
}

function defaultSessionId(projectId: number) {
  return `project-${projectId}-clarification`;
}

function resolveErrorMessage(error: unknown, fallback: string) {
  const candidate = error as { response?: { data?: { message?: string } } };
  return candidate.response?.data?.message || fallback;
}
</script>

<style scoped>
.dialog-page {
  max-width: 1080px;
}

.dialog-toolbar {
  margin-bottom: 18px;
}

.dialog-form {
  display: grid;
  grid-template-columns: minmax(132px, 0.8fr) minmax(240px, 1.6fr) minmax(220px, 1fr) minmax(112px, 0.7fr);
  gap: 14px;
}

.dialog-form :deep(.el-form-item) {
  margin-bottom: 0;
}

.dialog-timeline {
  display: grid;
  gap: 12px;
  min-height: 280px;
  padding: 4px 0;
}

.dialog-message {
  width: min(76%, 760px);
  padding: 14px 16px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #ffffff;
}

.dialog-message--teacher {
  justify-self: end;
  border-color: #a7f3d0;
  background: #ecfdf5;
}

.dialog-message--ai {
  justify-self: start;
  border-color: #bfdbfe;
  background: #eff6ff;
}

.dialog-message--system {
  justify-self: center;
  border-color: #e5e7eb;
  background: #f9fafb;
}

.dialog-message__meta {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  color: #667085;
  font-size: 12px;
}

.dialog-message__meta span:first-child {
  color: #1f2937;
  font-weight: 700;
}

.dialog-message p {
  margin: 8px 0 0;
  color: #1f2937;
  line-height: 1.7;
  overflow-wrap: anywhere;
  white-space: pre-wrap;
}

.dialog-composer {
  margin-top: 18px;
}

.dialog-composer__actions {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  margin-top: 12px;
}

@media (max-width: 920px) {
  .dialog-form {
    grid-template-columns: 1fr;
  }

  .dialog-message {
    width: 100%;
  }
}
</style>
