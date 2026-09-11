<template>
  <LessonForgeFrame class="lf-app--light-new" :recent-missions="recentMissions" :search-missions="recentMissions" active="new" context-label="New Mission" show-new-mission>
    <section class="lf-new-mission lf-new-mission--workspace go-new-mission" data-test="go-new-mission">
      <header class="lf-new-workspace-header">
        <div>
          <div class="lf-eyebrow">NEW MISSION</div>
          <h1>开始一个新的课件任务</h1>
          <p>写下教学目标，必要时把教材、教案或模板直接加入当前 Mission。</p>
        </div>
      </header>
      <section class="lf-new-workspace__conversation" data-test="go-new-conversation">
        <div v-if="!created" class="lf-new-workspace__empty">
          <div class="lf-new-mission__mark" aria-hidden="true">✦</div>
          <h2>从一个教学目标开始</h2>
          <p>告诉我你想制作什么课件。你可以先写下主题、年级和课堂目标，再补充教材或模板。</p>
          <div class="lf-new-workspace__quick-start" aria-label="快速开始">
            <span class="lf-new-workspace__section-label">快速开始</span>
            <div class="lf-new-workspace__prompts">
              <button v-for="prompt in quickPrompts" :key="prompt.title" type="button" @click="draft = prompt.text">
                <strong>{{ prompt.title }}</strong>
                <small>{{ prompt.description }}</small>
              </button>
            </div>
          </div>
          <div class="lf-new-workspace__dropzone" :class="{ 'is-dragging': draggingFiles }" data-test="context-dropzone" @click="openFilePicker" @dragenter.prevent="draggingFiles = true" @dragover.prevent="draggingFiles = true" @dragleave.prevent="draggingFiles = false" @drop.prevent="handleDroppedFiles">
            <input ref="contextFileInput" type="file" multiple accept=".pdf,.doc,.docx,.ppt,.pptx,.png,.jpg,.jpeg" hidden @change="handleDropzoneFileChange" />
            <div class="lf-new-workspace__dropzone-icon" aria-hidden="true">＋</div>
            <div class="lf-new-workspace__dropzone-copy">
              <strong>{{ composerFiles.length ? `已加入 ${composerFiles.length} 个 Context Files` : '拖入教学资料到这里' }}</strong>
              <small>{{ composerFiles.length ? '发送后会绑定到当前 Mission' : '教材、教案、PPTX 模板或课堂图片 · 也可以点击选择' }}</small>
            </div>
            <button class="lf-new-workspace__dropzone-action" type="button" @click.stop="openFilePicker">{{ composerFiles.length ? '继续添加' : '选择文件' }}</button>
          </div>
          <p class="lf-new-workspace__boundary">首条消息发送后才会创建 Mission；发送前的文件会保留在当前页面。</p>
        </div>
        <div v-if="error" class="lf-new-workspace__notice" role="alert">{{ error }}</div>
      </section>
      <div class="lf-new-mission__composer" data-test="go-new-composer">
        <p class="lf-new-workspace__context-summary" data-test="context-summary"><span>Context</span><strong>{{ composerFiles.length }} 个文件</strong><span>·</span><span>{{ selectedConnection?.name || '尚未选择模型' }}</span></p>
        <LessonForgeComposer v-model="draft" variant="empty" :files="composerFiles" :connections="connections" :selected-connection="selectedConnection" :working="sending || uploading" placeholder="Describe the lesson or courseware you want to create…" @send="handleSend" @files-selected="handleFilesSelected" @select-connection="handleConnectionSelection" @manage-connections="connectionDrawerOpen = true" />
        <p class="lf-new-mission__composer-status" data-test="go-composer-status">{{ composerStatus }}</p>
      </div>
      <ModelConnectionDrawer v-model="connectionDrawerOpen" :selected-connection="selectedConnection" :selected-connection-id="selectedConnection?.id ?? null" @update:connection="handleConnectionSelection" @connections-loaded="handleConnectionsLoaded" />
    </section>
  </LessonForgeFrame>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import LessonForgeFrame from '@/components/lessonForge/LessonForgeFrame.vue';
import LessonForgeComposer from '@/components/lessonForge/LessonForgeComposer.vue';
import ModelConnectionDrawer from '@/components/assistant/ModelConnectionDrawer.vue';
import { createGoMission, goErrorMessage, listGoMissions, uploadGoTemporary, type GoMission } from '@/api/go';
import { getModelConnections, type ModelConnection } from '@/api/aiCredentials';
import { composerFileIdentity, mergeComposerFiles, type ComposerFile } from '@/utils/lessonForgeComposer';
import type { LessonForgeMission } from '@/types/lessonForge';

