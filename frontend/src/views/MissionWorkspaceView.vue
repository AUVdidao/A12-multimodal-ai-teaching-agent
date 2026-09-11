<template>
  <section class="mission-page" data-test="mission-workspace">
    <div class="mission-shell">
      <MissionWorkspaceSidebar :missions="missionStore.missions" :current-mission-id="missionStore.currentMissionId" @select-mission="selectMission" />

      <main class="mission-main">
        <div v-if="missionStore.loading" class="mission-state">正在读取当前教师的 Mission fixture…</div>
        <div v-else-if="missionStore.error" class="mission-state is-error">{{ missionStore.error }}</div>
        <div v-else-if="!currentMission" class="mission-state">当前教师没有可见 Mission。</div>
        <template v-else>
          <header class="mission-header">
            <div>
              <h1>{{ currentMission.title }}</h1>
              <p>{{ currentMission.description }}</p>
            </div>
            <div class="mission-header__meta">
              <span :class="['mission-status', 'is-' + currentMission.status.toLowerCase()]">{{ currentMission.decision === 'REJECTED' ? '已拒绝' : missionStatusLabels[currentMission.status] }}</span>
              <span>截止 {{ currentMission.deadline }}</span>
            </div>
          </header>

          <section v-if="isAssigned" class="mission-assignment" data-test="mission-assignment">
            <span class="mission-assignment__eyebrow">需要教师确认</span>
            <h2>接受这个 Mission 后，在当前页面进入 Conversation。</h2>
            <p>接受与拒绝仅更新当前浏览器内的演示状态，不会写入后端。</p>
            <div class="mission-assignment__actions">
              <button type="button" class="mission-button mission-button--primary" data-test="accept-mission" @click="acceptMission">接受 Mission</button>
              <textarea v-model="rejectReason" data-test="reject-reason" rows="2" maxlength="300" placeholder="如需拒绝，请填写原因" />
              <button type="button" class="mission-button mission-button--secondary" data-test="reject-mission" @click="rejectMission">拒绝并说明原因</button>
            </div>
            <p v-if="rejectError" class="mission-form-error" data-test="reject-error">{{ rejectError }}</p>
          </section>

          <section v-else-if="currentMission.decision === 'REJECTED'" class="mission-result" data-test="mission-rejected">
            <span class="mission-assignment__eyebrow">已记录当前演示决定</span>
            <h2>已拒绝此 Mission</h2>
            <p>原因：{{ currentMission.decisionReason }}</p>
            <small>该决定未提交后端；刷新后会恢复 fixture 初始状态。</small>
          </section>

          <section v-else class="mission-conversation" data-test="mission-conversation">
            <div v-if="messages.length === 0" class="mission-first-message" data-test="waiting-first-message">
              <strong>等待教师第一句话</strong>
              <span>Conversation 已就绪；发送前不会自动调用模型，也不会生成假成功。</span>
            </div>
            <AssistantConversation
              v-model="composerText"
              :messages="messages"
              :quick-prompts="[]"
              :loading="false"
              :empty="false"
              :sending="sending"
              :teacher-initial="teacherInitial"
              :files="contextFiles"
              :uploading="false"
              workspace-title="Conversation"
              loading-status-text="正在读取 Mission 上下文"
              empty-status-text="等待教师接受 Mission"
              ready-status-text="等待你的第一句话"
              context-files-subtitle="当前 Mission 文件"
              context-files-empty-text="暂无文件；添加后只保留本地占位。"
              composer-aria-label="输入 Mission 首句"
              empty-composer-placeholder="接受 Mission 后输入第一句话"
              loading-composer-placeholder="Mission 上下文准备中"
              composer-placeholder="输入你想在这个 Mission 中记录的第一句话"
              empty-composer-meta="接受 Mission 后即可输入"
              loading-composer-meta="Mission 上下文准备中"
              composer-meta="消息只保存在当前浏览器；不会调用 AI"
              :show-connection-control="true"
              :connection-label="selectedConnectionId ? '已选择连接' : '未选择连接'"
              :show-toolbar-actions="false"
              @send="sendMessage"
              @file-select="handleFileSelect"
              @open-connection="connectionDrawerOpen = true"
            />
          </section>
        </template>
      </main>
    </div>

    <ModelConnectionDrawer v-model="connectionDrawerOpen" :selected-connection-id="selectedConnectionId" @update:connection="handleConnectionUpdate" />
  </section>
</template>

