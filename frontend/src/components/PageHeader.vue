<template>
  <header v-if="description || $slots.meta || $slots.actions" class="page-heading" :aria-label="title">
    <div class="page-heading__content">
      <p v-if="description">{{ description }}</p>
      <div v-if="$slots.meta" class="page-heading__meta"><slot name="meta" /></div>
    </div>
    <div v-if="$slots.actions" class="page-heading__actions"><slot name="actions" /></div>
  </header>
</template>

<script setup lang="ts">
defineProps<{
  title: string;
  description?: string;
  eyebrow?: string;
  projectLabel?: string;
}>();
</script>

<style scoped>
.page-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 24px;
  margin-bottom: 22px;
}

.page-heading__content {
  min-width: 0;
}

p {
  max-width: 760px;
  margin: 0;
  color: var(--color-text-secondary);
  font-size: 14px;
  line-height: 1.7;
}

.page-heading__meta {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 16px;
  margin-top: 12px;
}

.page-heading__actions {
  display: flex;
  flex: 0 0 auto;
  flex-wrap: wrap;
  gap: 10px;
}

@media (max-width: 720px) {
  .page-heading {
    display: grid;
    gap: 16px;
  }

  .page-heading__actions,
  .page-heading__actions :deep(.el-button) {
    width: 100%;
  }
}
</style>
