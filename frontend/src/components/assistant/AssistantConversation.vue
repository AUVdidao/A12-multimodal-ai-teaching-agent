<template>
  <section class="assistant-conversation">
    <header class="assistant-conversation__header">
      <div>
        <span :class="['assistant-conversation__dot', statusTone]" />
        <strong>AI 教学副驾驶</strong>
        <small>{{ loading ? '正在读取当前项目上下文' : empty ? '等待项目上下文' : '已读取当前项目上下文' }}</small>
      </div>
      <div class="assistant-conversation__tools">
        <el-button plain :icon="CirclePlus" :disabled="empty || loading" @click="$emit('new-dialogue')">新建对话</el-button>
        <el-button plain :icon="Clock" :disabled="empty || loading" @click="$emit('history')">历史对话</el-button>
      </div>
    </header>

    <div ref="scrollEl" class="assistant-conversation__body">
      <div v-if="loading" class="assistant-loading-state" aria-live="polite">
        <article v-for="item in loadingSteps" :key="item.id" class="assistant-loading-step">
          <span :class="['assistant-loading-step__status', item.done ? 'is-done' : 'is-loading']" />
          <span class="assistant-loading-step__icon"><A12AssetIcon :name="item.icon" :size="26" /></span>
          <div>
            <strong>{{ item.label }}</strong>
            <span><i :style="{ width: item.width }" /></span>
          </div>
        </article>
        <div class="assistant-skeleton-lines">
          <i /><i /><i class="short" />
        </div>
      </div>

      <div v-else-if="empty" class="assistant-empty-state">
        <div class="assistant-empty-state__icon"><A12AssetIcon name="sparkle" :size="50" /></div>
        <section>
          <p>还没有可以分析的教学项目。<br />创建项目并填写基本信息后，<br />你就可以在这里与 AI 一起规划教学。</p>
          <div>
            <el-button type="primary" :icon="CirclePlus" @click="$emit('create-project')">创建教学项目</el-button>
            <el-button plain :icon="Document" @click="$emit('view-projects')">查看项目列表</el-button>
          </div>
        </section>
      </div>

      <template v-else>
        <article
          v-for="message in messages"
          :key="message.id"
          :class="['assistant-message', `is-${message.role}`, `is-${message.status}`]"
        >
          <div class="assistant-message__avatar">
            <A12AssetIcon v-if="message.role !== 'teacher'" name="sparkle" :size="22" />
            <span v-else>王</span>
          </div>
          <div class="assistant-message__content">
            <section v-if="message.role === 'assistant'" class="assistant-ai-card">
              <p v-if="message.content" class="assistant-ai-card__intro">{{ message.content }}</p>
              <div v-if="message.sections?.length" class="assistant-ai-card__sections">
                <section v-for="section in message.sections" :key="section.id" class="assistant-ai-section">
                  <h3 :class="section.tone">{{ section.title }}</h3>
                  <p v-if="section.content">{{ section.content }}</p>
                  <div v-if="section.items?.length" class="assistant-ai-section__items">
                    <article v-for="item in section.items" :key="item.id" class="assistant-ai-item">
                      <span :class="['assistant-ai-item__mark', item.status || 'pending']">
                        <el-icon v-if="item.status === 'done'"><Check /></el-icon>
                        <el-icon v-else-if="item.status === 'failed'"><Close /></el-icon>
                        <el-icon v-else><ArrowRight /></el-icon>
                      </span>
                      <div>
                        <strong>{{ item.title }}</strong>
                        <small v-if="item.description">{{ item.description }}</small>
                      </div>
                      <el-button
                        v-if="item.action"
                        :type="item.action.tone === 'primary' ? 'primary' : undefined"
                        plain
                        :disabled="item.action.disabled"
                        @click="$emit('action', item.action)"
                      >
                        {{ item.action.label }}
                      </el-button>
                    </article>
                  </div>
                </section>
              </div>
              <div v-if="message.versionNotice" class="assistant-version-notice">
                <el-icon><InfoFilled /></el-icon>
                {{ message.versionNotice }}
              </div>
              <div v-if="message.evidence?.length" class="assistant-evidence-tags">
                <span v-for="item in message.evidence" :key="item.id" :class="item.tone || 'purple'">
                  {{ item.label }} <strong v-if="item.value">{{ item.value }}</strong>
                </span>
              </div>
              <div v-if="message.actions?.length" class="assistant-message__actions">
                <el-tooltip
                  v-for="action in message.actions"
                  :key="action.id"
                  :disabled="!action.disabledReason"
                  :content="action.disabledReason || ''"
                  placement="top"
                >
                  <el-button
                    :type="action.tone === 'primary' ? 'primary' : action.tone === 'success' ? 'success' : undefined"
                    :plain="action.tone !== 'primary'"
                    :disabled="action.disabled"
                    @click="$emit('action', action)"
                  >
                    {{ action.label }}
                  </el-button>
                </el-tooltip>
              </div>
              <el-alert
                v-if="message.status === 'error'"
                class="assistant-message__error"
                type="error"
                title="AI 回复失败，请重新发送。"
                :closable="false"
                show-icon
              />
            </section>
            <p v-else class="assistant-teacher-bubble">{{ message.content }}</p>
          </div>
        </article>
      </template>
    </div>

    <footer class="assistant-composer">
      <div class="assistant-composer__row">
        <el-button class="assistant-composer__attach" :disabled="empty || loading || sending" aria-label="添加附件" :icon="Paperclip" />
        <textarea
          ref="inputEl"
          :value="modelValue"
          :disabled="empty || loading || sending"
          :maxlength="maxLength"
          aria-label="告诉 AI 你想完成什么"
          :placeholder="empty ? '创建教学项目后即可开始对话' : loading ? '项目上下文读取完成后即可继续对话' : '告诉 AI 你想完成什么，例如：帮我检查教学需求是否完整'"
          rows="1"
          @input="$emit('update:modelValue', ($event.target as HTMLTextAreaElement).value)"
          @keydown="handleKeydown"
        />
        <el-button
          class="assistant-composer__send"
          type="primary"
          :icon="Position"
          :loading="sending"
          :disabled="empty || loading || sending || !modelValue.trim()"
          aria-label="发送消息"
          @click="$emit('send')"
        />
      </div>
      <div class="assistant-composer__meta">
        <span>{{ empty ? 'AI 需要先读取项目数据' : loading ? 'AI 正在结合当前项目数据准备回答' : 'AI 将结合当前项目数据回答' }}</span>
        <div v-if="!empty && !loading" class="assistant-quick-prompts">
          <button v-for="prompt in quickPrompts" :key="prompt.id" type="button" :disabled="sending" @click="$emit('quick-prompt', prompt.id)">
            {{ prompt.label }}
          </button>
        </div>
      </div>
    </footer>
  </section>
