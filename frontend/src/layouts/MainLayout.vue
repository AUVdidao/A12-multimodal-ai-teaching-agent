<template>
  <div class="app-shell">
    <aside class="app-shell__aside">
      <AppSidebar />
    </aside>

    <section class="app-shell__workspace">
      <header class="app-shell__header">
        <AppHeader />
      </header>
      <main :class="['app-shell__main', { 'app-shell__main--intent': isIntentPage }]">
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

const route = useRoute();
const isIntentPage = computed(() => route.name === 'project-intent');
</script>