<script setup lang="ts">
import AssistantConversation from '@/components/assistant/AssistantConversation.vue';
import MissionWorkspaceSidebar from '@/components/assistant/MissionWorkspaceSidebar.vue';
import ModelConnectionDrawer from '@/components/assistant/ModelConnectionDrawer.vue';
import { useAuthStore } from '@/stores/auth';
import { useConversationWorkspaceStore } from '@/stores/conversationWorkspace';
import { useMissionWorkspaceStore } from '@/stores/missionWorkspace';
import { missionStatusLabels } from '@/types/mission';
import type { AssistantMessage } from '@/types/assistant';
import type { ModelConnection } from '@/api/aiCredentials';
import { computed, onMounted, ref, watch } from 'vue';

const authStore = useAuthStore();
const conversationWorkspace = useConversationWorkspaceStore();
const missionStore = useMissionWorkspaceStore();
const composerText = ref('');
const sending = ref(false);
const rejectReason = ref('');
const rejectError = ref('');
const connectionDrawerOpen = ref(false);
const ready = ref(false);

const currentMission = computed(() => missionStore.currentMission);
const isAssigned = computed(() => currentMission.value?.status === 'ASSIGNED' && currentMission.value.decision === 'PENDING');
const teacherInitial = computed(() => (authStore.user?.displayName || '师').slice(0, 1));
const messages = computed(() => conversationWorkspace.activeSession?.messages || []);
const contextFiles = computed(() => conversationWorkspace.activeSession?.files || []);
const selectedConnectionId = computed(() => conversationWorkspace.selectedConnectionId);

onMounted(async () => {
  await hydrateMissionWorkspace(authStore.user?.id, true);
});

watch(() => authStore.user?.id, async (userId, previousUserId) => {
  if (userId === previousUserId) return;
  ready.value = false;
  composerText.value = '';
  rejectReason.value = '';
  rejectError.value = '';
  await hydrateMissionWorkspace(userId, false);
});

watch(() => missionStore.currentMissionId, () => {
  if (!ready.value) return;
  bindCurrentMissionSession();
});

function selectMission(missionId: string) {
  missionStore.selectMission(missionId);
  bindCurrentMissionSession();
  composerText.value = '';
  rejectReason.value = '';
  rejectError.value = '';
}

function acceptMission() {
  rejectError.value = '';
  missionStore.acceptCurrentMission();
  bindCurrentMissionSession();
}

function rejectMission() {
  if (!rejectReason.value.trim()) {
    rejectError.value = '请填写拒绝原因后再提交。';
    return;
  }
  rejectError.value = '';
  missionStore.rejectCurrentMission(rejectReason.value);
}

function sendMessage() {
  const content = composerText.value.trim();
  if (!content || sending.value) return;
  composerText.value = '';
  const createdAt = new Date().toISOString();
  const nextMessages = [...messages.value, { id: 'teacher-' + Date.now(), role: 'teacher' as const, content, createdAt, status: 'success' as const, persistenceStatus: 'not_required' as const }, {
    id: 'boundary-' + Date.now(),
    role: 'system',
    content: '已记录在当前浏览器会话；当前未调用 AI 或 Planning。',
    createdAt,
    status: 'pending',
    persistenceStatus: 'not_required',
    versionNotice: '真实模型与生成能力尚未接通。',
  } as AssistantMessage];
  conversationWorkspace.setSessionMessages(nextMessages);
}

function handleFileSelect(file: File) {
  conversationWorkspace.setSessionFiles([...contextFiles.value, {
    id: 'local-' + Date.now(),
    originalFilename: file.name,
    displayStatus: '仅本地占位 · 未上传',
  }]);
}

function handleConnectionUpdate(connection: ModelConnection | null) {
  conversationWorkspace.setConnection(connection?.id || null);
}

function missionSessionId(missionId: string) {
  return 'mission:' + missionId;
}

async function hydrateMissionWorkspace(userId: number | null | undefined, restoreActiveMission: boolean) {
  conversationWorkspace.hydrate(userId);
  missionStore.missions = [];
  missionStore.currentMissionId = null;
  composerText.value = '';
  rejectReason.value = '';
  rejectError.value = '';
  connectionDrawerOpen.value = false;
  await missionStore.load(userId);
  if (restoreActiveMission) {
    const restoredMissionId = conversationWorkspace.activeSession?.missionId;
    if (restoredMissionId && missionStore.missions.some((item) => item.id === restoredMissionId)) {
      missionStore.selectMission(restoredMissionId);
    }
  }
  ready.value = true;
  bindCurrentMissionSession();
}

function bindCurrentMissionSession() {
  const mission = currentMission.value;
  if (!mission || !authStore.user?.id) return;
  const sessionId = missionSessionId(mission.id);
  const existing = conversationWorkspace.sessions.find((session) => session.id === sessionId);
  if (existing) {
    conversationWorkspace.selectSession(sessionId);
  } else {
    conversationWorkspace.touchSession({
      id: sessionId,
      missionId: mission.id,
      title: mission.title,
      updatedAt: mission.updatedAt,
      status: 'active',
      connectionId: null,
    });
  }
}
</script>

