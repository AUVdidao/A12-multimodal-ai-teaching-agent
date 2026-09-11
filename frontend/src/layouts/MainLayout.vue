<template>
  <div
    class="app-shell"
    :class="{
      'app-shell--home': scene === 'HOME',
      'app-shell--single-column': !showSidebar,
      'app-shell--assistant': route.name === 'ai-assistant',
    }"
  >
    <aside v-if="showSidebar" class="app-shell__aside">
      <AppSidebar />
    </aside>

    <section class="app-shell__workspace">
      <header class="app-shell__header">
        <AppHeader />
      </header>
      <main class="app-shell__main">
        <router-view />
      </main>
    </section>
  </div>
</template>

<script setup lang="ts">
import AppHeader from '@/components/AppHeader.vue';
import AppSidebar from '@/components/AppSidebar.vue';
import { computed } from 'vue';
import { useRoute } from 'vue-router';

type WorkspaceScene = 'HOME' | 'COURSE_DEVELOPMENT' | 'RESULT_COLLABORATION' | 'STUDENT_INTERACTION' | 'STUDENT_SPACE';

const route = useRoute();
const scene = computed<WorkspaceScene>(() => {
  if (route.name === 'home') return 'HOME';
  if (route.meta.scene) return route.meta.scene as WorkspaceScene;
  if (String(route.name).startsWith('student-')) return 'STUDENT_SPACE';
  if (['teacher-approvals', 'teacher-publications', 'teacher-teaching-tasks', 'leader-approvals', 'leader-publications'].includes(String(route.name))) {
    return 'RESULT_COLLABORATION';
  }
  if (['teacher-questions', 'leader-questions', 'teaching-analytics', 'student-insights'].includes(String(route.name))) {
    return 'STUDENT_INTERACTION';
  }
  return 'COURSE_DEVELOPMENT';
});

const showSidebar = computed(() => route.name !== 'ai-assistant' && scene.value !== 'HOME' && scene.value !== 'STUDENT_INTERACTION');
</script>

<style scoped>
.app-shell--assistant {
  gap: 0;
  padding: 0;
  background: #090808;
}

.app-shell--assistant .app-shell__workspace {
  border: 0;
  border-radius: 0;
  background: #090808;
}

.app-shell--assistant .app-shell__header {
  height: 40px;
  border-bottom-color: #2a2424;
  background: #1b1416;
}

.app-shell--assistant .app-shell__main {
  padding: 0;
  background: #090808;
}

.app-shell--assistant :deep(.app-header) {
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 14px;
  padding: 0 16px;
}

.app-shell--assistant :deep(.app-header__title h1) {
  display: none;
}

.app-shell--assistant :deep(.app-header__search) {
  display: none;
}

.app-shell--assistant :deep(.app-header__actions) {
  gap: 7px;
}

.app-shell--assistant :deep(.app-header__actions .icon-button),
.app-shell--assistant :deep(.user-chip) {
  background: transparent;
  color: #b7aeaa;
}

.app-shell--assistant :deep(.app-header__actions .icon-button:hover),
.app-shell--assistant :deep(.user-chip:hover) {
  color: #f3eee9;
}

.app-shell--assistant :deep(.user-chip__identity strong) {
  color: #ede7e3;
  font-size: 11px;
}

.app-shell--assistant :deep(.user-chip__identity small) {
  color: #8e8581;
  font-size: 9px;
}

@media (max-width: 720px) {
  .app-shell--assistant :deep(.user-chip__identity) {
    display: none;
  }
}
</style>
