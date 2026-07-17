<template>
  <div class="app-shell">
    <aside class="app-shell__aside">
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
</script>
