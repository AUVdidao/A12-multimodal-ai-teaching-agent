<template>
  <aside class="app-sidebar">
    <div class="brand">
      <UiBrandMark :size="64" />
      <div>
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
        <component
          v-for="item in group.items"
          :key="item.key"
          :is="item.future ? 'span' : RouterLink"
          class="sidebar-nav__item"
          :class="{ 'is-active': isNavActive(item), 'is-disabled': item.future }"
          :to="item.future ? undefined : item.to"
          :aria-current="isNavActive(item) ? 'page' : undefined"
          :aria-disabled="item.future || undefined"
          :title="item.future ? '将在后续阶段开放' : undefined"
        >
          <A12AssetIcon :name="item.icon" :size="21" />
          <span>{{ item.label }}</span>
        </component>
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
import { RouterLink, useRoute } from 'vue-router';

interface SidebarNavItem {
  key: string;
  label: string;
  to: string;
  icon: A12AssetIconName;
  future?: boolean;
  activeRouteNames?: string[];
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
  if (auth.activeRole === 'LEADER') return { key: 'leader-workspace', label: '管理工作台', to: '/leader', icon: 'home' };
  if (auth.activeRole === 'STUDENT') return { key: 'student-workspace', label: '学习空间', to: '/student', icon: 'home' };
  return { key: 'teacher-workspace', label: '工作台', to: '/', icon: 'home', activeRouteNames: ['home'] };
});

const navGroups = computed<Array<{ label: string; items: SidebarNavItem[] }>>(() => {
  if (auth.activeRole === 'LEADER') {
    return [
      {
        label: '教学管理',
        items: [
          { key: 'leader-tasks', label: '教学任务', to: '/leader', icon: 'document', future: true },
          { key: 'leader-approvals', label: '成果审核', to: '/leader', icon: 'check-circle', future: true },
          { key: 'leader-publications', label: '班级发布', to: '/leader', icon: 'users', future: true },
        ],
      },
    ];
  }
  if (auth.activeRole === 'STUDENT') {
    return [
      {
        label: '我的学习',
        items: [
          { key: 'student-courses', label: '已发布课程', to: '/student', icon: 'book', future: true },
          { key: 'student-questions', label: '我的提问', to: '/student', icon: 'question-help', future: true },
        ],
      },
    ];
  }
  return [
    {
      label: '教学项目',
      items: [
        { key: 'projects', label: '教学项目', to: '/projects', icon: 'folder', activeRouteNames: teacherProjectRoutes },
        { key: 'tasks', label: '我的任务', to: '/projects', icon: 'document', future: true },
        { key: 'recent', label: '最近访问', to: '/projects', icon: 'clock', activeRouteNames: ['project-overview'] },
        { key: 'recycle-bin', label: '回收站', to: '/projects', icon: 'layers', future: true },
      ],
    },
    {
      label: '资源中心',
      items: [
        { key: 'materials', label: '资料库', to: '/projects', icon: 'document', activeRouteNames: ['project-materials'] },
        { key: 'knowledge', label: '知识库', to: '/projects', icon: 'book', activeRouteNames: ['project-knowledge'] },
        { key: 'templates', label: '模板中心', to: '/projects', icon: 'layers', future: true },
      ],
    },
    {
      label: '智能工具',
      items: [
        { key: 'ai-assistant', label: 'AI 助手', to: '/projects', icon: 'sparkle', future: true },
        { key: 'teaching-analysis', label: '教学分析', to: '/projects', icon: 'document', future: true },
        { key: 'learning-insights', label: '学情洞察', to: '/projects', icon: 'lightbulb', future: true },
      ],
    },
  ];
});

const mobileItems = computed(() => [workspaceItem.value, ...navGroups.value.flatMap((group) => group.items).filter((item) => !item.future).slice(0, 2)]);

function isNavActive(item: SidebarNavItem) {
  return route.path === item.to || item.activeRouteNames?.includes(String(route.name)) || false;
}

onMounted(async () => {
  try {
    serviceUp.value = (await checkBackendHealth()).data.status === 'UP';
  } catch {
    serviceUp.value = false;
  }
});
</script>
