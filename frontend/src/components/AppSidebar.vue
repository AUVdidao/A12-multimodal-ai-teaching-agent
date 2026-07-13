<template>
  <aside class="app-sidebar">
    <div class="brand">
      <UiBrandMark :size="64" />
      <div>
        <strong>A12 教学智能体</strong>
        <span>多模态 AI 互动式教学</span>
      </div>
    </div>

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
        <RouterLink
          v-for="item in group.items"
          :key="item.key"
          class="sidebar-nav__item"
          :class="{ 'is-active': isNavActive(item) }"
          :to="item.to"
          :aria-current="isNavActive(item) ? 'page' : undefined"
          @click="selectNavItem(item)"
        >
          <A12AssetIcon :name="item.icon" :size="21" />
          <span>{{ item.label }}</span>
          <em v-if="item.badge">{{ item.badge }}</em>
        </RouterLink>
      </template>
    </nav>

    <div class="sidebar-status">
      <span>系统状态</span>
      <strong><i /> 服务正常</strong>
    </div>
  </aside>
</template>

<script setup lang="ts">
import A12AssetIcon, { type A12AssetIconName } from '@/components/ui/A12AssetIcon.vue';
import UiBrandMark from '@/components/ui/UiBrandMark.vue';
import { computed, ref } from 'vue';
import { RouterLink, useRoute } from 'vue-router';

interface SidebarNavItem {
  key: string;
  label: string;
  to: string;
  icon: A12AssetIconName;
  badge?: string;
  activeRouteNames?: string[];
}

const workspaceItem: SidebarNavItem = {
  key: 'workspace',
  label: '工作台',
  to: '/',
  icon: 'home',
  activeRouteNames: ['home'],
};

const navGroups: Array<{
  label: string;
  items: SidebarNavItem[];
}> = [
  {
    label: '教学项目',
    items: [
      {
        key: 'projects',
        label: '教学项目',
        to: '/projects',
        icon: 'folder',
        activeRouteNames: ['projects', 'project-create', 'project-mode', 'project-intent', 'project-plan', 'project-preview', 'project-export'],
      },
      {
        key: 'tasks',
        label: '我的任务',
        to: '/projects/1/requirements',
        icon: 'document',
        badge: '12',
        activeRouteNames: ['project-requirements', 'project-summary'],
      },
      { key: 'recent', label: '最近访问', to: '/projects/1', icon: 'clock', activeRouteNames: ['project-overview'] },
      { key: 'recycle-bin', label: '回收站', to: '/projects', icon: 'layers' },
    ],
  },
  {
    label: '资源中心',
    items: [
      {
        key: 'materials',
        label: '资料库',
        to: '/projects/1/materials',
        icon: 'document',
        activeRouteNames: ['project-materials'],
      },
      {
        key: 'knowledge',
        label: '知识库',
        to: '/projects/1/knowledge',
        icon: 'book',
        activeRouteNames: ['project-knowledge'],
      },
      { key: 'templates', label: '模板中心', to: '/projects', icon: 'layers' },
    ],
  },
  {
    label: '智能工具',
    items: [
      { key: 'ai-assistant', label: 'AI 助手', to: '/projects/1/requirements', icon: 'sparkle' },
      { key: 'teaching-analysis', label: '教学分析', to: '/projects/1', icon: 'document' },
      { key: 'learning-insights', label: '学情洞察', to: '/projects/1', icon: 'lightbulb' },
    ],
  },
];

const route = useRoute();
const storageKey = 'a12-sidebar-selected-item';
const allNavItems = [workspaceItem, ...navGroups.flatMap((group) => group.items)];
const selectedKey = ref(typeof window === 'undefined' ? '' : window.sessionStorage.getItem(storageKey) || '');
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
</script>
