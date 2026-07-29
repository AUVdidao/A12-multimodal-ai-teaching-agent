<template>
  <div
    class="app-shell"
    :class="{
      'app-shell--home': scene === 'HOME',
      'app-shell--single-column': !showSidebar,
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

const showSidebar = computed(() => scene.value !== 'HOME' && scene.value !== 'STUDENT_INTERACTION');
</script>
