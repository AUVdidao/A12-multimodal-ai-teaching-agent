<template>
  <el-container class="app-shell">
    <el-aside class="app-shell__aside" width="var(--sidebar-width)">
      <AppSidebar />
    </el-aside>
    <el-container class="app-shell__workspace">
      <el-header class="app-shell__header">
        <AppHeader @toggle-navigation="mobileNavigationOpen = true" />
      </el-header>
      <el-main class="app-shell__main">
        <div class="app-shell__content" :class="{ 'is-project-workspace': projectId }">
          <template v-if="projectId">
            <ProjectContextHeader />
            <ProjectWorkspaceNav :project-id="projectId" />
          </template>
          <router-view v-slot="{ Component, route }">
            <transition name="page" mode="out-in">
              <component :is="Component" :key="route.fullPath" />
            </transition>
          </router-view>
        </div>
      </el-main>
    </el-container>

    <el-drawer
      v-model="mobileNavigationOpen"
      class="mobile-navigation"
      direction="ltr"
      size="min(84vw, 320px)"
      :with-header="false"
    >
      <AppSidebar @navigate="mobileNavigationOpen = false" />
    </el-drawer>
  </el-container>
</template>

<script setup lang="ts">
import AppHeader from '@/components/AppHeader.vue';
import AppSidebar from '@/components/AppSidebar.vue';
import ProjectContextHeader from '@/components/ProjectContextHeader.vue';
import ProjectWorkspaceNav from '@/components/ProjectWorkspaceNav.vue';
import { computed, ref } from 'vue';
import { useRoute } from 'vue-router';

const mobileNavigationOpen = ref(false);
const route = useRoute();
const projectId = computed(() => {
  const value = Number(route.params.projectId);
  return Number.isInteger(value) && value > 0 ? value : null;
});
</script>
