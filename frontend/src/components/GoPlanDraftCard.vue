<template>
  <section class="go-plan-preview lf-plan-card" data-test="go-plan-draft">
    <header class="go-plan-preview__header">
      <div>
        <div class="lf-card-kicker">COURSEWARE PLAN · v{{ draft.version }}</div>
        <h2>课件方案草稿</h2>
      </div>
      <div class="go-plan-preview__header-actions">
        <span class="go-plan-preview__status">{{ locked ? '已锁定' : '待确认' }}</span>
        <button v-if="!locked" class="lf-primary-button go-plan-preview__approve" type="button" :disabled="approving || Boolean(approvalBlocker)" :title="approvalBlocker || undefined" data-test="approve-plan" @click="$emit('approve')">
          {{ approving ? '正在锁定…' : '批准方案' }}
        </button>
      </div>
    </header>
    <p v-if="!locked && approvalBlocker" class="go-plan-preview__approval-requirement" data-test="approval-prerequisite">{{ approvalBlocker }}</p>
    <div v-if="facts.length" class="go-plan-preview__summary">
      <span v-for="fact in facts" :key="fact">{{ fact }}</span>
    </div>
    <div class="go-plan-preview__intro">
      <div class="go-plan-preview__intro-label">课程设计意图</div>
      <p>{{ intro }}</p>
    </div>
    <div v-if="slides.length" class="go-plan-slides">
      <div class="go-plan-slides__heading"><span>逐页布局规划</span><small>{{ slides.length }} 页 · 每页均可继续修改</small></div>
      <article v-for="slide in slides" :key="slide.key" class="go-plan-slide" :data-test="`go-plan-slide-${slide.index}`">
        <div class="go-plan-slide__topline"><span class="go-plan-slide__index">SLIDE {{ slide.index }}</span><span class="go-plan-slide__tag">{{ slide.layout }}</span></div>
        <h3>{{ slide.title }}</h3>
        <div class="go-plan-slide__grid">
          <div><span class="go-plan-slide__label">教学目的</span><p>{{ slide.purpose }}</p></div>
          <div><span class="go-plan-slide__label">页面内容</span><ul><li v-for="point in slide.points" :key="point">{{ point }}</li></ul></div>
          <div><span class="go-plan-slide__label">视觉与布局</span><p>{{ slide.visual }}</p></div>
          <div><span class="go-plan-slide__label">课堂动作</span><p>{{ slide.classroomAction }}</p></div>
        </div>
        <div v-if="slide.sources.length" class="go-plan-slide__sources"><span>来源</span><em v-for="source in slide.sources" :key="source">{{ source }}</em></div>
      </article>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { presentPlanSlides } from '@/utils/conversationPresentation';
import type { GoPlanningDraft } from '@/api/go';

const props = defineProps<{ draft: GoPlanningDraft; locked: boolean; facts: string[]; approving: boolean; approvalBlocker: string }>();
defineEmits<{ approve: [] }>();
const slides = computed(() => presentPlanSlides(props.draft));
const intro = computed(() => {
  const raw = props.draft.structuredPlan;
  if (raw && typeof raw === 'object' && !Array.isArray(raw)) {
    const record = raw as Record<string, unknown>;
    for (const key of ['overview', 'summary', 'teachingIntent', 'objective']) if (typeof record[key] === 'string' && record[key].trim()) return record[key] as string;
  }
  return '围绕课程目标组织概念讲解、案例分析与课堂练习，让教师可以先确认整体结构，再逐页检查内容和呈现方式。';
});
</script>
