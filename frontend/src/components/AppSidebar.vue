<template>
  <aside class="app-sidebar">
    <div class="brand">
      <UiBrandMark :size="64" />
      <div class="brand__copy">
        <strong>A12 教学智能体</strong>
        <span>多模态 AI 互动式教学</span>
      </div>
    </div>

    <nav class="sidebar-mobile-nav" aria-label="移动端主导航">
      <RouterLink
        v-for="item in mobileItems"
        :key="item.key"
        :class="['sidebar-mobile-nav__item', { 'is-active': isNavActive(item) }]"
        :to="item.to"
        :aria-label="item.label"
        :title="item.label"
      >
        <A12AssetIcon :name="item.icon" :size="22" />
      </RouterLink>
      <span class="sidebar-mobile-nav__status" :title="serviceUp ? '服务正常' : '服务离线'">
        <i :class="{ 'is-down': !serviceUp }" />
        <span class="sr-only">{{ serviceUp ? '服务正常' : '服务离线' }}</span>
      </span>
    </nav>

    <nav class="sidebar-nav" aria-label="全局导航">
      <RouterLink
        class="sidebar-nav__item sidebar-nav__item--workspace"
        :class="{ 'is-active': isNavActive(workspaceItem) }"
        :to="workspaceItem.to"
        :aria-current="isNavActive(workspaceItem) ? 'page' : undefined"
      >
        <A12AssetIcon name="home" :size="23" />
        <span>{{ workspaceItem.label }}</span>
      </RouterLink>

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

    <div class="sidebar-status">
      <span>系统状态</span>
      <strong><i :class="{ 'is-down': !serviceUp }" /> {{ serviceUp ? '服务正常' : '服务离线' }}</strong>
    </div>
  </aside>
</template>

<script setup lang="ts">
import { checkBackendHealth } from '@/api/health';
import A12AssetIcon, { type A12AssetIconName } from '@/components/ui/A12AssetIcon.vue';
import UiBrandMark from '@/components/ui/UiBrandMark.vue';
import { useAuthStore } from '@/stores/auth';
import { computed, onMounted, ref } from 'vue';
import { RouterLink, useRoute, type RouteLocationRaw } from 'vue-router';

interface SidebarNavItem {
  key: string;
  label: string;
  to: RouteLocationRaw;
  icon: A12AssetIconName;
  activeRouteNames: string[];
}

const auth = useAuthStore();
const route = useRoute();
const serviceUp = ref(false);

const teacherProjectRoutes = [
  'projects',
  'project-create',
  'project-mode',
  'project-overview',
  'project-requirements',
  'project-summary',
  'project-materials',
  'project-knowledge',
  'project-intent',
  'project-plan',
  'project-preview',
  'project-export',
];

const workspaceItem = computed<SidebarNavItem>(() => {
  if (auth.activeRole === 'LEADER') {
    return {
      key: 'leader-workspace',
      label: '管理工作台',
      to: { name: 'leader-workspace' },
      icon: 'home',
      activeRouteNames: ['leader-workspace'],
    };
  }
  if (auth.activeRole === 'STUDENT') {
    return {
      key: 'student-workspace',
      label: '学习空间',
      to: { name: 'student-workspace' },
      icon: 'home',
      activeRouteNames: ['student-workspace'],
    };
  }
  return {
    key: 'teacher-workspace',
    label: '工作台',
    to: { name: 'home' },
    icon: 'home',
    activeRouteNames: ['home'],
  };
});

