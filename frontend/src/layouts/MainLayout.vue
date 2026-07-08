<template>
  <el-container class="app-shell">
    <el-header class="app-shell__header">
      <AppHeader />
    </el-header>

    <el-container class="app-shell__body">
      <el-aside class="app-shell__aside" width="232px">
        <AppSidebar />
      </el-aside>

      <el-main class="app-shell__main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup lang="ts">
import AppHeader from '@/components/AppHeader.vue';
import AppSidebar from '@/components/AppSidebar.vue';
</script>
