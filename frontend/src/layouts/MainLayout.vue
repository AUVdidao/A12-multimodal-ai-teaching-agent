<template>
  <el-container class="app-shell">
    <el-header class="app-shell__header">
      <AppHeader @toggle-navigation="mobileNavigationOpen = true" />
    </el-header>

    <el-container class="app-shell__body">
      <el-aside class="app-shell__aside" width="240px">
        <AppSidebar />
      </el-aside>

      <el-main class="app-shell__main">
        <div class="app-shell__content">
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
import { ref } from 'vue';

const mobileNavigationOpen = ref(false);
</script>
