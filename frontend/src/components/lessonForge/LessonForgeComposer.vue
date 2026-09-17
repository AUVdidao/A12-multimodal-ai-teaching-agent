<template>
  <div
    class="lf-composer"
    data-test="composer"
    :class="{ 'lf-composer--empty': variant === 'empty', 'lf-composer--resizing': resizing }"
    :style="composerStyle"
  >
    <button
      class="lf-composer-resize-handle"
      type="button"
      aria-label="拖动调整输入框高度"
      title="拖动调整输入框高度"
      @pointerdown="startResize"
      @keydown="handleResizeKeydown"
    />
    <div v-if="files.length" class="lf-composer-files" data-test="composer-context-files"><span v-for="file in files" :key="fileKey(file)" class="lf-composer-file" :data-test="`composer-context-file-${file.name}`"><span class="lf-file-icon">▤</span><span class="lf-composer-file__details"><strong>{{ file.name }}</strong><small>{{ fileTypeLabel(file.name) }} · 等待上传</small></span><button aria-label="移除文件" @click="removeFile(file)">×</button></span></div>
    <textarea ref="textarea" :value="modelValue" :placeholder="placeholder" aria-label="消息输入" :disabled="disabled" @input="onTextInput" @keydown.meta.enter.prevent="send" @keydown.ctrl.enter.prevent="send" />
    <div class="lf-composer-toolbar">
      <input v-if="showFiles" ref="fileInput" type="file" multiple accept=".pdf,.doc,.docx,.ppt,.pptx,.png,.jpg,.jpeg" hidden @change="onFileChange" />
      <button v-if="showFiles" class="lf-composer-tool" type="button" aria-label="添加文件" :disabled="disabled" @click="fileInput?.click()"><span class="lf-composer-plus">＋</span><span v-if="variant === 'empty'">Add files</span></button>
      <div v-if="showConnection" class="lf-connection-picker">
        <button class="lf-connection-select" type="button" :class="{ 'is-pending': selectedConnection && !isUsable(selectedConnection) }" aria-label="选择模型连接" :disabled="disabled" @click="pickerOpen = !pickerOpen"><span class="lf-connection-dot" :class="{ 'is-verified': selectedConnection && isUsable(selectedConnection) }" />{{ selectedConnection?.name || 'Select model' }}<span class="lf-select-chevron">⌄</span></button>
        <div v-if="pickerOpen" class="lf-connection-popover"><div class="lf-popover-title">Model connection</div><button v-for="connection in connections" :key="connection.id" class="lf-connection-option" :class="{ 'is-unavailable': !isUsable(connection) }" :disabled="!isUsable(connection)" :data-test="`connection-option-${connection.id}`" @click="selectConnection(connection)"><span class="lf-connection-dot" :class="{ 'is-verified': isUsable(connection) }" /><span><strong>{{ connection.name }}</strong><small>{{ connection.modelId || connection.model }} · {{ connectionStateLabel(connection) }}</small></span><span v-if="selectedConnection?.id === connection.id" class="lf-option-selected" role="img" aria-label="当前选择" title="当前选择" /></button><span v-if="!connections.length" class="lf-popover-title">暂无服务器连接，请先保存自己的 Model Connection。</span><button class="lf-add-connection" @click="$emit('manage-connections'); pickerOpen = false">＋ Manage connections</button></div>
      </div>
      <div class="lf-composer-spacer" /><span class="lf-composer-hint">{{ working ? 'Working…' : 'Ctrl + Enter to send' }}</span><button class="lf-send-button" :disabled="!canSend" aria-label="发送" @click="send">↑</button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue';
