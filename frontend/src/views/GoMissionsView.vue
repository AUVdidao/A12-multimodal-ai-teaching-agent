<template>
  <LessonForgeFrame
    class="lf-app--light-missions"
    :recent-missions="recentMissions"
    :search-missions="recentMissions"
    active="missions"
    context-label="Missions"
    show-new-mission
  >
    <section class="lf-overview lf-missions-overview go-missions-page" aria-labelledby="go-missions-title">
      <header class="lf-page-heading lf-missions-heading">
        <div>
          <div class="lf-eyebrow">MISSIONS</div>
          <h1 id="go-missions-title">你的课件任务</h1>
          <p>查看你的课件任务，并继续最近的工作。</p>
        </div>
        <div class="lf-missions-heading__actions">
          <span class="lf-missions-heading__count">{{ missions.length }} 个任务</span>
        </div>
      </header>

      <div class="lf-mission-summary" aria-label="Mission overview">
        <div><strong>{{ missions.length }}</strong><span>Total</span></div>
        <div><strong>{{ filterCount('IN_PROGRESS') }}</strong><span>In Progress</span></div>
        <div><strong>{{ filterCount('ASSIGNED') }}</strong><span>Pending</span></div>
        <div><strong>{{ filterCount('FEEDBACK') }}</strong><span>Feedback</span></div>
      </div>

      <div v-if="error" class="lf-inline-note lf-inline-note--warning" role="alert">{{ error }} <button class="lf-secondary-button" type="button" @click="load">重试</button></div>
      <div v-else-if="loading" class="lf-table-state" role="status">正在加载 Missions…</div>
      <template v-else>
        <nav class="lf-filter-tabs" aria-label="Mission filters">
          <button v-for="filter in filters" :key="filter.value" type="button" :class="{ 'is-active': currentFilter === filter.value }" :aria-pressed="currentFilter === filter.value" @click="currentFilter = filter.value">
            <span>{{ filter.label }}</span>
            <small>{{ filterCount(filter.value) }}</small>
          </button>
        </nav>
        <div class="lf-mission-table" role="table" aria-label="Missions">
          <div class="lf-table-head" role="row"><span>Mission</span><span>Progress</span><span>Status</span><span>Updated</span><span>Model</span></div>
          <button v-for="mission in visibleMissions" :key="mission.id" class="lf-mission-row" type="button" role="row" @click="openMission(mission.id)">
            <span class="lf-mission-row__title"><strong>{{ mission.title }}</strong><small>{{ mission.description || '继续当前课件任务' }}</small></span>
            <span class="lf-mission-progress" :aria-label="`进度：${progressInfo(mission.status).stage}`">
              <span class="lf-mission-progress__track" aria-hidden="true"><i :style="{ width: `${progressInfo(mission.status).segments * 25}%` }" /></span>
              <small>{{ progressInfo(mission.status).stage }}</small>
            </span>
            <span><span class="lf-status-chip"><i class="lf-status-dot" :class="`lf-status-dot--${statusClass(mission.status)}`" />{{ statusLabel(mission.status) }}</span></span>
            <span class="lf-table-muted">{{ formatDate(mission.updatedAt || mission.createdAt) }}</span>
            <span class="lf-mission-model">{{ modelLabel(mission) }}</span>
          </button>
          <div v-if="!visibleMissions.length" class="lf-table-empty" role="status">还没有可显示的 Mission。</div>
        </div>
      </template>
    </section>
  </LessonForgeFrame>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import LessonForgeFrame from '@/components/lessonForge/LessonForgeFrame.vue';
import { listGoMissions, goErrorMessage, type GoMission } from '@/api/go';
import type { LessonForgeMission, LessonForgeMissionStatus } from '@/types/lessonForge';