</template>

<script setup lang="ts">
import type { AssistantMessage, AssistantWorkspaceAction } from '@/types/assistant';
import A12AssetIcon, { type A12AssetIconName } from '@/components/ui/A12AssetIcon.vue';
import { ArrowRight, Check, CirclePlus, Clock, Close, Document, InfoFilled, Paperclip, Position } from '@element-plus/icons-vue';
import { computed, nextTick, ref, watch } from 'vue';

const props = withDefaults(defineProps<{
  messages: AssistantMessage[];
  modelValue: string;
  quickPrompts: Array<{ id: string; label: string }>;
  loading?: boolean;
  empty?: boolean;
  sending?: boolean;
  maxLength?: number;
}>(), {
  maxLength: 1000,
});

const emit = defineEmits<{
  'update:modelValue': [value: string];
  send: [];
  'quick-prompt': [promptId: string];
  action: [action: AssistantWorkspaceAction];
  'new-dialogue': [];
  history: [];
  'create-project': [];
  'view-projects': [];
}>();

const scrollEl = ref<HTMLElement>();
const inputEl = ref<HTMLTextAreaElement>();
const statusTone = computed(() => props.loading ? 'is-loading' : props.empty ? 'is-muted' : 'is-ready');
const loadingSteps: Array<{ id: string; label: string; icon: A12AssetIconName; done?: boolean; width: string }> = [
  { id: 'context', label: '正在读取项目上下文', icon: 'book', done: true, width: '62%' },
  { id: 'requirement', label: '正在分析教学需求', icon: 'search', width: '47%' },
  { id: 'materials', label: '正在读取参考资料', icon: 'document', width: '38%' },
  { id: 'recent', label: '正在整理最近操作', icon: 'layers', width: '52%' },
];