import { isSelectableConnection } from '@/utils/conversationWorkspaceConnection';
import { composerFileIdentity, mergeComposerFiles, type ComposerFile } from '@/utils/lessonForgeComposer';
interface Connection { id: number; name: string; model?: string; modelId?: string; enabled?: boolean; verificationStatus?: 'VERIFIED' | 'UNVERIFIED' | 'INVALID'; }
const props = withDefaults(defineProps<{ modelValue: string; files?: ComposerFile[]; connections?: Connection[]; selectedConnection?: Connection | null; variant?: 'default' | 'empty'; working?: boolean; disabled?: boolean; placeholder?: string; showFiles?: boolean; showConnection?: boolean }>(), { files: () => [], connections: () => [], selectedConnection: null, variant: 'default', working: false, disabled: false, placeholder: 'Type your message…', showFiles: true, showConnection: true });
const emit = defineEmits<{ 'update:modelValue': [value: string]; send: [payload: { text: string; files: ComposerFile[] }]; 'files-selected': [files: ComposerFile[]]; 'select-connection': [connection: Connection]; 'manage-connections': [] }>();
const fileInput = ref<HTMLInputElement>(); const pickerOpen = ref(false);
const canSend = computed(() => Boolean(props.modelValue.trim() || props.files.length) && !props.working && !props.disabled);
const COMPOSER_MIN_HEIGHT = 100;
const COMPOSER_MAX_HEIGHT = 360;
const COMPOSER_TOOLBAR_HEIGHT = 43;
const COMPOSER_BORDER_HEIGHT = 2;
const composerHeight = ref(COMPOSER_MIN_HEIGHT);
const resizing = ref(false);
const textareaHeight = computed(() => Math.max(55, composerHeight.value - COMPOSER_TOOLBAR_HEIGHT - COMPOSER_BORDER_HEIGHT));
const composerStyle = computed(() => ({ '--lf-composer-height': `${composerHeight.value}px`, '--lf-composer-textarea-height': `${textareaHeight.value}px` }));
let resizeStartY = 0;
let resizeStartHeight = COMPOSER_MIN_HEIGHT;

function clampComposerHeight(value: number) { return Math.min(COMPOSER_MAX_HEIGHT, Math.max(COMPOSER_MIN_HEIGHT, value)); }
function startResize(event: PointerEvent) {
  if (event.button !== 0 || props.disabled) return;
  event.preventDefault();
  resizing.value = true;
  resizeStartY = event.clientY;
  resizeStartHeight = composerHeight.value;
  document.body.classList.add('lf-is-resizing-composer');
  window.addEventListener('pointermove', handleResize);
  window.addEventListener('pointerup', stopResize);
  window.addEventListener('pointercancel', stopResize);
}
function handleResize(event: PointerEvent) { composerHeight.value = clampComposerHeight(resizeStartHeight + resizeStartY - event.clientY); }
function stopResize() {
  resizing.value = false;
  document.body.classList.remove('lf-is-resizing-composer');
  window.removeEventListener('pointermove', handleResize);
  window.removeEventListener('pointerup', stopResize);
  window.removeEventListener('pointercancel', stopResize);
}
function handleResizeKeydown(event: KeyboardEvent) {
  if (event.key === 'ArrowUp') { event.preventDefault(); composerHeight.value = clampComposerHeight(composerHeight.value + 20); }
  if (event.key === 'ArrowDown') { event.preventDefault(); composerHeight.value = clampComposerHeight(composerHeight.value - 20); }
}
onBeforeUnmount(stopResize);
function send() { if (canSend.value) emit('send', { text: props.modelValue.trim(), files: props.files }); }
function onTextInput(event: Event) { const target = event.target as HTMLTextAreaElement; emit('update:modelValue', target.value); }
function isUsable(connection: Connection) { return isSelectableConnection(connection); }
function connectionStateLabel(connection: Connection) {
  if (connection.enabled === false) return 'Disabled';
  if (connection.verificationStatus === 'INVALID') return 'Invalid';
  return isUsable(connection) ? 'Verified' : 'Unverified · live verification pending';
}
function fileTypeLabel(name: string) {
  const extension = name.split('.').pop()?.toLowerCase();
  return extension ? extension.toUpperCase() : 'FILE';
}
function fileKey(file: ComposerFile) { return composerFileIdentity(file); }
function selectConnection(connection: Connection) { if (!isUsable(connection)) return; emit('select-connection', connection); pickerOpen.value = false; }
function onFileChange(event: Event) { const input = event.target as HTMLInputElement; const incoming = Array.from(input.files || []).map((file) => ({ name: file.name, file })); emit('files-selected', mergeComposerFiles(props.files, incoming)); input.value = ''; }
function removeFile(file: ComposerFile) { emit('files-selected', props.files.filter((item) => composerFileIdentity(item) !== composerFileIdentity(file))); }
</script>
