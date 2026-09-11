<template>
  <section class="assistant-conversation">
    <header class="assistant-conversation__header">
      <div>
        <span :class="['assistant-conversation__dot', statusTone]" />
        <strong>{{ workspaceTitle }}</strong>
        <small>{{ loading ? loadingStatusText : empty ? emptyStatusText : readyStatusText }}</small>
      </div>
      <div v-if="showToolbarActions" class="assistant-conversation__tools">
        <el-button plain :icon="CirclePlus" :disabled="empty || loading" @click="$emit('new-dialogue')">新建对话</el-button>
        <el-button plain :icon="Clock" :disabled="empty || loading" @click="$emit('history')">需求对话记录</el-button>
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
            <span v-else>{{ teacherInitial }}</span>
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
            <div v-if="message.persistenceStatus && message.persistenceStatus !== 'saved' && message.persistenceStatus !== 'not_required'" :class="['assistant-message__persistence', `is-${message.persistenceStatus}`]">
              <span>{{ persistenceText(message) }}</span>
              <button
                v-if="message.persistenceStatus === 'failed'"
                type="button"
                :disabled="Boolean(message.persistRetryCount && message.persistRetryCount > 0)"
                @click="$emit('action', retrySaveAction(message.id))"
              >
                {{ message.persistRetryCount && message.persistRetryCount > 0 ? '已重试' : '重新保存' }}
              </button>
            </div>
          </div>
        </article>
      </template>
    </div>

    <footer class="assistant-composer">
      <section class="assistant-context-files" aria-label="Context Files">
        <div v-if="files.length" class="assistant-context-files__list">
          <article v-for="file in files.slice(0, 4)" :key="file.id" class="assistant-file-card">
            <A12AssetIcon name="document" :size="17" />
            <div>
              <strong>{{ file.originalFilename }}</strong>
                  <span>{{ file.displayStatus || (file.fileType || '文件') + ' · ' + (file.parseStatus === 'SUCCEEDED' ? '已解析' : file.parseStatus === 'FAILED' ? '解析失败' : '待解析') }}</span>
            </div>
          </article>
          <span v-if="files.length > 4" class="assistant-context-files__more">还有 {{ files.length - 4 }} 份材料</span>
        </div>
        <div v-else class="assistant-context-files__empty">{{ contextFilesEmptyText }}</div>
        <div v-if="uploading" class="assistant-upload-progress" role="status">
          <span>正在上传文件</span><el-progress :percentage="uploadProgress" :show-text="false" />
        </div>
      </section>
      <div class="assistant-composer__panel">
        <div class="assistant-composer__row">
          <textarea
            ref="inputEl"
            :value="modelValue"
            :disabled="empty || loading || sending"
            :maxlength="maxLength"
            :aria-label="composerAriaLabel"
            :placeholder="empty ? emptyComposerPlaceholder : loading ? loadingComposerPlaceholder : composerPlaceholder"
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
          <div class="assistant-composer__meta-left">
            <UiUploadDropzone
              compact
              :disabled="empty || loading || uploading"
              title="添加文件"
              description=""
              @select="$emit('file-select', $event)"
            />
            <button
              v-if="showConnectionControl"
              type="button"
              class="assistant-composer__connection"
              data-test="open-connection"
              @click="$emit('open-connection')"
            >
              <span>连接</span>
              <strong>{{ connectionLabel }}</strong>
            </button>
          </div>
          <div v-if="!empty && !loading" class="assistant-quick-prompts">
            <button v-for="prompt in quickPrompts" :key="prompt.id" type="button" :disabled="sending" @click="$emit('quick-prompt', prompt.id)">
              {{ prompt.label }}
            </button>
          </div>
        </div>
      </div>
    </footer>
  </section>
</template>

<script setup lang="ts">
import type { AssistantContextFile, AssistantMessage, AssistantWorkspaceAction } from '@/types/assistant';
import A12AssetIcon, { type A12AssetIconName } from '@/components/ui/A12AssetIcon.vue';
import UiUploadDropzone from '@/components/ui/UiUploadDropzone.vue';
import { ArrowRight, Check, CirclePlus, Clock, Close, Document, InfoFilled, Position } from '@element-plus/icons-vue';
import { computed, nextTick, ref, watch } from 'vue';

