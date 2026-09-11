<template>
  <aside class="lf-board" data-test="mission-board">
    <section class="lf-board-section lf-board-section--mission" data-test="board-mission">
      <div class="lf-board-label">MISSION</div>
      <dl class="lf-mission-facts"><div><dt>任务说明</dt><dd>{{ mission.description }}</dd></div><div v-if="mission.deadline"><dt>截止时间</dt><dd>{{ mission.deadline }}</dd></div><div><dt>负责人</dt><dd>教师本人</dd></div></dl>
    </section>
    <section v-if="(mission.currentPhase === 'DRAFT' || mission.currentPhase === 'LOCKED') && mission.plan" class="lf-board-section lf-board-section--courseware" data-test="board-courseware">
      <div class="lf-board-label">COURSEWARE <span>{{ mission.plan.pages }} slides</span></div>
      <button class="lf-plan-row" type="button" :aria-expanded="planOpen" @click="$emit('toggle-plan')">
        <span class="lf-plan-file">≡</span>
        <span><strong>{{ mission.plan.version }}</strong><small>{{ mission.currentPhase === 'LOCKED' ? 'Locked' : 'Draft' }}</small></span>
        <span class="lf-chevron" aria-hidden="true">{{ planOpen ? '⌃' : '⌄' }}</span>
      </button>
      <div v-if="planOpen" class="lf-board-plan">
        <p>{{ mission.plan.summary }}</p>
        <ol><li v-for="section in mission.plan.sections" :key="section">{{ section }}</li></ol>
      </div>
    </section>
    <section class="lf-board-section lf-board-section--activity" data-test="board-activity">
      <div class="lf-board-label">ACTIVITY</div>
      <div v-if="mission.activities.length" class="lf-activity-list">
        <div v-for="activity in mission.activities" :key="`${activity.label}-${activity.time}`" class="lf-activity">
          <span class="lf-activity__mark" :class="`is-${activity.tone || 'default'}`">{{ activity.tone === 'success' ? '✓' : '·' }}</span>
          <span><strong>{{ activity.label }}</strong><small>{{ activity.detail }}</small></span>
          <time>{{ activity.time }}</time>
        </div>
      </div>
      <div v-else class="lf-board-empty">暂无业务事件</div>
    </section>
  </aside>
</template>

<script setup lang="ts">
import type { LessonForgeMission } from '@/types/lessonForge';
defineProps<{ mission: LessonForgeMission; planOpen?: boolean }>();
defineEmits<{ 'toggle-plan': [] }>();
</script>