const navGroups = computed<Array<{ label: string; items: SidebarNavItem[] }>>(() => {
  if (auth.activeRole === 'LEADER') {
    return [
      {
        label: '教学管理',
        items: [
          {
            key: 'leader-tasks',
            label: '教学任务',
            to: { name: 'leader-teaching-tasks' },
            icon: 'document',
            activeRouteNames: ['leader-teaching-tasks'],
          },
          {
            key: 'leader-courses',
            label: '课程与班级',
            to: { name: 'leader-courses' },
            icon: 'users',
            activeRouteNames: ['leader-courses'],
          },
          {
            key: 'leader-approvals',
            label: '成果审批',
            to: { name: 'leader-approvals' },
            icon: 'document',
            activeRouteNames: ['leader-approvals'],
          },
          {
            key: 'leader-publications',
            label: '成果发布',
            to: { name: 'leader-publications' },
            icon: 'layers',
            activeRouteNames: ['leader-publications'],
          },
          {
            key: 'leader-questions',
            label: '学生问答',
            to: { name: 'leader-questions' },
            icon: 'question-help',
            activeRouteNames: ['leader-questions'],
          },
          {
            key: 'leader-insights',
            label: '学情洞察',
            to: { name: 'student-insights' },
            icon: 'lightbulb',
            activeRouteNames: ['student-insights'],
          },
        ],
      },
    ];
  }
  if (auth.activeRole === 'STUDENT') {
    return [
      {
        label: '学习中心',
        items: [
          {
            key: 'student-learning',
            label: '学习内容',
            to: { name: 'student-learning' },
            icon: 'book',
            activeRouteNames: ['student-learning'],
          },
          {
            key: 'student-questions',
            label: '我的问答',
            to: { name: 'student-questions' },
            icon: 'question-help',
            activeRouteNames: ['student-questions'],
          },
        ],
      },
    ];
  }

  return [
    {
      label: '教学项目',
      items: [
        {
          key: 'projects',
          label: '教学项目',
          to: { name: 'projects' },
          icon: 'folder',
          activeRouteNames: teacherProjectRoutes,
        },
        {
          key: 'teacher-tasks',
          label: '我的任务',
          to: { name: 'teacher-teaching-tasks' },
          icon: 'document',
          activeRouteNames: ['teacher-teaching-tasks'],
        },
        {
          key: 'recent-projects',
          label: '最近访问',
          to: { name: 'recent-projects' },
          icon: 'clock',
          activeRouteNames: ['recent-projects'],
        },
        {
          key: 'recycle-bin',
          label: '回收站',
          to: { name: 'recycle-bin' },
          icon: 'layers',
          activeRouteNames: ['recycle-bin'],
        },
        {
          key: 'teacher-approvals',
          label: '成果审批',
          to: { name: 'teacher-approvals' },
          icon: 'document',
          activeRouteNames: ['teacher-approvals'],
        },
        {
          key: 'teacher-publications',
          label: '发布记录',
          to: { name: 'teacher-publications' },
          icon: 'layers',
          activeRouteNames: ['teacher-publications'],
        },
      ],
    },
    {
      label: '资源中心',
      items: [
        {
          key: 'resource-library',
          label: '资料库',
          to: { name: 'resource-library' },
          icon: 'document',
          activeRouteNames: ['resource-library'],
        },
        {
          key: 'knowledge-library',
          label: '知识库',
          to: { name: 'knowledge-library' },
          icon: 'book',
          activeRouteNames: ['knowledge-library'],
        },
        {
          key: 'template-center',
          label: '模板中心',
          to: { name: 'template-center' },
          icon: 'layers',
          activeRouteNames: ['template-center'],
        },
      ],
    },
    {
      label: '智能工具',
      items: [
        {
          key: 'ai-assistant',
          label: 'AI 助手',
          to: { name: 'ai-assistant' },
          icon: 'sparkle',
          activeRouteNames: ['ai-assistant'],
        },
        {
          key: 'teacher-questions',
          label: '学生问答',
          to: { name: 'teacher-questions' },
          icon: 'question-help',
          activeRouteNames: ['teacher-questions'],
        },
        {
          key: 'teaching-analytics',
          label: '教学分析',
          to: { name: 'teaching-analytics' },
          icon: 'math',
          activeRouteNames: ['teaching-analytics'],
        },
        {
          key: 'student-insights',
          label: '学情洞察',
          to: { name: 'student-insights' },
          icon: 'lightbulb',
          activeRouteNames: ['student-insights'],
        },
      ],
    },
  ];
});

const mobileItems = computed(() => [workspaceItem.value, ...navGroups.value.flatMap((group) => group.items)]);

function isNavActive(item: SidebarNavItem) {
  return item.activeRouteNames.includes(String(route.name));
}

onMounted(async () => {
  try {
    serviceUp.value = (await checkBackendHealth()).data.status === 'UP';
  } catch {
    serviceUp.value = false;
  }
});
</script>
