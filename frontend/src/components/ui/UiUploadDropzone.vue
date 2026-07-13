<template>
  <button
    class="ui-upload-dropzone"
    type="button"
    :disabled="disabled"
    @click="openPicker"
    @dragover.prevent
    @drop.prevent="handleDrop"
  >
    <span class="ui-upload-dropzone__icon" aria-hidden="true">
      <svg viewBox="0 0 48 48" fill="none">
        <path d="M24 34V12" stroke="currentColor" stroke-width="3.2" stroke-linecap="round" />
        <path d="M15 21L24 12L33 21" stroke="currentColor" stroke-width="3.2" stroke-linecap="round" stroke-linejoin="round" />
        <path d="M12 36H36" stroke="currentColor" stroke-width="3.2" stroke-linecap="round" />
      </svg>
    </span>
    <strong>{{ title }}</strong>
    <span>{{ description }}</span>
    <input ref="fileInput" class="ui-upload-dropzone__input" type="file" :accept="accept" @change="handleChange" />
  </button>
</template>

<script setup lang="ts">
import { ref } from 'vue';

const emit = defineEmits<{
  select: [file: File];
}>();

withDefaults(
  defineProps<{
    title?: string;
    description?: string;
    accept?: string;
    disabled?: boolean;
  }>(),
  {
    title: '拖拽文件到此处，或点击上传',
    description: '支持 PDF、PPT、DOCX、XLSX、TXT、MD、PNG、JPG、MP4 等格式',
    accept: '',
    disabled: false,
  },
);

const fileInput = ref<HTMLInputElement>();

function openPicker() {
  fileInput.value?.click();
}

function handleChange(event: Event) {
  const file = (event.target as HTMLInputElement).files?.[0];
  if (file) emit('select', file);
  if (fileInput.value) fileInput.value.value = '';
}

function handleDrop(event: DragEvent) {
  const file = event.dataTransfer?.files?.[0];
  if (file) emit('select', file);
}
</script>

<style scoped>
.ui-upload-dropzone {
  display: grid;
  justify-items: center;
  align-content: center;
  gap: 12px;
  width: 100%;
  min-height: 220px;
  padding: 24px;
  border: 1px dashed #c9bcff;
  border-radius: 18px;
  background: #fbfaff;
  color: var(--ui-text);
  cursor: pointer;
  text-align: center;
}

.ui-upload-dropzone:hover {
  border-color: var(--ui-primary);
  background: #f7f4ff;
}

.ui-upload-dropzone:disabled {
  cursor: not-allowed;
  opacity: 0.65;
}

.ui-upload-dropzone__input {
  display: none;
}

.ui-upload-dropzone__icon {
  display: grid;
  width: 64px;
  height: 64px;
  place-items: center;
  border-radius: 18px;
  background: #f1edff;
  color: var(--ui-primary);
}

.ui-upload-dropzone__icon svg {
  width: 38px;
  height: 38px;
}

.ui-upload-dropzone strong {
  font-size: 18px;
}

.ui-upload-dropzone span:last-child {
  color: var(--ui-muted);
  line-height: 1.55;
}
</style>