<style scoped>
.mission-page {
  --factory-bg: #090808;
  --factory-panel: #111010;
  --factory-panel-raised: #171514;
  --factory-border: #302b29;
  --factory-muted: #918883;
  --factory-text: #f1ece8;
  --factory-accent: #ff6b16;
  display: grid;
  min-height: calc(100vh - 40px);
  background: var(--factory-bg);
  color: var(--factory-text);
}

.mission-shell {
  display: grid;
  min-height: 0;
  grid-template-columns: 168px minmax(0, 1fr);
}

.mission-main {
  display: flex;
  min-width: 0;
  min-height: 0;
  flex-direction: column;
  overflow: hidden;
}

.mission-state {
  margin: auto;
  padding: 28px;
  border: 1px dashed var(--factory-border);
  background: var(--factory-panel);
  color: var(--factory-muted);
  text-align: center;
}

.mission-state.is-error {
  color: #ff9184;
}

.mission-header {
  display: flex;
  flex: 0 0 auto;
  align-items: flex-start;
  justify-content: space-between;
  gap: 24px;
  padding: 17px 28px 13px;
  border-bottom: 1px solid var(--factory-border);
  background: var(--factory-bg);
}

.mission-header h1 {
  margin: 0 0 5px;
  color: var(--factory-text);
  font-size: clamp(18px, 1.7vw, 22px);
  font-weight: 560;
  letter-spacing: -0.02em;
}

.mission-header p {
  max-width: 780px;
  margin: 0;
  color: var(--factory-muted);
  font-size: 12px;
  line-height: 1.55;
}

.mission-header__meta {
  display: flex;
  align-items: center;
  gap: 13px;
  padding-top: 3px;
  color: var(--factory-muted);
  font-size: 11px;
  white-space: nowrap;
}

.mission-status {
  padding: 3px 7px;
  border: 1px solid #4c3326;
  border-radius: 4px;
  background: transparent;
  color: #ff9c63;
  font-size: 10px;
  font-weight: 650;
}

.mission-status.is-returned { border-color: #594027; color: #e7a65e; }
.mission-status.is-completed { border-color: #2f5a46; color: #72c78f; }

.mission-assignment,
.mission-result {
  max-width: 760px;
  margin: auto;
  padding: 24px;
  border: 1px solid var(--factory-border);
  border-radius: 5px;
  background: var(--factory-panel);
  box-shadow: none;
}

.mission-assignment h2,
.mission-result h2 {
  margin: 7px 0;
  color: var(--factory-text);
  font-size: 17px;
  font-weight: 560;
}

.mission-assignment p,
.mission-result p,
.mission-result small {
  color: var(--factory-muted);
  font-size: 12px;
  line-height: 1.6;
}

.mission-assignment__actions {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  margin-top: 18px;
}

.mission-assignment textarea {
  min-width: 210px;
  padding: 8px 10px;
  border: 1px solid var(--factory-border);
  border-radius: 4px;
  background: #0d0c0c;
  color: var(--factory-text);
  resize: vertical;
  font: inherit;
  font-size: 12px;
}

.mission-button {
  min-height: 32px;
  padding: 0 12px;
  border: 1px solid var(--factory-border);
  border-radius: 4px;
  background: transparent;
  color: #d8d0cb;
  cursor: pointer;
  font: inherit;
  font-size: 12px;
}

.mission-button--primary { border-color: var(--factory-accent); background: var(--factory-accent); color: #1d110b; }
.mission-button:hover { border-color: #6a5b52; }
.mission-form-error { color: #ff9184 !important; font-size: 12px; }

.mission-conversation {
  position: relative;
  display: flex;
  min-width: 0;
  min-height: 0;
  flex: 1 1 auto;
  flex-direction: column;
}

.mission-conversation > .assistant-conversation {
  min-height: 0;
  flex: 1 1 auto;
}

.mission-first-message {
  position: absolute;
  z-index: 1;
  top: 42%;
  left: 50%;
  display: grid;
  gap: 3px;
  width: min(380px, calc(100% - 48px));
  margin: 0;
  padding: 10px 14px;
  border: 1px solid var(--factory-border);
  border-radius: 4px;
  background: var(--factory-panel);
  color: var(--factory-muted);
  transform: translate(-50%, -50%);
  font-size: 12px;
}

.mission-first-message strong { color: #d8d0cb; font-weight: 560; }
.mission-first-message span { font-size: 11px; }

@media (max-width: 760px) {
  .mission-shell { grid-template-columns: 1fr; }
  .mission-header { padding: 18px 20px 14px; }
  .mission-header__meta { align-items: flex-end; flex-direction: column; gap: 5px; }
  .mission-conversation :deep(.assistant-conversation__body) { padding: 24px 20px 14px; }
}
</style>
