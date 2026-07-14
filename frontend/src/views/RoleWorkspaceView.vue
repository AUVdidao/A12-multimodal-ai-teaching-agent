<template>
  <section class="role-workspace">
    <div class="role-workspace__intro">
      <span>{{ roleLabel }}</span>
      <h2>{{ auth.user?.displayName }}，欢迎进入{{ roleLabel }}</h2>
      <p>{{ roleDescription }}</p>
    </div>
    <div class="role-workspace__grid">
      <article v-for="item in workspaceItems" :key="item.title">
        <strong>{{ item.value }}</strong>
        <span>{{ item.title }}</span>
        <p>{{ item.description }}</p>
      </article>
    </div>
  </section>
</template>

<script setup lang="ts">
import { useAuthStore } from '@/stores/auth';
import { computed } from 'vue';

const auth = useAuthStore();
const roleLabel = computed(() => auth.activeRole === 'LEADER' ? '教研管理工作台' : '学生学习空间');
const roleDescription = computed(() => auth.activeRole === 'LEADER'
  ? '教学任务、成果审核与班级发布将在同一工作流中完成。'
  : '已发布教学内容与课程问答将在这里集中呈现。');
const workspaceItems = computed(() => auth.activeRole === 'LEADER'
  ? [
      { title: '待审核成果', value: '0', description: '固定版本进入审核后将显示在此处。' },
      { title: '进行中任务', value: '0', description: '已下发的教学任务将在此处跟踪。' },
      { title: '已发布班级', value: '0', description: '通过审核并发布的课程将在此处汇总。' },
    ]
  : [
      { title: '我的课程', value: '0', description: '班级发布后可在此阅读教学内容。' },
      { title: '待解决问题', value: '0', description: '向教师提出的问题将在此持续跟踪。' },
      { title: '已完成学习', value: '0', description: '完成阅读的课程将在此归档。' },
    ]);
</script>
