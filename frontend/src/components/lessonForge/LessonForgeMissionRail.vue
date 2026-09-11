<template>
  <aside class="lf-mission-rail" data-test="mission-index">
    <button class="lf-back-link" type="button" @click="$emit('back')">‹ <span>Back to Missions</span></button>
    <div class="lf-rail-sections">
      <section class="lf-rail-section" data-test="rail-sources"><div class="lf-rail-section__heading">Sources <span>{{ mission.sources.length }}</span></div><button v-for="file in mission.sources" :key="file.id" class="lf-rail-file" type="button" @click="$emit('focus', file.name)"><span :title="file.name">{{ file.name }}</span></button><span v-if="!mission.sources.length" class="lf-rail-empty">暂无来源</span></section>
      <section v-if="(mission.currentPhase === 'DRAFT' || mission.currentPhase === 'LOCKED') && mission.plan" class="lf-rail-section" data-test="rail-courseware"><div class="lf-rail-section__heading">Courseware</div><button class="lf-rail-file" type="button" @click="$emit('plan')"><span class="lf-file-icon">≡</span><span>{{ mission.plan.version }} · {{ mission.currentPhase === 'LOCKED' ? 'Locked' : 'Draft' }}</span></button></section>
      <section v-if="mission.currentPhase === 'SUCCEEDED' && mission.output" class="lf-rail-section" data-test="rail-outputs"><div class="lf-rail-section__heading">Outputs</div><button class="lf-rail-file" type="button" @click="$emit('focus', mission.output.name)"><span class="lf-file-icon lf-file-icon--ppt">P</span><span :title="mission.output.name">{{ mission.output.name }}</span></button></section>
      <section v-if="mission.surfaceGate.submission && mission.submission" class="lf-rail-section" data-test="rail-submission"><div class="lf-rail-section__heading">Submission</div><button class="lf-rail-file" type="button" @click="$emit('focus', 'submission')"><span class="lf-file-icon lf-file-icon--ppt">P</span><span :title="mission.submission.fileName">{{ mission.submission.fileName }}</span></button></section>
    </div>
    <div class="lf-rail-account" data-test="mission-account"><span class="lf-avatar lf-avatar--small">{{ accountInitial }}</span><span class="lf-rail-account__name">{{ accountName }}</span><button class="lf-rail-account__more" type="button" aria-label="账户菜单">···</button></div>
  </aside>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import type { UserProfile } from '@/api/auth';
import type { LessonForgeMission } from '@/types/lessonForge';
const props = defineProps<{ mission: LessonForgeMission; user?: Pick<UserProfile, 'displayName' | 'username'> | null }>();
defineEmits<{ back: []; focus: [label: string]; plan: [] }>();
const accountName = computed(() => props.user?.displayName || props.user?.username || '教师');
const accountInitial = computed(() => accountName.value.slice(0, 1).toUpperCase());
</script>
