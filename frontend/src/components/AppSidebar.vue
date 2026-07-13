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
        :class="['sidebar-mobile-nav__item', { 'is-active': isNavActive(workspaceItem) }]"
        :to="workspaceItem.to"
        aria-label="工作台"
        title="工作台"
        @click="selectNavItem(workspaceItem)"
      >
        <A12AssetIcon name="home" :size="22" />
      </RouterLink>
      <RouterLink
        :class="['sidebar-mobile-nav__item', { 'is-active': isNavActive(projectsItem) }]"
        :to="projectsItem.to"
        aria-label="教学项目"
        title="教学项目"
        @click="selectNavItem(projectsItem)"
      >
        <A12AssetIcon name="folder" :size="22" />
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
        @click="selectNavItem(workspaceItem)"
      >
        <A12AssetIcon name="home" :size="23" />
        <span>工作台</span>
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
          :title="item.future ? '后续阶段开放' : undefined"
          @click="!item.future && selectNavItem(item)"
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
import A12AssetIcon, { type A12AssetIconName } from '@/components/ui/A12AssetIcon.vue';
import { checkBackendHealth } from '@/api/health';
import UiBrandMark from '@/components/ui/UiBrandMark.vue';
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

const workspaceItem: SidebarNavItem = {
  key: 'workspace',
  label: '工作台',
  to: '/',
  icon: 'home',
  activeRouteNames: ['home'],
};

const projectsItem: SidebarNavItem = {
  key: 'projects',
  label: '教学项目',
  to: '/projects',
  icon: 'folder',
  activeRouteNames: [
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
  ],
};

const navGroups: Array<{
  label: string;
  items: SidebarNavItem[];
}> = [
  {
    label: '教学项目',
    items: [
      projectsItem,
      {
        key: 'tasks',
        label: '我的任务',
        to: '/projects',
        icon: 'document',
        future: true,
        activeRouteNames: ['project-requirements', 'project-summary'],
      },
      { key: 'recent', label: '最近访问', to: '/projects', icon: 'clock', activeRouteNames: ['project-overview'] },
      { key: 'recycle-bin', label: '回收站', to: '/projects', icon: 'layers', future: true },
    ],
  },
  {
    label: '资源中心',
    items: [
      {
        key: 'materials',
        label: '资料库',
        to: '/projects',
        icon: 'document',
        activeRouteNames: ['project-materials'],
      },
      {
        key: 'knowledge',
        label: '知识库',
        to: '/projects',
        icon: 'book',
        activeRouteNames: ['project-knowledge'],
      },
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

const route = useRoute();
const storageKey = 'a12-sidebar-selected-item';
const allNavItems = [workspaceItem, ...navGroups.flatMap((group) => group.items)];
const selectedKey = ref(typeof window === 'undefined' ? '' : window.sessionStorage.getItem(storageKey) || '');
const serviceUp = ref(false);
const selectedItem = computed(() => allNavItems.find((item) => item.key === selectedKey.value));

function selectNavItem(item: SidebarNavItem) {
  selectedKey.value = item.key;
  window.sessionStorage.setItem(storageKey, item.key);
}

function isNavActive(item: SidebarNavItem) {
  if (selectedItem.value && route.path === selectedItem.value.to) {
    return selectedItem.value.key === item.key;
  }
  return item.activeRouteNames?.includes(String(route.name)) || false;
}

onMounted(async () => {
  try {
    const result = await checkBackendHealth();
    serviceUp.value = result.data.status === 'UP';
  } catch {
    serviceUp.value = false;
  }
});
</script>
