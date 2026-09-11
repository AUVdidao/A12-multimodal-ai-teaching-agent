<template>
  <section v-if="mission.surfaceGate.submission" class="lf-submission-card" data-test="submission-card">
    <div class="lf-card-header">
      <div>
        <div class="lf-card-kicker">SUBMISSION</div>
        <h2>提交最终课件</h2>
      </div>
      <span v-if="mission.submission" class="lf-submission-status" :class="`is-${mission.submission.status.toLowerCase()}`">
        {{ mission.submission.status === 'REVIEWED' ? '已收到反馈' : '等待审核' }}
      </span>
    </div>

    <div v-if="mission.submission">
      <div class="lf-submission-summary">
        <span class="lf-file-icon lf-file-icon--ppt">P</span>
        <div><strong>{{ mission.submission.fileName }}</strong><small>提交给负责人 · {{ mission.submission.submittedAt }}</small></div>
      </div>
      <p class="lf-submission-note">文件已由服务器保存；下载与审核状态以 API 返回为准。</p>
    </div>

    <div class="lf-submission-field">
      <span class="lf-submission-label">{{ mission.submission ? '再次提交 PPTX' : '最终 PPTX' }}</span>
      <input ref="fileInput" data-test="submission-file-input" type="file" accept=".pptx,application/vnd.openxmlformats-officedocument.presentationml.presentation" hidden @change="onFileChange" />
      <button class="lf-upload-file-button" data-test="submission-file" @click="fileInput?.click()">
        <span>＋</span>{{ selectedFileName || '选择已修改的 PPTX' }}
      </button>
      <small v-if="selectedFileName" class="lf-local-only" data-test="submission-local-only">仅本地选择 · 点击提交后上传</small>
    </div>
    <p v-if="errorMessage" class="lf-submission-error" data-test="submission-error">{{ errorMessage }}</p>
    <p class="lf-submission-note">提交后将进入 Mission 创建负责人的审核；Workspace 不会因提交而冻结。</p>
    <div class="lf-submission-footer"><span>服务端仅接受 .pptx</span><button class="lf-primary-button" data-test="submit-final-deck" :disabled="!selectedFileName" @click="submit">提交审核</button></div>
  </section>
</template>

<script setup lang="ts">
import { ref } from 'vue';
import type { LessonForgeMission } from '@/types/lessonForge';

defineProps<{ mission: LessonForgeMission }>();
const emit = defineEmits<{ 'submit-file': [file: File] }>();
const fileInput = ref<HTMLInputElement>();
const selectedFileName = ref('');
const selectedFile = ref<File | null>(null);
const errorMessage = ref('');

function onFileChange(event: Event) {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  const fileName = file?.name?.trim() || '';
  selectedFileName.value = '';
  selectedFile.value = null;
  errorMessage.value = '';
  if (fileName && !fileName.toLowerCase().endsWith('.pptx')) {
    errorMessage.value = '请选择 .pptx 格式的最终课件。';
    input.value = '';
    return;
  }
  selectedFileName.value = fileName;
  selectedFile.value = file || null;
  input.value = '';
}

function submit() {
  if (!selectedFileName.value) {
    errorMessage.value = '请选择最终 PPTX 后再提交。';
    return;
  }
  if (selectedFile.value) emit('submit-file', selectedFile.value);
}
</script>
