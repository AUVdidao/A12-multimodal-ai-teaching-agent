<template>
  <section class="form-section">
    <header class="form-section__header">
      <el-icon v-if="icon" class="form-section__icon"><component :is="icon" /></el-icon>
      <div>
        <h2>{{ title }}</h2>
        <p v-if="description">{{ description }}</p>
      </div>
    </header>
    <div class="form-section__body"><slot /></div>
  </section>
</template>

<script setup lang="ts">
import type { Component } from 'vue';

defineProps<{
  title: string;
  description?: string;
  icon?: Component;
}>();
</script>

<style scoped>
.form-section + .form-section {
  margin-top: 26px;
  padding-top: 26px;
  border-top: 1px solid var(--color-border);
}

.form-section__header {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  margin-bottom: 18px;
}

.form-section__icon {
  flex: 0 0 auto;
  width: 32px;
  height: 32px;
  border-radius: var(--radius-md);
  background: var(--color-primary-soft);
  color: var(--color-primary);
  font-size: 17px;
}

h2,
p {
  margin: 0;
}

h2 {
  color: var(--color-text);
  font-size: 16px;
  line-height: 1.4;
}

p {
  margin-top: 4px;
  color: var(--color-text-muted);
  font-size: 12px;
  line-height: 1.6;
}
</style>
