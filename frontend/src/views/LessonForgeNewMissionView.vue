<template>
  <LessonForgeFrame
    :recent-missions="recentMissions"
    :search-missions="viewerMissions"
    active="new"
    context-label="New Mission"
    show-new-mission
  >
    <section v-if="!isLeader" class="lf-new-mission lf-new-mission--workspace" data-test="new-mission-workspace">
      <header class="lf-new-workspace-header">
        <div>
          <h1>开始一个新的课件任务</h1>
          <p>告诉我你想制作什么课件，也可以直接添加教材、教案或模板。</p>
        </div>
      </header>

      <section class="lf-new-workspace__conversation" data-test="empty-conversation">
        <div class="lf-new-workspace__empty">
          <div class="lf-new-mission__mark" aria-hidden="true">✦</div>
          <h2>你想做什么课件？</h2>
          <p>先写下课程主题、教学对象和目标，后面可以继续补充要求。</p>
        </div>
        <div v-if="sendNotice" class="lf-new-workspace__notice" data-test="send-boundary" role="status">
          <span>已收到你的课件需求，可以继续补充内容。</span>
          <span class="lf-dev-only" aria-hidden="true">FRONTEND_ONLY · BACKEND_NOT_CONNECTED · 尚未创建真实 Mission/会话；需求和文件只保留在当前页面，未发送到服务器。</span>
        </div>
      </section>

      <div class="lf-new-mission__composer" data-test="fixed-composer">
        <LessonForgeComposer
          v-model="draft"
          variant="empty"
          :files="composerFiles"
          :connections="connections"
          :selected-connection="selectedConnection"
          placeholder="Describe the lesson or courseware you want to create…"
          @send="handleSend"
          @files-selected="composerFiles = $event"
          @select-connection="handleConnectionSelection"
          @manage-connections="connectionDrawerOpen = true"
        />
        <p class="lf-new-mission__composer-status" data-test="composer-status">{{ composerStatus }}</p>
      </div>

      <ModelConnectionDrawer
        v-model="connectionDrawerOpen"
        :selected-connection="selectedConnection"
        :selected-connection-id="selectedConnection?.id ?? null"
        @update:connection="handleConnectionSelection"
        @connections-loaded="handleConnectionsLoaded"
      />
    </section>

    <section v-else class="lf-new-mission">
      <div class="lf-new-mission__hero">
        <div class="lf-new-mission__mark">✦</div>
        <div class="lf-eyebrow">NEW MISSION</div>
        <h1>派发一个课件制作 Mission</h1>
        <p>填写教学目标并选择负责教师。附件、模型连接和生成配置属于接受后的教师工作区，不在创建请求中伪装保存。</p>
      </div>
      <select v-model="assignedTeacherId" class="lf-teacher-select" aria-label="选择教师">
        <option :value="0">选择负责教师</option>
        <option v-for="teacher in teachers" :key="teacher.id" :value="teacher.id">{{ teacher.displayName }}</option>
      </select>
      <LessonForgeComposer
        v-model="draft"
        variant="empty"
        :show-files="false"
        :show-connection="false"
        placeholder="Describe the lesson or courseware goal to assign…"
        @send="createMission"
      />
      <div class="lf-new-mission__hint">提交后立即创建并派发为 ASSIGNED；教师显式接受后才能写入 Workspace。</div>
    </section>
  </LessonForgeFrame>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import LessonForgeFrame from '@/components/lessonForge/LessonForgeFrame.vue';
import LessonForgeComposer from '@/components/lessonForge/LessonForgeComposer.vue';
import ModelConnectionDrawer from '@/components/assistant/ModelConnectionDrawer.vue';
import { useLessonForgeStore } from '@/stores/lessonForge';
import { useAuthStore } from '@/stores/auth';
import { getModelConnections, type ModelConnection } from '@/api/aiCredentials';
import { listLessonForgeTeachers, type TeacherApi } from '@/api/lessonForge';
import { isSelectableConnection } from '@/utils/conversationWorkspaceConnection';

const router = useRouter();
const store = useLessonForgeStore();
const auth = useAuthStore();
const isLeader = computed(() => auth.activeRole === 'LEADER');
const draft = ref('');
const composerFiles = ref<{ name: string; file?: File }[]>([]);
const teachers = ref<TeacherApi[]>([]);
const assignedTeacherId = ref(0);
const connections = ref<ModelConnection[]>([]);
const selectedConnection = ref<ModelConnection | null>(null);
const connectionDrawerOpen = ref(false);
const connectionState = ref<'loading' | 'loaded' | 'error'>('loading');
const sendNotice = ref(false);
type ConnectionSelection = { id: number; name: string; enabled?: boolean; verificationStatus?: ModelConnection['verificationStatus'] };

const viewerMissions = computed(() => store.missionsForViewer(auth.user?.id ?? null, auth.activeRole));
const recentMissions = computed(() => store.recentMissionsForViewer(auth.user?.id ?? null, auth.activeRole));
const composerStatus = computed(() => {
  if (connectionState.value === 'error') return '暂时无法加载模型连接';
  if (!selectedConnection.value) return '选择一个模型连接后开始';
  return `${selectedConnection.value.name} · 已准备好`;
});

onMounted(async () => {
  if (auth.user?.id && store.teacherId !== auth.user.id) await store.load(auth.user.id);
  if (isLeader.value) {
    teachers.value = await listLessonForgeTeachers();
    return;
  }
  await loadConnections();
});

async function loadConnections() {
  connectionState.value = 'loading';
  try {
    const response = await getModelConnections();
    if (response.code !== 0) throw new Error(response.message);
    handleConnectionsLoaded(response.data || []);
    connectionState.value = 'loaded';
  } catch {
    connectionState.value = 'error';
    connections.value = [];
    selectedConnection.value = null;
  }
}

function handleConnectionsLoaded(value: ModelConnection[]) {
  connections.value = value;
  if (!isSelectableConnection(selectedConnection.value) || !value.some((connection) => connection.id === selectedConnection.value?.id && isSelectableConnection(connection))) {
    selectedConnection.value = null;
  }
}

function handleConnectionSelection(value: ConnectionSelection | null) {
  const fullConnection = value && connections.value.find((connection) => connection.id === value.id);
  selectedConnection.value = fullConnection && isSelectableConnection(fullConnection) ? fullConnection : null;
}

function handleSend() {
  sendNotice.value = true;
}

async function createMission(payload: { text: string; files: { name: string }[] }) {
  if (!assignedTeacherId.value) return;
  const mission = await store.createMission(payload.text.slice(0, 200) || '未命名 Mission', payload.text, assignedTeacherId.value);
  router.push({ name: 'lessonforge-mission', params: { missionId: mission.id } });
}
</script>
