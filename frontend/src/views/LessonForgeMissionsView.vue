<template>
  <LessonForgeFrame
    :recent-missions="recentMissions"
    :search-missions="viewerMissions"
    active="missions"
    context-label="Missions"
    show-new-mission
  >
    <section class="lf-overview lf-missions-overview" aria-labelledby="missions-title">
      <header class="lf-page-heading">
        <div>
          <div class="lf-eyebrow">LESSONFORGE</div>
          <h1 id="missions-title">Missions</h1>
          <p>{{ isLeader ? '管理课件任务，并继续最近的工作。' : '查看你的课件任务，并继续最近的工作。' }}</p>
        </div>
        <button class="lf-primary-button" type="button" @click="router.push({ name: 'lessonforge-new' })">＋ New Mission</button>
      </header>

      <div v-if="store.error" class="lf-inline-note lf-inline-note--warning" data-test="missions-api-boundary">
        Missions 暂时无法从服务器加载。此页不会创建本地假数据；请在后端接通后重试。
      </div>
      <div v-else-if="store.loading" class="lf-table-state" role="status">正在加载 Missions…</div>
      <template v-else>
        <nav class="lf-filter-tabs" aria-label="Mission filters">
          <button
            v-for="filter in filters"
            :key="filter.value"
            type="button"
            :class="{ 'is-active': currentFilter === filter.value }"
            :aria-current="currentFilter === filter.value ? 'page' : undefined"
            @click="currentFilter = filter.value"
          >
            {{ filter.label }}
          </button>
        </nav>

        <div class="lf-mission-table" role="table" aria-label="Missions">
          <div class="lf-table-head" role="row">
            <span role="columnheader">Title</span>
            <span role="columnheader">Status</span>
            <span role="columnheader">Deadline</span>
            <span role="columnheader">Recent activity</span>
          </div>
          <button
            v-for="mission in visibleMissions"
            :key="mission.id"
            class="lf-mission-row"
            type="button"
            role="row"
            @click="router.push({ name: 'lessonforge-mission', params: { missionId: mission.id } })"
          >
            <span class="lf-mission-row__title" role="cell">
              <strong>{{ mission.title }}</strong>
              <small>{{ mission.description }}</small>
            </span>
            <span role="cell">
              <span class="lf-status-chip">
                <i class="lf-status-dot" :class="`lf-status-dot--${mission.status.toLowerCase()}`" />
                {{ lessonForgeStatusLabels[mission.status] }}
              </span>
            </span>
            <span class="lf-table-muted" role="cell">{{ mission.deadline || '—' }}</span>
            <span class="lf-table-muted" role="cell">{{ mission.recentActivity }}</span>
          </button>
          <div v-if="!visibleMissions.length" class="lf-table-empty" role="status">
            {{ currentFilter === 'ALL' ? '还没有可显示的 Mission。' : '此筛选下没有 Mission。' }}
          </div>
        </div>
      </template>
    </section>
  </LessonForgeFrame>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import LessonForgeFrame from '@/components/lessonForge/LessonForgeFrame.vue';
import { useLessonForgeStore } from '@/stores/lessonForge';
import { lessonForgeStatusLabels, type LessonForgeMissionStatus } from '@/types/lessonForge';
import { useAuthStore } from '@/stores/auth';

const router = useRouter();
const store = useLessonForgeStore();
const auth = useAuthStore();
const isLeader = computed(() => auth.activeRole === 'LEADER');
const currentFilter = ref<LessonForgeMissionStatus | 'ALL'>('ALL');
const filters: Array<{ value: LessonForgeMissionStatus | 'ALL'; label: string }> = [
  { value: 'ALL', label: 'All' },
  { value: 'ASSIGNED', label: 'Pending' },
  { value: 'IN_PROGRESS', label: 'In progress' },
  { value: 'FEEDBACK', label: 'Feedback' },
  { value: 'SUBMITTED', label: 'Submitted' },
];

const viewerMissions = computed(() => store.missionsForViewer(auth.user?.id ?? null, auth.activeRole));
const recentMissions = computed(() => store.recentMissionsForViewer(auth.user?.id ?? null, auth.activeRole));
const visibleMissions = computed(() => currentFilter.value === 'ALL'
  ? viewerMissions.value
  : viewerMissions.value.filter((mission) => mission.status === currentFilter.value));

onMounted(() => {
  if (auth.user?.id) store.load(auth.user.id);
});
</script>