watch(
  () => [props.messages.length, props.loading],
  async () => {
    await nextTick();
    if (scrollEl.value) scrollEl.value.scrollTop = scrollEl.value.scrollHeight;
  },
);

function handleKeydown(event: KeyboardEvent) {
  if (event.key !== 'Enter' || event.shiftKey) return;
  event.preventDefault();
  if (!props.modelValue.trim() || props.empty || props.loading || props.sending) return;
  emit('send');
}
</script>

<style scoped>
.assistant-conversation {
  display: flex;
  min-width: 0;
  min-height: 0;
  flex-direction: column;
  border: 1px solid var(--ui-border);
  border-radius: 12px;
  background: #fff;
  box-shadow: var(--shadow-panel);
  overflow: hidden;
}

.assistant-conversation__header {
  display: flex;
  flex: 0 0 auto;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  min-height: 60px;
  padding: 0 18px;
  border-bottom: 1px solid var(--ui-border);
}

.assistant-conversation__header > div:first-child {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 10px;
}

.assistant-conversation__header strong {
  color: #101827;
  font-size: 20px;
}

.assistant-conversation__header small {
  color: var(--ui-muted);
  font-size: 13px;
}

.assistant-conversation__dot {
  width: 12px;
  height: 12px;
  border-radius: 50%;
}

.assistant-conversation__dot.is-ready {
  background: var(--ui-success);
}

.assistant-conversation__dot.is-muted {
  background: #7b8798;
}

.assistant-conversation__dot.is-loading {
  background: var(--ui-primary);
  box-shadow: 0 0 0 4px #efeaff;
}

.assistant-conversation__tools {
  display: flex;
  flex: 0 0 auto;
  gap: 10px;
}

.assistant-conversation__body {
  display: flex;
  min-height: 0;
  flex: 1 1 auto;
  flex-direction: column;
  gap: 18px;
  padding: 18px;
  overflow-y: auto;
  scrollbar-width: thin;
  scrollbar-color: #d7deeb transparent;
}

.assistant-message {
  display: grid;
  grid-template-columns: 34px minmax(0, 1fr);
  gap: 12px;
  max-width: min(720px, 94%);
}

.assistant-message.is-teacher {
  grid-template-columns: minmax(0, 1fr) 34px;
  align-self: flex-end;
}

.assistant-message__avatar {
  display: grid;
  width: 34px;
  height: 34px;
  place-items: center;
  border-radius: 50%;
  background: #eee9ff;
  color: var(--ui-primary);
  font-size: 14px;
  font-weight: 800;
}

.assistant-message.is-teacher .assistant-message__avatar {
  grid-column: 2;
  background: #dbe8ff;
  color: #174ea6;
}

.assistant-message.is-teacher .assistant-message__content {
  grid-column: 1;
  grid-row: 1;
}

.assistant-ai-card,
.assistant-teacher-bubble {
  border: 1px solid var(--ui-border);
  border-radius: 12px;
  background: #fff;
  box-shadow: 0 4px 14px rgba(23, 43, 77, 0.04);
}

.assistant-ai-card {
  padding: 15px 16px;
}

.assistant-teacher-bubble {
  max-width: 100%;
  margin: 0;
  padding: 13px 16px;
  background: #eeeaff;
  color: #202944;
  font-size: 14px;
  line-height: 1.6;
  overflow-wrap: anywhere;
}

