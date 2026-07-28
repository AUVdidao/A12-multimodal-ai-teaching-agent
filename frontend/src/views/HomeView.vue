<template>
  <section class="scene-home page">
    <header class="scene-home__intro">
      <p class="scene-home__eyebrow">A12 教学智能体</p>
      <h2>{{ greeting }}，{{ auth.user?.displayName || '用户' }}</h2>
      <p>请选择一个工作场景，继续处理教学工作。</p>
    </header>

    <div class="scene-home__grid" aria-label="工作场景入口">
      <button v-if="!isLeader" class="scene-card" type="button" @click="go('course-development')">
        <span class="scene-card__icon scene-card__icon--purple"><A12AssetIcon name="folder" :size="30" /></span>
        <span class="scene-card__content">
          <strong>课程开发</strong>
          <span>从教学需求和参考资料出发，完成教学意图、课件、教案和互动内容的生成与修改。</span>
        </span>
        <span class="scene-card__action">进入工作区 <span aria-hidden="true">→</span></span>
      </button>

      <button v-if="isLeader" class="scene-card" type="button" @click="go('leader-teaching-tasks')">
        <span class="scene-card__icon scene-card__icon--purple"><A12AssetIcon name="document" :size="30" /></span>
        <span class="scene-card__content">
          <strong>教师互动</strong>
          <span>查看教师的教学任务与课程班级信息，跟进协作中的教学工作。</span>
        </span>
        <span class="scene-card__action">进入工作区 <span aria-hidden="true">→</span></span>
      </button>

      <button class="scene-card" type="button" @click="go('result-collaboration')">
        <span class="scene-card__icon scene-card__icon--orange"><A12AssetIcon name="document" :size="30" /></span>
        <span class="scene-card__content">
          <strong>成果提交与审批</strong>
          <span>{{ resultDescription }}</span>
        </span>
        <span class="scene-card__action">进入工作区 <span aria-hidden="true">→</span></span>
      </button>

      <button v-if="!isLeader" class="scene-card" type="button" @click="go('student-interaction')">
        <span class="scene-card__icon scene-card__icon--green"><A12AssetIcon name="question-help" :size="30" /></span>
        <span class="scene-card__content">
          <strong>学生互动与反馈</strong>
          <span>查看并答复学生针对课程提出的问题。</span>
        </span>
        <span class="scene-card__action">进入工作区 <span aria-hidden="true">→</span></span>
      </button>
    </div>
  </section>
</template>

<script setup lang="ts">
import A12AssetIcon from '@/components/ui/A12AssetIcon.vue';
import { useAuthStore } from '@/stores/auth';
import { computed } from 'vue';
import { useRouter } from 'vue-router';

const auth = useAuthStore();
const router = useRouter();
const isLeader = computed(() => auth.activeRole === 'LEADER');

const greeting = computed(() => {
  const hour = new Date().getHours();
  if (hour < 11) return '上午好';
  if (hour < 14) return '中午好';
  if (hour < 18) return '下午好';
  return '晚上好';
});

const resultDescription = computed(() => auth.activeRole === 'LEADER'
  ? '审核教师提交的教学成果，处理退回修改，并完成成果发布。'
  : '提交教学成果，跟踪审核进度，处理退回修改，并查看成果发布状态。');

function go(name: 'course-development' | 'result-collaboration' | 'student-interaction' | 'leader-teaching-tasks') {
  void router.push({ name });
}
</script>

<style scoped>
.scene-home {
  display: grid;
  align-content: center;
  min-height: calc(100vh - var(--header-height, 76px));
  max-width: 1120px;
  margin: 0 auto;
  padding-block: clamp(56px, 12vh, 132px);
}

.scene-home__intro {
  max-width: 680px;
  margin-bottom: 34px;
}

.scene-home__eyebrow {
  margin: 0 0 10px;
  color: var(--color-primary);
  font-size: 14px;
  font-weight: 700;
}

.scene-home__intro h2 {
  margin: 0;
  color: var(--color-text);
  font-size: clamp(30px, 4vw, 42px);
  line-height: 1.18;
}

.scene-home__intro > p:last-child {
  margin: 14px 0 0;
  color: var(--color-text-secondary);
  font-size: 16px;
}

.scene-home__grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 18px;
}

.scene-card {
  display: grid;
  grid-template-rows: auto 1fr auto;
  min-height: 276px;
  padding: 26px;
  border: 1px solid var(--color-border);
  border-radius: 12px;
  background: var(--color-surface);
  color: inherit;
  text-align: left;
  cursor: pointer;
  box-shadow: var(--shadow-panel);
  transition: border-color 160ms ease, box-shadow 160ms ease, transform 160ms ease;
}

.scene-card:hover {
  border-color: var(--color-primary-border);
  background: var(--color-primary-soft);
  box-shadow: 0 10px 28px rgba(28, 34, 80, 0.1);
  transform: translateY(-1px);
}

.scene-card:focus-visible {
  outline: 3px solid var(--color-primary-border);
  outline-offset: 3px;
}

.scene-card__icon {
  display: grid;
  width: 54px;
  height: 54px;
  place-items: center;
  border-radius: 12px;
}

.scene-card__icon--purple { background: var(--color-primary-soft); color: var(--color-primary); }
.scene-card__icon--orange { background: #fff3e4; color: #d97706; }
.scene-card__icon--green { background: #eaf8ef; color: #169657; }

.scene-card__content {
  display: grid;
  align-content: start;
  gap: 12px;
  padding-top: 24px;
}

.scene-card__content strong {
  color: var(--color-text);
  font-size: 20px;
}

.scene-card__content span {
  color: var(--color-text-secondary);
  font-size: 14px;
  line-height: 1.75;
}

.scene-card__action {
  color: var(--color-primary);
  font-size: 14px;
  font-weight: 700;
}

@media (max-width: 880px) {
  .scene-home__grid { grid-template-columns: 1fr; }
  .scene-card { min-height: 210px; }
}
</style>
