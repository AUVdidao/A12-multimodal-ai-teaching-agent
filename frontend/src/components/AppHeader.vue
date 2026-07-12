<template>
  <div class="app-header">
    <div class="app-header__brand">
      <el-button
        class="app-header__menu"
        :icon="Menu"
        text
        aria-label="打开导航"
        title="打开导航"
        @click="emit('toggle-navigation')"
      />
      <strong class="app-header__title">{{ pageTitle }}</strong>
    </div>
    <el-button v-if="showCreateProject" class="app-header__create" type="primary" @click="router.push('/projects/new')">新建教学项目</el-button>
  </div>
</template>

<script setup lang="ts">
import { Menu } from '@element-plus/icons-vue';
import { computed } from 'vue';
import { useRoute, useRouter } from 'vue-router';

const emit = defineEmits<{ 'toggle-navigation': [] }>();
const route = useRoute();
const router = useRouter();
const pageTitle = computed(() => typeof route.meta.title === 'string' ? route.meta.title : '教师工作台');
const showCreateProject = computed(() => route.path === '/' || route.path === '/projects');
</script>

<style scoped>
.app-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 100%;
  gap: 12px;
}

.app-header__brand {
  display: flex;
  align-items: center;
  min-width: 0;
  gap: 11px;
}

.app-header__title {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--color-text);
  font-size: 18px;
  font-weight: 750;
}

.app-header__menu {
  display: none;
}
.app-header__create { margin-left: 2px; }

@media (max-width: 1023px) {
  .app-header__menu {
    display: inline-flex;
    margin-right: -4px;
  }
}

@media (max-width: 600px) {
  .app-header__title {
    max-width: 210px;
    font-size: 14px;
  }

  .app-header__create {
    display: none;
  }
}
</style>
