<template>
  <section v-if="currentPhase === 'SUCCEEDED'" class="lf-ppt-preview-layer" data-test="ppt-preview-layer" aria-label="PPT Preview Layer">
    <div class="lf-ppt-preview-layer__header"><span>PPT Preview</span><small>FRONTEND_ONLY · DEVELOPMENT_FIXTURE</small></div>
    <div class="lf-ppt-preview-scroll" data-test="ppt-preview-scroll">
      <article v-for="page in pages" :key="page" class="lf-ppt-slide" :data-test="`ppt-slide-${page}`"><div class="lf-ppt-slide__canvas"><span class="lf-ppt-slide__number">{{ page }} / {{ total }}</span><strong>{{ page === 1 ? title : `课堂内容 · 第 ${page} 页` }}</strong><i /><i /><i /></div></article>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import type { LessonForgePhase } from '@/types/lessonForge';
const props = withDefaults(defineProps<{ currentPhase: LessonForgePhase; total: number; title?: string }>(), { title: 'LessonForge 课堂课件' });
const pages = computed(() => Array.from({ length: Math.max(1, Math.min(props.total, 30)) }, (_, index) => index + 1));
</script>
