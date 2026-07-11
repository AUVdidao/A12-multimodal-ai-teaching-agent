<template>
  <header class="page-heading">
    <div class="page-heading__content">
      <div v-if="eyebrow || projectLabel" class="page-heading__eyebrow">
        <span v-if="eyebrow">{{ eyebrow }}</span>
        <span v-if="projectLabel" class="page-heading__project">{{ projectLabel }}</span>
      </div>
      <h1>{{ title }}</h1>
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

.page-heading__eyebrow {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-bottom: 7px;
  color: var(--color-primary);
  font-size: 12px;
  font-weight: 700;
}

.page-heading__project {
  padding-left: 8px;
  border-left: 1px solid var(--color-border-strong);
  color: var(--color-text-muted);
  font-weight: 600;
}

h1 {
  margin: 0;
  color: var(--color-text);
  font-size: 27px;
  font-weight: 750;
  line-height: 1.25;
  letter-spacing: 0;
}

p {
  max-width: 760px;
  margin: 9px 0 0;
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

  h1 {
    font-size: 23px;
  }

  .page-heading__actions,
  .page-heading__actions :deep(.el-button) {
    width: 100%;
  }
}
</style>
