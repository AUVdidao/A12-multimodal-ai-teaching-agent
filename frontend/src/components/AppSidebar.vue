<template>
  <aside class="app-sidebar">
    <RouterLink
      v-if="scene !== 'STUDENT_SPACE'"
      class="sidebar-nav__item sidebar-nav__item--return app-sidebar__home-link"
      :to="{ name: 'home' }"
    >
      <A12AssetIcon name="home" :size="21" />
      <span>返回首页</span>
    </RouterLink>

    <nav class="sidebar-nav" aria-label="当前工作区导航">
      <template v-for="group in navGroups" :key="group.label">
        <div class="sidebar-nav__group">{{ group.label }}</div>
        <RouterLink
          v-for="item in group.items"
          :key="item.key"
          class="sidebar-nav__item"
          :class="{ 'is-active': isNavActive(item) }"
          :to="item.to"
          :aria-current="isNavActive(item) ? 'page' : undefined"
        >
          <A12AssetIcon :name="item.icon" :size="21" />
          <span>{{ item.label }}</span>
        </RouterLink>
      </template>
    </nav>
  </aside>
</template>

<script setup lang="ts">
import A12AssetIcon, { type A12AssetIconName } from '@/components/ui/A12AssetIcon.vue';
import { useAuthStore } from '@/stores/auth';
import { computed } from 'vue';
import { RouterLink, useRoute, type RouteLocationRaw } from 'vue-router';

type WorkspaceScene = 'COURSE_DEVELOPMENT' | 'RESULT_COLLABORATION' | 'STUDENT_INTERACTION' | 'TEACHER_INTERACTION' | 'STUDENT_SPACE';

interface SidebarNavItem {
  key: string;
  label: string;
  to: RouteLocationRaw;
  icon: A12AssetIconName;
  activeRouteNames: string[];
}

const auth = useAuthStore();
const route = useRoute();
const projectRoutes = [
  'projects', 'project-create', 'project-mode', 'project-overview', 'project-requirements',
  'project-summary', 'project-materials', 'project-knowledge', 'project-intent', 'project-plan',
  'project-preview', 'project-export',
];

const scene = computed<WorkspaceScene>(() => {
  if (String(route.name).startsWith('student-')) return 'STUDENT_SPACE';
  if (route.meta.scene) return route.meta.scene as WorkspaceScene;
  if (['teacher-approvals', 'teacher-publications', 'teacher-teaching-tasks', 'leader-approvals', 'leader-publications'].includes(String(route.name))) {
    return 'RESULT_COLLABORATION';
  }
  if (['teacher-questions', 'leader-questions', 'teaching-analytics', 'student-insights'].includes(String(route.name))) {
    return 'STUDENT_INTERACTION';
  }
  return 'COURSE_DEVELOPMENT';
});

const navGroups = computed<Array<{ label: string; items: SidebarNavItem[] }>>(() => {
  if (scene.value === 'STUDENT_SPACE') {
    return [{
      label: '学习空间',
      items: [
        { key: 'student-workspace', label: '学习首页', to: { name: 'student-workspace' }, icon: 'home', activeRouteNames: ['student-workspace'] },
        { key: 'student-learning', label: '学习内容', to: { name: 'student-learning' }, icon: 'book', activeRouteNames: ['student-learning'] },
        { key: 'student-questions', label: '我的问答', to: { name: 'student-questions' }, icon: 'question-help', activeRouteNames: ['student-questions'] },
      ],
    }];
  }

  if (scene.value === 'RESULT_COLLABORATION') {
    if (auth.activeRole === 'LEADER') {
      return [{
        label: '成果提交与审批',
        items: [
          { key: 'result-collaboration', label: '审批概览', to: { name: 'result-collaboration' }, icon: 'document', activeRouteNames: ['result-collaboration'] },
          { key: 'leader-approvals', label: '待审批成果', to: { name: 'leader-approvals' }, icon: 'document', activeRouteNames: ['leader-approvals'] },
          { key: 'leader-publications', label: '成果发布', to: { name: 'leader-publications' }, icon: 'layers', activeRouteNames: ['leader-publications'] },
        ],
      }];
    }
    return [{
      label: '成果提交与审批',
      items: [
        { key: 'result-collaboration', label: '成果概览', to: { name: 'result-collaboration' }, icon: 'document', activeRouteNames: ['result-collaboration'] },
        { key: 'teacher-tasks', label: '待提交成果', to: { name: 'teacher-teaching-tasks' }, icon: 'document', activeRouteNames: ['teacher-teaching-tasks'] },
        { key: 'teacher-approvals', label: '审核进度与退回修改', to: { name: 'teacher-approvals' }, icon: 'clock', activeRouteNames: ['teacher-approvals'] },
        { key: 'teacher-publications', label: '发布记录', to: { name: 'teacher-publications' }, icon: 'layers', activeRouteNames: ['teacher-publications'] },
      ],
    }];
  }

  if (scene.value === 'STUDENT_INTERACTION') {
    return [{
      label: '学生互动与反馈',
      items: [
        { key: 'questions', label: '学生问答', to: { name: auth.activeRole === 'LEADER' ? 'leader-questions' : 'teacher-questions' }, icon: 'question-help', activeRouteNames: ['student-interaction', 'teacher-questions', 'leader-questions'] },
      ],
    }];
  }

  if (scene.value === 'TEACHER_INTERACTION') {
    return [{
      label: '教师互动',
      items: [
        { key: 'leader-teaching-tasks', label: '教学任务', to: { name: 'leader-teaching-tasks' }, icon: 'document', activeRouteNames: ['leader-teaching-tasks'] },
        { key: 'leader-courses', label: '课程与班级', to: { name: 'leader-courses' }, icon: 'book', activeRouteNames: ['leader-courses'] },
      ],
    }];
  }

  return [
    {
      label: '课程开发',
      items: [
        { key: 'course-development', label: '开发概览', to: { name: 'course-development' }, icon: 'home', activeRouteNames: ['course-development'] },
        { key: 'projects', label: '教学项目', to: { name: 'projects' }, icon: 'folder', activeRouteNames: projectRoutes },
        { key: 'recent-projects', label: '最近访问', to: { name: 'recent-projects' }, icon: 'clock', activeRouteNames: ['recent-projects'] },
        { key: 'recycle-bin', label: '回收站', to: { name: 'recycle-bin' }, icon: 'layers', activeRouteNames: ['recycle-bin'] },
      ],
    },
    {
      label: '资源中心',
      items: [
        { key: 'resource-library', label: '资料库', to: { name: 'resource-library' }, icon: 'document', activeRouteNames: ['resource-library'] },
        { key: 'knowledge-library', label: '知识库', to: { name: 'knowledge-library' }, icon: 'book', activeRouteNames: ['knowledge-library'] },
        { key: 'template-center', label: '模板中心', to: { name: 'template-center' }, icon: 'layers', activeRouteNames: ['template-center'] },
      ],
    },
    {
      label: '智能生成',
      items: [
        { key: 'ai-assistant', label: 'AI助手', to: { name: 'ai-assistant' }, icon: 'sparkle', activeRouteNames: ['ai-assistant'] },
      ],
    },
  ];
});

function isNavActive(item: SidebarNavItem) {
  return item.activeRouteNames.includes(String(route.name));
}
</script>

<style scoped>
.app-sidebar__home-link {
  flex: 0 0 auto;
  margin: 14px 0 6px;
}
</style>
