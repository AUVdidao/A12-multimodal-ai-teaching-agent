<template>
  <aside class="mission-sidebar" aria-label="Mission List">
    <header class="mission-sidebar__header">
      <div>
        <h2>Missions</h2>
      </div>
      <span class="mission-sidebar__count">{{ missions.length }}</span>
    </header>
    <div v-if="missions.length" class="mission-sidebar__list">
      <button
        v-for="mission in missions"
        :key="mission.id"
        type="button"
        class="mission-sidebar__item"
        :class="{ 'is-active': mission.id === currentMissionId }"
        @click="$emit('select-mission', mission.id)"
      >
        <strong>{{ mission.title }}</strong>
        <span>
          <i :class="'mission-status-dot is-' + mission.status.toLowerCase()" />
          {{ mission.decision === 'REJECTED' ? '已拒绝' : missionStatusLabels[mission.status] }}
        </span>
        <small v-if="mission.deadline">截止 {{ mission.deadline }}</small>
      </button>
    </div>
    <div v-else class="mission-sidebar__empty">当前教师没有可见 Mission。</div>
  </aside>
</template>

<script setup lang="ts">
import type { FrontendMission } from '@/types/mission';
import { missionStatusLabels } from '@/types/mission';

defineProps<{
  missions: FrontendMission[];
  currentMissionId: string | null;
}>();

defineEmits<{
  'select-mission': [missionId: string];
}>();
</script>

<style scoped>
.mission-sidebar { display: flex; min-width: 168px; flex-direction: column; border-right: 1px solid #2b2625; background: #0c0b0b; color: #ded7d2; }
.mission-sidebar__header { display: flex; align-items: center; justify-content: space-between; padding: 15px 12px 10px; }
.mission-sidebar h2 { margin: 0; color: #a9a09b; font-size: 10px; font-weight: 650; letter-spacing: .08em; text-transform: uppercase; }
.mission-sidebar__count { min-width: 20px; color: #77706b; font-size: 10px; text-align: right; }
.mission-sidebar__list { display: grid; gap: 1px; padding: 0 7px 18px; overflow: auto; }
.mission-sidebar__item { display: grid; gap: 3px; min-width: 0; padding: 8px 8px 8px 10px; border: 0; border-left: 2px solid transparent; border-radius: 3px; background: transparent; color: inherit; text-align: left; cursor: pointer; }
.mission-sidebar__item:hover { background: #171414; }
.mission-sidebar__item.is-active { border-left-color: #ff6b16; background: #211b19; }
.mission-sidebar__item strong { overflow: hidden; color: #e9e2dd; font-size: 10px; font-weight: 520; line-height: 1.35; text-overflow: ellipsis; white-space: nowrap; }
.mission-sidebar__item span { display: flex; align-items: center; gap: 5px; color: #948b86; font-size: 9px; }
.mission-sidebar__item small { color: #706965; font-size: 9px; }
.mission-status-dot { width: 6px; height: 6px; border-radius: 50%; background: #6b6460; }
.mission-status-dot.is-assigned { background: #d8904c; }.mission-status-dot.is-accepted { background: #9186d9; }.mission-status-dot.is-in_progress { background: #5c9bda; }.mission-status-dot.is-submitted { background: #a679c8; }.mission-status-dot.is-returned { background: #d99a51; }.mission-status-dot.is-completed { background: #67b47f; }
.mission-sidebar__empty { margin: 0 18px; color: #867d78; font-size: 11px; }
</style>
