<template>
  <section class="lf-plan-card go-question-history" data-test="go-question-history">
    <header class="go-question-history__header">
      <div>
        <div class="lf-card-kicker">REQUIREMENTS CHECK</div>
        <h2>需求确认记录</h2>
      </div>
      <span class="go-question-history__state">{{ answeredCount }}/{{ groups.length }} 已确认</span>
    </header>
    <div class="go-question-history__list">
      <article v-for="group in groups" :key="group.key" class="go-question-history__item" :data-test="`go-question-history-${group.index}`">
        <div class="go-question-history__item-head">
          <span class="go-question-history__number">问题 {{ group.index }}</span>
          <span class="go-question-history__answered">{{ group.answered ? '已回答' : '待处理' }}</span>
        </div>
        <h3>{{ group.title }}</h3>
        <p v-if="group.prompt" class="go-question-history__prompt">{{ group.prompt }}</p>
        <div v-if="group.options.length" class="go-question-history__choice-block">
          <div class="go-question-history__label">{{ group.answered ? '教师已确认的方向' : '可选方向' }}</div>
          <div class="go-question-history__options" role="list">
            <div v-for="option in group.options" :key="option.code" class="go-question-history__option" :class="{ 'is-selected': option.selected }" role="listitem" :aria-label="`${option.code} ${option.label}${option.selected ? '，已选择' : ''}`">
              <span class="go-question-history__option-mark">{{ option.selected ? '✓' : option.code }}</span>
              <span><strong>{{ option.label }}</strong><small v-if="option.detail">{{ option.detail }}</small></span>
            </div>
          </div>
        </div>
        <div v-if="group.answer" class="go-question-history__answer">
          <span>教师回答</span>
          <p>{{ group.answer }}</p>
        </div>
      </article>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { presentQuestionGroups } from '@/utils/conversationPresentation';
import type { GoQuestion } from '@/api/go';

const props = defineProps<{ questions: GoQuestion[] }>();
const groups = computed(() => props.questions.flatMap((question) => presentQuestionGroups(question)));
const answeredCount = computed(() => groups.value.filter((group) => group.answered).length);
</script>
