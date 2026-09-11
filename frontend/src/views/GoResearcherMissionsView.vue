<template>
  <LessonForgeFrame class="lf-app--light-missions" active="missions" context-label="Review" :recent-missions="missionsAsRecent" :search-missions="missionsAsRecent" :show-new-mission="false">
    <section class="lf-overview go-review-queue" aria-labelledby="go-review-queue-title">
      <header class="lf-page-heading">
        <div>
          <div class="lf-eyebrow">REVIEW QUEUE</div>
          <h1 id="go-review-queue-title">教研审核</h1>
          <p>查看已分派给你的课件任务，并将可执行的反馈留在 Mission 内。</p>
        </div>
        <span class="go-review-queue__count">{{ pendingMissions.length }} 个待审核 · {{ feedbackMissions.length }} 个已反馈</span>
      </header>

      <div v-if="error" class="lf-inline-note lf-inline-note--warning" role="alert">{{ error }} <button class="lf-secondary-button" type="button" @click="load">重试</button></div>
      <div v-else-if="loading" class="lf-table-state" role="status">正在加载审核任务…</div>
      <div v-else class="go-review-queue__list">
        <button v-for="mission in missions" :key="mission.id" class="go-review-queue__row" type="button" @click="openMission(mission.id)">
          <span class="go-review-queue__row-main"><strong>{{ mission.title }}</strong><small>{{ mission.description || '等待教研审核的课件任务' }}</small></span>
          <span class="go-review-queue__row-status"><i class="lf-status-dot" :class="`lf-status-dot--${statusClass(mission.status)}`" />{{ statusLabel(mission.status) }}</span>
          <time>{{ formatDate(mission.updatedAt) }}</time>
          <span class="go-review-queue__arrow" aria-hidden="true">›</span>
        </button>
        <div v-if="!missions.length" class="lf-table-empty" role="status">当前没有分派给你的审核任务。</div>
      </div>
    </section>
  </LessonForgeFrame>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import LessonForgeFrame from '@/components/lessonForge/LessonForgeFrame.vue';
import type { LessonForgeMission } from '@/types/lessonForge';
import { goErrorMessage, listGoResearcherMissions, type GoMission } from '@/api/go';

const router = useRouter();
const loading = ref(false);
const error = ref('');
const missions = ref<GoMission[]>([]);
const pendingMissions = computed(() => missions.value.filter((mission) => mission.status === 'SUBMITTED' || mission.status === 'COMPLETED'));
const feedbackMissions = computed(() => missions.value.filter((mission) => mission.status === 'FEEDBACK'));
const missionsAsRecent = computed<LessonForgeMission[]>(() => missions.value.slice(0, 8).map((mission) => ({ id: String(mission.id), teacherId: mission.ownerTeacherId, title: mission.title, description: mission.description, status: 'FEEDBACK', selectedConnectionId: mission.selectedModelConnectionId, currentPhase: 'UNAVAILABLE', surfaceGate: { submission: false, feedback: true }, recentActivity: mission.status, sources: [], messages: [], activities: [], submissions: [] })));

async function load() {
  loading.value = true;
  error.value = '';
  try { missions.value = await listGoResearcherMissions(); }
  catch (reason) { error.value = goErrorMessage(reason, '暂时无法读取审核任务。'); }
  finally { loading.value = false; }
}
function openMission(id: number) { router.push({ name: 'lessonforge-researcher-review', params: { missionId: id } }); }
function statusLabel(status: string) { return ({ SUBMITTED: '待审核', COMPLETED: '待审核', FEEDBACK: '已反馈' } as Record<string, string>)[status] || status; }
function statusClass(status: string) { return status.toLowerCase().replaceAll('_', '-'); }
function formatDate(value: string) { const date = new Date(value); return Number.isNaN(date.valueOf()) ? '—' : date.toLocaleDateString('zh-CN'); }
onMounted(load);
</script>