const router = useRouter();
const loading = ref(false);
const error = ref('');
const missions = ref<GoMission[]>([]);
const currentFilter = ref('ALL');
const filters = [
  { value: 'ALL', label: 'All' },
  { value: 'ASSIGNED', label: 'Pending' },
  { value: 'IN_PROGRESS', label: 'In progress' },
  { value: 'FEEDBACK', label: 'Feedback' },
  { value: 'SUBMITTED', label: 'Submitted' },
];

const visibleMissions = computed(() => missions.value.filter((item) => matchesFilter(item.status, currentFilter.value)));
const recentMissions = computed<LessonForgeMission[]>(() => missions.value.slice(0, 8).map(toFrameMission));

function matchesFilter(status: string, filter: string) {
  if (filter === 'ALL') return true;
  if (filter === 'SUBMITTED') return status === 'SUBMITTED' || status === 'COMPLETED';
  if (filter === 'ASSIGNED') return status === 'ASSIGNED' || status === 'WAITING_INPUTS';
  if (filter === 'IN_PROGRESS') return status === 'IN_PROGRESS' || status === 'QUEUED' || status === 'RUNNING';
  return status === filter;
}

function filterCount(filter: string) {
  return missions.value.filter((item) => matchesFilter(item.status, filter)).length;
}

function progressInfo(status: string) {
  const progress: Record<string, { segments: number; stage: string }> = {
    ASSIGNED: { segments: 1, stage: '内容补充' },
    IN_PROGRESS: { segments: 3, stage: '课件生成' },
    SUBMITTED: { segments: 4, stage: '待审核' },
    COMPLETED: { segments: 4, stage: '已完成' },
    WAITING_INPUTS: { segments: 1, stage: '内容补充' },
    QUEUED: { segments: 2, stage: '内容确认' },
    RUNNING: { segments: 3, stage: '课件生成' },
    FEEDBACK: { segments: 3, stage: '等待修改' },
    FAILED: { segments: 0, stage: '需要处理' },
    CANCELLED: { segments: 0, stage: '已取消' },
  };
  return progress[status] || { segments: 0, stage: '待确认' };
}

function modelLabel(mission: GoMission) {
  if (mission.selectedModelConnectionId) return `Connection #${mission.selectedModelConnectionId}`;
  return '未选择';
}

function legacyStatus(status: string): LessonForgeMissionStatus {
  if (status === 'FEEDBACK') return 'FEEDBACK';
  if (status === 'SUBMITTED' || status === 'COMPLETED') return 'SUBMITTED';
  if (status === 'ASSIGNED' || status === 'WAITING_INPUTS') return 'ASSIGNED';
  return 'IN_PROGRESS';
}

async function load() {
  loading.value = true;
  error.value = '';
  try { missions.value = await listGoMissions(); }
  catch (reason) { error.value = goErrorMessage(reason, '暂时无法读取你的 Missions。'); }
  finally { loading.value = false; }
}
function openMission(id: number) { router.push({ name: 'lessonforge-mission', params: { missionId: id } }); }
function statusClass(status: string) { return status.toLowerCase().replaceAll('_', '-'); }
function statusLabel(status: string) { return ({ ASSIGNED: '待开始', IN_PROGRESS: '进行中', SUBMITTED: '已提交', COMPLETED: '已完成', WAITING_INPUTS: '待补充', QUEUED: '排队中', RUNNING: '进行中', FAILED: '失败', CANCELLED: '已取消', FEEDBACK: '待修改' } as Record<string, string>)[status] || status; }
function formatDate(value: string) { const date = new Date(value); return Number.isNaN(date.valueOf()) ? '—' : date.toLocaleDateString('zh-CN'); }
function toFrameMission(item: GoMission): LessonForgeMission {
  return { id: String(item.id), teacherId: item.ownerTeacherId, title: item.title, description: item.description, status: legacyStatus(item.status), selectedConnectionId: item.selectedModelConnectionId, currentPhase: 'UNAVAILABLE', surfaceGate: { submission: false, feedback: false }, recentActivity: item.status, sources: [], messages: [], activities: [], submissions: [] };
}
onMounted(load);
</script>
