<template>
  <section v-if="latest" class="go-feedback-card lf-plan-card" data-test="go-feedback-card">
    <button class="go-feedback-card__summary" type="button" :aria-expanded="expanded" data-test="go-feedback-toggle" @click="expanded = !expanded">
      <span class="go-feedback-card__summary-main">
        <span>
          <strong>已收到教研员反馈</strong>
          <small>{{ latest.rating }} 星 · {{ latest.reviewerName }} · 第 {{ latest.submissionVersion }} 版</small>
        </span>
      </span>
      <span class="go-feedback-card__chevron" aria-hidden="true">{{ expanded ? '⌃' : '›' }}</span>
    </button>

    <div v-if="expanded" class="go-feedback-card__details" data-test="go-feedback-details">
      <div class="go-feedback-card__meta">
        <span>总体意见</span>
        <time>{{ formatDate(latest.createdAt) }}</time>
      </div>
      <p class="go-feedback-card__summary-text">{{ latest.summary }}</p>

      <div v-if="latest.items.length" class="go-feedback-card__items">
        <div class="go-feedback-card__section-label">逐页建议</div>
        <article v-for="(item, index) in latest.items" :key="`${latest.id}-${index}`" class="go-feedback-card__item">
          <div class="go-feedback-card__item-heading">
            <strong>{{ item.slideNumber ? `Slide ${item.slideNumber} · ` : '' }}{{ item.slideTitle }}</strong>
            <span :class="`is-${item.severity.toLowerCase()}`">{{ severityLabel(item.severity) }}</span>
          </div>
          <p>{{ item.comment }}</p>
        </article>
      </div>

      <details v-if="feedback.length > 1" class="go-feedback-card__history">
        <summary>查看历史反馈（{{ feedback.length - 1 }}）</summary>
        <div v-for="item in feedback.slice(1)" :key="item.id" class="go-feedback-card__history-item">
          <strong>第 {{ item.submissionVersion }} 版 · {{ item.rating }} 星</strong>
          <span>{{ item.reviewerName }} · {{ formatDate(item.createdAt) }}</span>
          <p>{{ item.summary }}</p>
        </div>
      </details>

      <button class="lf-secondary-button go-feedback-card__continue" type="button" data-test="go-feedback-continue" @click="$emit('continue-editing')">继续修改课件</button>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue';
import type { GoFeedbackSeverity, GoMissionFeedback } from '@/api/go';

const props = defineProps<{ feedback: GoMissionFeedback[] }>();
defineEmits<{ 'continue-editing': [] }>();
const expanded = ref(false);
const latest = computed(() => props.feedback[0] || null);

function severityLabel(value: GoFeedbackSeverity) {
  return ({ INFO: '建议', IMPORTANT: '重要', BLOCKING: '需修改' } as Record<GoFeedbackSeverity, string>)[value];
}
function formatDate(value: string) {
  const date = new Date(value);
  return Number.isNaN(date.valueOf()) ? '—' : date.toLocaleString('zh-CN', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' });
}
</script>
