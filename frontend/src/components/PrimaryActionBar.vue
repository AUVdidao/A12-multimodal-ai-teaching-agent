<template>
  <div class="primary-action-bar">
    <div v-if="$slots.info" class="primary-action-bar__info"><slot name="info" /></div>
    <div class="primary-action-bar__buttons">
      <slot name="secondary" />
      <slot />
    </div>
  </div>
</template>

<style scoped>
.primary-action-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-top: 24px;
  padding-top: 18px;
  border-top: 1px solid var(--color-border);
}

.primary-action-bar__info {
  min-width: 0;
  color: var(--color-text-muted);
  font-size: 12px;
}

.primary-action-bar__buttons {
  display: flex;
  flex: 0 0 auto;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 10px;
}

@media (max-width: 640px) {
  .primary-action-bar {
    align-items: stretch;
    flex-direction: column;
  }

  .primary-action-bar__buttons,
  .primary-action-bar__buttons :deep(.el-button) {
    width: 100%;
  }

  .primary-action-bar__buttons :deep(.el-button + .el-button) {
    margin-left: 0;
  }
}
</style>
