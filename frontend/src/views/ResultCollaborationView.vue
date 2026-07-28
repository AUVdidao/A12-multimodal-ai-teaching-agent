<template>
  <section class="page">
    <header class="page-hero">
      <div>
        <h2>{{ isLeader ? '审批概览' : '成果概览' }}</h2>
        <p>{{ isLeader ? '集中处理教师提交的教学成果，完成审批、退回修改与发布。' : '提交教学成果，跟踪审核进度，并处理退回修改。' }}</p>
      </div>
    </header>

    <section class="collaboration-list" aria-label="成果协作入口">
      <button
        v-for="item in actions"
        :key="item.title"
        class="collaboration-list__item"
        type="button"
        @click="router.push(item.to)"
      >
        <span class="collaboration-list__copy">
          <strong>{{ item.title }}</strong>
          <small>{{ item.description }}</small>
        </span>
        <span class="collaboration-list__arrow" aria-hidden="true">进入</span>
      </button>
    </section>
  </section>
</template>

<script setup lang="ts">
import { useAuthStore } from '@/stores/auth';
import { computed } from 'vue';
import { useRouter, type RouteLocationRaw } from 'vue-router';

const auth = useAuthStore();
const router = useRouter();
const isLeader = computed(() => auth.activeRole === 'LEADER');

const actions = computed<Array<{ title: string; description: string; to: RouteLocationRaw }>>(() => isLeader.value
  ? [
      { title: '待审批成果', description: '查看并处理待审批的教学成果与退回意见。', to: { name: 'leader-approvals' } },
      { title: '成果发布', description: '发布审批通过的教学成果，并查看发布状态。', to: { name: 'leader-publications' } },
    ]
  : [
      { title: '待提交成果', description: '查看需要继续完善或提交的教学任务。', to: { name: 'teacher-teaching-tasks' } },
      { title: '审核进度与退回修改', description: '查看审批状态，并处理需要修改后重新提交的成果。', to: { name: 'teacher-approvals' } },
      { title: '发布记录', description: '查看已发布成果的状态与记录。', to: { name: 'teacher-publications' } },
    ]);
</script>

<style scoped>
.collaboration-list {
  overflow: hidden;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  background: var(--color-surface);
}

.collaboration-list__item {
  display: flex;
  width: 100%;
  min-height: 76px;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  padding: 16px 20px;
  border: 0;
  border-bottom: 1px solid var(--color-border);
  background: transparent;
  color: inherit;
  text-align: left;
  cursor: pointer;
}

.collaboration-list__item:last-child { border-bottom: 0; }
.collaboration-list__item:hover { background: var(--color-primary-soft); }
.collaboration-list__item:focus-visible { outline: 3px solid var(--color-primary-border); outline-offset: -3px; }
.collaboration-list__copy { display: grid; gap: 5px; min-width: 0; }
.collaboration-list__copy strong { color: var(--color-text); font-size: 16px; }
.collaboration-list__copy small { color: var(--color-text-secondary); font-size: 14px; line-height: 1.55; }
.collaboration-list__arrow { flex: 0 0 auto; color: var(--color-primary); font-size: 14px; font-weight: 700; }

@media (max-width: 560px) {
  .collaboration-list__item { align-items: flex-start; gap: 12px; padding: 15px; }
}
</style>
