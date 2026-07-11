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
      <div class="app-header__identity">
        <strong>{{ pageTitle }}</strong>
        <span>{{ pageHint }}</span>
      </div>
    </div>
    <div class="app-header__status">
      <span class="app-header__status-dot" aria-hidden="true" />
      <span>{{ app.systemStatus }}</span>
    </div>
  </div>
</template>

<script setup lang="ts">
import { Menu } from '@element-plus/icons-vue';
import { useAppStore } from '@/stores/app';
import { computed } from 'vue';
import { useRoute } from 'vue-router';

const emit = defineEmits<{ 'toggle-navigation': [] }>();
const app = useAppStore();
const route = useRoute();
const pageTitle = computed(() => typeof route.meta.title === 'string' ? route.meta.title : '教师工作台');
const pageHint = computed(() => route.path.startsWith('/projects/') ? '项目工作区' : '教师备课工作台');
</script>

<style scoped>
.app-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 100%;
  gap: 20px;
}

.app-header__brand {
  display: flex;
  align-items: center;
  min-width: 0;
  gap: 11px;
}

.app-header__identity {
  min-width: 0;
}

.app-header__identity strong,
.app-header__identity span {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.app-header__identity strong {
  color: var(--color-text);
  font-size: 16px;
}

.app-header__identity span {
  margin-top: 2px;
  color: var(--color-text-muted);
  font-size: 11px;
}

.app-header__status {
  display: inline-flex;
  flex: 0 0 auto;
  align-items: center;
  gap: 7px;
  min-height: 30px;
  padding: 4px 10px;
  border: 1px solid #bce8db;
  border-radius: 999px;
  background: var(--color-success-soft);
  color: var(--color-success);
  font-size: 12px;
  font-weight: 700;
}

.app-header__status-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: currentColor;
}

.app-header__menu {
  display: none;
}

@media (max-width: 1023px) {
  .app-header__menu {
    display: inline-flex;
    margin-right: -4px;
  }
}

@media (max-width: 600px) {
  .app-header__identity strong {
    max-width: 210px;
    font-size: 14px;
  }

  .app-header__identity span,
  .app-header__status span:last-child {
    display: none;
  }

  .app-header__status {
    width: 28px;
    height: 28px;
    min-height: 28px;
    justify-content: center;
    padding: 0;
  }
}
</style>
