<template>
  <RouterView v-if="route.meta.public" />
  <MainLayout v-else />
</template>

<script setup lang="ts">
import MainLayout from './layouts/MainLayout.vue';
import { useRoute } from 'vue-router';

const route = useRoute();
</script>
