<template>
  <div class="app-header">
    <div class="app-header__title">
      <h1>{{ title }}</h1>
    </div>

    <div class="app-header__search" role="search">
      <A12AssetIcon name="search" :size="23" />
      <input aria-label="搜索项目、资料、知识" placeholder="搜索项目、资料、知识..." />
      <kbd>⌘K</kbd>
    </div>

    <div class="app-header__actions">
      <button class="icon-button notification-button" type="button" aria-label="通知">
        <A12AssetIcon name="bell" :size="29" />
        <span>8</span>
      </button>
      <button class="icon-button" type="button" aria-label="帮助">
        <A12AssetIcon name="question-help" :size="29" />
      </button>
      <button class="user-chip" type="button" aria-label="打开用户菜单">
        <span>张</span>
        <strong>张老师</strong>
        <i aria-hidden="true" />
      </button>
    </div>
  </div>
</template>

<script setup lang="ts">
import A12AssetIcon from '@/components/ui/A12AssetIcon.vue';
import { computed } from 'vue';
import { useRoute } from 'vue-router';

const route = useRoute();
const title = computed(() => route.meta.title || '教师工作台');
</script>