.assistant-ai-card__intro {
  margin: 0;
  color: #202944;
  font-size: 14px;
  line-height: 1.75;
  white-space: pre-line;
}

.assistant-ai-card__sections {
  display: grid;
  gap: 12px;
  margin-top: 2px;
}

.assistant-ai-section {
  padding-top: 11px;
  border-top: 1px solid var(--ui-border);
}

.assistant-ai-section:first-child {
  padding-top: 0;
  border-top: 0;
}

.assistant-ai-section h3 {
  margin: 0 0 7px;
  color: var(--ui-primary);
  font-size: 15px;
  line-height: 1.35;
}

.assistant-ai-section h3.green {
  color: var(--ui-success);
}

.assistant-ai-section h3.orange {
  color: var(--ui-warning);
}

.assistant-ai-section p {
  margin: 0;
  color: #344054;
  font-size: 13px;
  line-height: 1.75;
  white-space: pre-line;
}

.assistant-ai-section__items {
  display: grid;
  gap: 9px;
}

.assistant-ai-item {
  display: grid;
  grid-template-columns: 32px minmax(0, 1fr) auto;
  align-items: center;
  gap: 12px;
  min-height: 58px;
  padding: 10px 12px;
  border: 1px solid var(--ui-border);
  border-radius: 10px;
  background: #fff;
}

.assistant-ai-item__mark {
  display: grid;
  width: 28px;
  height: 28px;
  place-items: center;
  border-radius: 9px;
  background: var(--ui-primary-soft);
  color: var(--ui-primary);
}

.assistant-ai-item__mark.done {
  background: #e9f8f0;
  color: var(--ui-success);
}

.assistant-ai-item__mark.warning,
.assistant-ai-item__mark.pending {
  background: #fff3e4;
  color: var(--ui-warning);
}

.assistant-ai-item strong {
  display: block;
  color: #1c2435;
  font-size: 13px;
}

.assistant-ai-item small {
  display: block;
  margin-top: 4px;
  color: var(--ui-muted);
  font-size: 12px;
  line-height: 1.45;
}

.assistant-evidence-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin-top: 14px;
}

.assistant-evidence-tags span {
  display: inline-flex;
  align-items: center;
  min-height: 34px;
  gap: 8px;
  padding: 0 12px;
  border: 1px solid var(--ui-border);
  border-radius: 9px;
  background: #fff;
  color: var(--ui-muted);
  font-size: 12px;
}

.assistant-evidence-tags strong {
  color: var(--ui-primary);
  font-size: 14px;
}

.assistant-evidence-tags .green strong {
  color: var(--ui-success);
}

.assistant-evidence-tags .orange strong {
  color: var(--ui-warning);
}

.assistant-version-notice {
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: 43px;
  margin-top: 12px;
  padding: 0 12px;
  border: 1px solid #bdb4ff;
  border-radius: 8px;
  background: #f7f5ff;
  color: #31405d;
  font-size: 13px;
}

.assistant-message__actions {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin-top: 14px;
}

.assistant-message__error {
  margin-top: 12px;
}

.assistant-composer {
  flex: 0 0 auto;
  padding: 11px 14px 12px;
  border-top: 1px solid var(--ui-border);
  background: #fbfcff;
}

.assistant-composer__row {
  display: grid;
  grid-template-columns: 42px minmax(0, 1fr) 42px;
  gap: 10px;
  align-items: end;
}

.assistant-composer textarea {
  width: 100%;
  height: 42px;
  max-height: 96px;
  padding: 11px 12px;
  border: 1px solid var(--ui-border-strong);
  border-radius: 9px;
  outline: 0;
  resize: none;
  color: var(--ui-text);
  line-height: 1.45;
}

.assistant-composer textarea:focus {
  border-color: var(--ui-primary);
  box-shadow: 0 0 0 3px var(--ui-primary-soft);
}