const router = useRouter();
const draft = ref('');
const composerFiles = ref<ComposerFile[]>([]);
const connections = ref<ModelConnection[]>([]);
const selectedConnection = ref<ModelConnection | null>(null);
const connectionDrawerOpen = ref(false);
const loadingConnections = ref(false);
const uploading = ref(false);
const sending = ref(false);
const created = ref(false);
const error = ref('');
const temporaryUploads = ref(new Map<string, string>());
const failedUploads = ref(new Set<string>());
const recent = ref<GoMission[]>([]);
const contextFileInput = ref<HTMLInputElement>();
const draggingFiles = ref(false);
const quickPrompts = [
  { title: '制作一节新课件', description: '从教学目标开始', text: '制作一节高中物理《牛顿运动定律》课件' },
  { title: '基于教材整理', description: '自动提炼章节重点', text: '把教材重点整理成一套课堂讲解课件' },
  { title: '沿用已有模板', description: '保留视觉结构和版式', text: '使用我上传的 PPTX 模板，为高一学生补充案例、练习和课堂活动' },
];
const recentMissions = computed<LessonForgeMission[]>(() => recent.value.slice(0, 8).map((item) => ({ id: String(item.id), teacherId: item.ownerTeacherId, title: item.title, description: item.description, status: 'IN_PROGRESS', selectedConnectionId: item.selectedModelConnectionId, currentPhase: 'UNAVAILABLE', surfaceGate: { submission: false, feedback: false }, recentActivity: item.status, sources: [], messages: [], activities: [], submissions: [] })));
const composerStatus = computed(() => uploading.value ? '正在上传文件…' : sending.value ? '正在创建 Mission…' : selectedConnection.value ? `${selectedConnection.value.name} · 已选择` : '可以先发送；没有连接时 Agent 会等待补充');

onMounted(async () => { await loadConnections(); try { recent.value = await listGoMissions(); } catch { recent.value = []; } });
async function loadConnections() { loadingConnections.value = true; try { const response = await getModelConnections(); connections.value = response.code === 0 ? response.data || [] : []; } catch { connections.value = []; } finally { loadingConnections.value = false; } }
function handleConnectionsLoaded(value: ModelConnection[]) { connections.value = value; if (!value.some((item) => item.id === selectedConnection.value?.id && item.enabled && item.verificationStatus === 'VERIFIED')) selectedConnection.value = null; }
function handleConnectionSelection(value: { id: number } | ModelConnection | null) { const id = value?.id; selectedConnection.value = id == null ? null : connections.value.find((item) => item.id === id && item.enabled && item.verificationStatus === 'VERIFIED') || null; }
function openFilePicker() { contextFileInput.value?.click(); }
function addDroppedFiles(files: File[]) {
  if (!files.length) return;
  void handleFilesSelected(mergeComposerFiles(composerFiles.value, files.map((file) => ({ name: file.name, file }))));
}
function handleDroppedFiles(event: DragEvent) { draggingFiles.value = false; addDroppedFiles(Array.from(event.dataTransfer?.files || [])); }
function handleDropzoneFileChange(event: Event) { const input = event.target as HTMLInputElement; addDroppedFiles(Array.from(input.files || [])); input.value = ''; }
async function handleFilesSelected(value: ComposerFile[]) {
  composerFiles.value = value;
  const pending = value.filter((item) => item.file && !temporaryUploads.value.has(composerFileIdentity(item)) && !failedUploads.value.has(composerFileIdentity(item)));
  if (!pending.length) return;
  uploading.value = true;
  error.value = '';
  try { for (const item of pending) { if (!item.file) continue; const upload = await uploadGoTemporary(item.file); temporaryUploads.value.set(composerFileIdentity(item), upload.uploadId); } }
  catch (reason) { const current = pending.find((item) => !temporaryUploads.value.has(composerFileIdentity(item))); if (current) failedUploads.value.add(composerFileIdentity(current)); error.value = goErrorMessage(reason, '文件上传失败，未创建 Mission。'); }
  finally { uploading.value = false; }
}
async function handleSend(payload: { text: string; files: ComposerFile[] }) {
  if (!payload.text.trim()) { error.value = '请先写下课件需求。'; return; }
  if (uploading.value || payload.files.some((item) => item.file && !temporaryUploads.value.has(composerFileIdentity(item)))) { error.value = '文件仍在上传，请稍候再发送。'; return; }
  if (failedUploads.value.size) { error.value = '有文件未上传成功，请移除后重试。'; return; }
  sending.value = true; error.value = '';
  try { const result = await createGoMission({ title: payload.text.split(/\r?\n/, 1)[0].slice(0, 200), message: payload.text, uploadIds: payload.files.flatMap((item) => { const id = temporaryUploads.value.get(composerFileIdentity(item)); return id ? [id] : []; }), modelConnectionId: selectedConnection.value?.id ?? null }); created.value = true; router.push({ name: 'lessonforge-mission', params: { missionId: result.missionId } }); }
  catch (reason) { error.value = goErrorMessage(reason, 'Mission 创建失败；未伪造成功状态。'); }
  finally { sending.value = false; }
}
</script>
