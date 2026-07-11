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
      <div class="app-header__mark" aria-hidden="true">
        <el-icon><Reading /></el-icon>
      </div>
      <div class="app-header__identity">
        <strong>A12 多模态 AI 教学智能体</strong>
        <span>教师备课与需求共创工作台</span>
      </div>
    </div>
    <div class="app-header__status">
      <span class="app-header__status-dot" aria-hidden="true" />
      <span>{{ app.mode }}</span>
    </div>
  </div>
</template>

<script setup lang="ts">
import { Menu, Reading } from '@element-plus/icons-vue';
import { useAppStore } from '@/stores/app';

const emit = defineEmits<{ 'toggle-navigation': [] }>();
const app = useAppStore();
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

.app-header__mark {
  display: grid;
  flex: 0 0 36px;
  width: 36px;
  height: 36px;
  place-items: center;
  border-radius: var(--radius-md);
  background: var(--color-primary);
  color: #ffffff;
  font-size: 20px;
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
  .app-header__mark {
    display: none;
  }

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