.assistant-composer textarea:disabled {
  background: #f4f6fa;
  color: var(--ui-faint);
}

.assistant-composer__attach,
.assistant-composer__send {
  width: 42px;
  height: 42px;
  padding: 0;
}

.assistant-composer__meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-top: 8px;
  color: var(--ui-muted);
  font-size: 12px;
}

.assistant-quick-prompts {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 8px;
}

.assistant-quick-prompts button {
  min-height: 30px;
  padding: 0 11px;
  border: 1px solid #d6cffd;
  border-radius: 8px;
  background: #fff;
  color: var(--ui-primary);
  cursor: pointer;
  font-size: 12px;
  font-weight: 700;
}

.assistant-quick-prompts button:disabled {
  cursor: not-allowed;
  opacity: 0.58;
}

.assistant-empty-state {
  display: grid;
  flex: 1;
  place-items: center;
  padding: 64px 20px;
  text-align: center;
}

.assistant-empty-state__icon {
  display: grid;
  width: 96px;
  height: 96px;
  margin: 0 auto 16px;
  place-items: center;
  border-radius: 50%;
  background: #eeeaff;
}

.assistant-empty-state section {
  width: min(100%, 430px);
  padding: 28px;
  border: 1px solid var(--ui-border);
  border-radius: 12px;
  background: #fff;
}

.assistant-empty-state p {
  margin: 0 0 20px;
  color: #26344d;
  font-size: 16px;
  line-height: 1.85;
}

.assistant-empty-state div:last-child {
  display: flex;
  justify-content: center;
  gap: 12px;
}

.assistant-loading-state {
  display: grid;
  width: min(100%, 680px);
  margin: 20px auto 0;
  padding: 14px;
  border: 1px solid var(--ui-border);
  border-radius: 12px;
  background: #fff;
}

.assistant-loading-step {
  display: grid;
  grid-template-columns: 20px 54px minmax(0, 1fr);
  align-items: center;
  gap: 12px;
  min-height: 76px;
  padding: 0 16px;
  border-bottom: 1px solid var(--ui-border);
}

.assistant-loading-step:last-child {
  border-bottom: 0;
}

.assistant-loading-step__status {
  width: 16px;
  height: 16px;
  border: 2px solid #90a3c2;
  border-radius: 50%;
}

.assistant-loading-step__status.is-done {
  border-color: var(--ui-success);
  background: radial-gradient(circle at center, var(--ui-success) 0 42%, transparent 46%);
}

.assistant-loading-step__status.is-loading {
  border-color: #c8d3e5;
  border-top-color: var(--ui-info);
  animation: assistant-spin 1s linear infinite;
}

.assistant-loading-step__icon {
  display: grid;
  width: 50px;
  height: 50px;
  place-items: center;
  border-radius: 50%;
  background: #f4f7ff;
}

.assistant-loading-step strong {
  display: block;
  margin-bottom: 10px;
  color: var(--ui-text);
  font-size: 14px;
}

.assistant-loading-step div > span,
.assistant-skeleton-lines i {
  display: block;
  height: 9px;
  border-radius: 999px;
  background: #edf1f7;
  overflow: hidden;
}

.assistant-loading-step div > span i {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: linear-gradient(90deg, #7c6cff, #5b45f6);
}

.assistant-skeleton-lines {
  display: grid;
  gap: 16px;
  padding: 28px 0 0;
}

.assistant-skeleton-lines .short {
  width: 64%;
}

@keyframes assistant-spin {
  to {
    transform: rotate(360deg);
  }
}

@media (max-width: 760px) {
  .assistant-message {
    max-width: 100%;
  }

  .assistant-ai-item {
    grid-template-columns: 32px minmax(0, 1fr);
  }

  .assistant-ai-item .el-button {
    grid-column: 2;
    width: 100%;
  }

  .assistant-composer__meta {
    align-items: stretch;
    flex-direction: column;
  }

  .assistant-quick-prompts {
    justify-content: flex-start;
  }
}
</style>
