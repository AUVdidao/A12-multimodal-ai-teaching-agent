<template>
  <section class="page">
    <header class="page-hero">
      <div>
        <h2>学生问答</h2>
        <p>集中查看并回复学生在当前教学项目中的问题。</p>
      </div>
    </header>

    <div class="interaction-actions">
      <button class="interaction-action" type="button" @click="router.push(questionRoute)">
        <strong>学生问答</strong>
        <span>查看并回复当前范围内的学生问题。</span>
        <em>进入 <b aria-hidden="true">→</b></em>
      </button>
    </div>
  </section>
</template>

<script setup lang="ts">
import { useAuthStore } from '@/stores/auth';
import { computed } from 'vue';
import { useRouter, type RouteLocationRaw } from 'vue-router';

const auth = useAuthStore();
const router = useRouter();
const questionRoute = computed<RouteLocationRaw>(() => ({
  name: auth.activeRole === 'LEADER' ? 'leader-questions' : 'teacher-questions',
}));
</script>

<style scoped>
.interaction-actions { display: grid; grid-template-columns: repeat(auto-fit, minmax(240px, 1fr)); gap: 16px; }
.interaction-action { display: grid; min-height: 174px; align-content: start; gap: 11px; padding: 22px; border: 1px solid var(--color-border); border-radius: 8px; background: var(--color-surface); text-align: left; cursor: pointer; }
.interaction-action:hover { border-color: var(--color-primary-border); box-shadow: 0 8px 18px rgba(41, 52, 84, 0.08); }
.interaction-action:focus-visible { outline: 3px solid var(--color-primary-border); outline-offset: 3px; }
.interaction-action strong { color: var(--color-text); font-size: 17px; }
.interaction-action span { color: var(--color-text-secondary); font-size: 14px; line-height: 1.65; }
.interaction-action em { margin-top: auto; color: var(--color-primary); font-size: 14px; font-style: normal; font-weight: 700; }
.interaction-action b { margin-left: 6px; }
</style>