const props = withDefaults(defineProps<{
  messages: AssistantMessage[];
  modelValue: string;
  quickPrompts: Array<{ id: string; label: string }>;
  loading?: boolean;
  empty?: boolean;
  sending?: boolean;
  teacherInitial?: string;
  maxLength?: number;
  files?: AssistantContextFile[];
  uploading?: boolean;
  uploadProgress?: number;
  workspaceTitle?: string;
  loadingStatusText?: string;
  emptyStatusText?: string;
  readyStatusText?: string;
  contextFilesSubtitle?: string;
  contextFilesEmptyText?: string;
  composerAriaLabel?: string;
  emptyComposerPlaceholder?: string;
  loadingComposerPlaceholder?: string;
  composerPlaceholder?: string;
  emptyComposerMeta?: string;
  loadingComposerMeta?: string;
  composerMeta?: string;
  showToolbarActions?: boolean;
  showConnectionControl?: boolean;
  connectionLabel?: string;
}>(), {
  teacherInitial: '师',
  maxLength: 1000,
  files: () => [],
  uploading: false,
  uploadProgress: 0,
  workspaceTitle: 'Conversation Workspace',
  loadingStatusText: '正在读取当前 Task 上下文',
  emptyStatusText: '等待项目上下文',
  readyStatusText: '已读取当前 Task 上下文',
  contextFilesSubtitle: '当前项目材料',
  contextFilesEmptyText: '还没有绑定材料，可从这里添加教材、教案、PPTX 或图片。',
  composerAriaLabel: '告诉 AI 你想完成什么',
  emptyComposerPlaceholder: '创建教学项目后即可开始对话',
  loadingComposerPlaceholder: '项目上下文读取完成后即可继续对话',
  composerPlaceholder: '告诉 AI 你想完成什么，例如：帮我检查教学需求是否完整',
  emptyComposerMeta: 'AI 需要先读取项目数据',
  loadingComposerMeta: 'AI 正在结合当前项目数据准备回答',
  composerMeta: 'AI 将结合当前项目数据回答',
  showToolbarActions: true,
  showConnectionControl: false,
  connectionLabel: '未选择连接',
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
  'file-select': [file: File];
  'open-connection': [];
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

function persistenceText(message: AssistantMessage) {
  if (message.persistenceStatus === 'pending') return '正在保存对话';
  return message.persistenceError || '对话保存失败，刷新后这条消息可能丢失。';
}

function retrySaveAction(messageId: string): AssistantWorkspaceAction {
  return {
    id: `retry-save-${messageId}`,
    label: '重新保存',
    tone: 'secondary',
    actionType: 'RETRY_SAVE',
    messageId,
  };
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

.assistant-context-files {
  display: grid;
  gap: 9px;
  margin-bottom: 10px;
  padding: 10px;
  border: 1px solid var(--ui-border);
  border-radius: 10px;
  background: #fff;
}

.assistant-context-files__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.assistant-context-files__header > div {
  display: flex;
  align-items: baseline;
  gap: 8px;
}

.assistant-context-files__header strong {
  color: var(--ui-text);
  font-size: 13px;
}

.assistant-context-files__header span,
.assistant-context-files__empty,
.assistant-context-files__more {
  color: var(--ui-muted);
  font-size: 11px;
}

.assistant-context-files__list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.assistant-file-card {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 7px;
  max-width: 220px;
  padding: 7px 9px;
  border: 1px solid var(--ui-border);
  border-radius: 8px;
  background: #fbfcff;
}

.assistant-file-card > .a12-asset-icon {
  flex: 0 0 auto;
}

.assistant-file-card div {
  display: grid;
  min-width: 0;
  gap: 2px;
}

.assistant-file-card strong,
.assistant-file-card span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.assistant-file-card strong {
  color: var(--ui-text);
  font-size: 11px;
}

.assistant-file-card span {
  color: var(--ui-muted);
  font-size: 10px;
}

.assistant-upload-progress {
  display: grid;
  grid-template-columns: auto minmax(80px, 1fr);
  align-items: center;
  gap: 8px;
  color: var(--ui-primary);
  font-size: 11px;
}

.assistant-composer__row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 42px;
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

.assistant-composer__send {
  width: 42px;
  height: 42px;
  padding: 0;
}

.assistant-message__persistence {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-top: 7px;
  color: var(--ui-muted);
  font-size: 12px;
}

.assistant-message__persistence.is-failed {
  color: var(--ui-danger);
}

.assistant-message__persistence button {
  min-height: 26px;
  padding: 0 9px;
  border: 1px solid currentColor;
  border-radius: 8px;
  background: #fff;
  color: inherit;
  cursor: pointer;
  font-size: 12px;
  font-weight: 700;
}

.assistant-message__persistence button:disabled {
  cursor: not-allowed;
  opacity: 0.58;
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

.assistant-composer__meta-left {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 12px;
}

.assistant-composer__connection {
  display: inline-flex;
  min-height: 28px;
  align-items: center;
  gap: 6px;
  padding: 0 9px;
  border: 1px solid var(--ui-border);
  border-radius: 7px;
  background: #fff;
  color: var(--ui-muted);
  cursor: pointer;
  font: inherit;
  font-size: 11px;
}

.assistant-composer__connection:hover,
.assistant-composer__connection:focus-visible {
  border-color: var(--ui-primary);
  color: var(--ui-primary);
  outline: 0;
}

.assistant-composer__connection strong {
  color: var(--ui-text);
  font-size: 11px;
}

.assistant-composer__connection em {
  color: var(--ui-primary);
  font-style: normal;
  font-weight: 700;
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

/* Mission Workspace uses a compact desktop shell; the conversation remains the visual center. */
.assistant-conversation {
  border: 0;
  border-radius: 0;
  background: #090808;
  box-shadow: none;
  color: #eee8e3;
}

.assistant-conversation__header {
  display: none;
}

.assistant-conversation__body {
  width: min(100%, 920px);
  align-self: center;
  padding: 28px 30px 18px;
  scrollbar-color: #403936 transparent;
}

.assistant-message {
  max-width: min(780px, 94%);
}

.assistant-message__avatar {
  width: 28px;
  height: 28px;
  border-radius: 4px;
  background: #28211e;
  color: #ff965e;
  font-size: 12px;
}

.assistant-message.is-teacher .assistant-message__avatar {
  background: #26272a;
  color: #ddd6d1;
}

.assistant-ai-card,
.assistant-teacher-bubble {
  border-color: #302b29;
  border-radius: 5px;
  background: #141211;
  box-shadow: none;
}

.assistant-ai-card {
  padding: 13px 15px;
}

.assistant-teacher-bubble {
  padding: 10px 13px;
  background: #201b19;
  color: #e5ddd8;
  font-size: 13px;
  line-height: 1.6;
}

.assistant-ai-card__intro,
.assistant-ai-section p {
  color: #ddd5d0;
  font-size: 13px;
}

.assistant-ai-section {
  border-top-color: #302b29;
}

.assistant-ai-section h3 {
  color: #ff9a5e;
  font-size: 13px;
}

.assistant-ai-item {
  min-height: 48px;
  padding: 8px 10px;
  border-color: #302b29;
  border-radius: 4px;
  background: #111010;
}

.assistant-ai-item strong { color: #e8e0db; font-size: 12px; }
.assistant-ai-item small { color: #8f8681; font-size: 11px; }

.assistant-evidence-tags span {
  min-height: 27px;
  padding: 0 9px;
  border-color: #302b29;
  border-radius: 4px;
  background: #111010;
  color: #918984;
}

.assistant-version-notice {
  min-height: 34px;
  border-color: #4a382b;
  border-radius: 4px;
  background: #1a1512;
  color: #b8aaa0;
  font-size: 11px;
}

.assistant-composer {
  width: min(100%, 860px);
  align-self: center;
  padding: 0 24px 14px;
  border-top: 0;
  background: #090808;
}

.assistant-context-files {
  gap: 5px;
  margin-bottom: 6px;
  padding: 0;
  border: 0;
  border-radius: 0;
  background: transparent;
}

.assistant-context-files__empty {
}

.assistant-file-card {
  max-width: 190px;
  padding: 5px 7px;
  border-color: #302b29;
  border-radius: 4px;
  background: #141211;
}

.assistant-file-card strong { color: #d9d1cc; font-size: 10px; }
.assistant-file-card span { color: #837a75; font-size: 9px; }

.assistant-composer__panel {
  overflow: hidden;
  border: 1px solid #3b3431;
  border-radius: 6px;
  background: #111010;
}

.assistant-composer__row {
  grid-template-columns: minmax(0, 1fr) 36px;
  gap: 7px;
  padding: 5px;
  border: 0;
  border-bottom: 1px solid #2b2625;
  border-radius: 0;
  background: transparent;
}

.assistant-composer textarea {
  height: 36px;
  max-height: 84px;
  padding: 8px 9px;
  border: 0;
  border-radius: 3px;
  background: transparent;
  color: #eee8e3;
  font-size: 13px;
}

.assistant-composer textarea:focus {
  border: 0;
  box-shadow: none;
}

.assistant-composer textarea:disabled {
  background: transparent;
  color: #675f5b;
}

.assistant-composer__send {
  width: 36px;
  height: 36px;
  border-color: #ff6b16;
  background: #ff6b16;
  color: #1d1008;
}

.assistant-composer__meta {
  min-height: 30px;
  margin-top: 0;
  padding: 3px 5px 4px;
  color: #746c67;
  font-size: 10px;
}

.assistant-composer__meta-left {
  gap: 7px;
}

.assistant-composer__meta-left > span {
  display: none;
}

.assistant-composer__connection {
  min-height: 24px;
  padding: 0 6px;
  border-color: #38312e;
  border-radius: 4px;
  background: transparent;
  color: #8f8681;
  font-size: 10px;
}

.assistant-composer__connection strong { color: #d4cbc5; font-size: 10px; }
.assistant-composer__connection em { display: none; }

.assistant-composer__meta-left :deep(.ui-upload-dropzone) {
  min-height: 24px;
  padding: 3px 7px;
  border-color: #38312e;
  border-radius: 4px;
  background: transparent;
  color: #b5aaa4;
  font-size: 10px;
  font-weight: 500;
}

.assistant-composer__meta-left :deep(.ui-upload-dropzone:hover) {
  border-color: #6a5b52;
  background: #171414;
  color: #eee8e3;
}

.assistant-composer__meta-left :deep(.ui-upload-dropzone.is-compact strong) {
  font-size: 10px;
  font-weight: 500;
}

.assistant-composer__meta-left :deep(.ui-upload-dropzone.is-compact .ui-upload-dropzone__icon),
.assistant-composer__meta-left :deep(.ui-upload-dropzone.is-compact .ui-upload-dropzone__icon svg) {
  width: 15px;
  height: 15px;
}

.assistant-quick-prompts button {
  min-height: 25px;
  padding: 0 8px;
  border-color: #38312e;
  border-radius: 4px;
  background: transparent;
  color: #a09892;
  font-size: 10px;
}

.assistant-empty-state__icon { background: #211a17; }
.assistant-empty-state section { border-color: #302b29; border-radius: 5px; background: #141211; }
.assistant-empty-state p { color: #c9c0ba; font-size: 13px; }

.assistant-loading-state { border-color: #302b29; border-radius: 5px; background: #141211; }
.assistant-loading-step { border-bottom-color: #302b29; }
.assistant-loading-step strong { color: #d8d0cb; font-size: 12px; }
.assistant-loading-step div > span,
.assistant-skeleton-lines i { background: #2a2523; }

@media (max-width: 760px) {
  .assistant-conversation__body,
  .assistant-composer { padding-right: 16px; padding-left: 16px; }
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

  .assistant-composer__meta-left {
    align-items: flex-start;
    flex-direction: column;
    gap: 7px;
  }

  .assistant-quick-prompts {
    justify-content: flex-start;
  }
}
</style>
