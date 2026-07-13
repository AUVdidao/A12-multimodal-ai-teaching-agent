<template>
  <section class="ui-evidence-list">
    <div class="ui-evidence-list__header">
      <h3>{{ title }}</h3>
      <button v-if="actionLabel" type="button" @click="$emit('action')">{{ actionLabel }}</button>
    </div>
    <article v-for="(item, index) in items" :key="item.title" class="ui-evidence-item">
      <span :class="['ui-evidence-item__index', item.tone || 'purple']">{{ index + 1 }}</span>
      <div>
        <div class="ui-evidence-item__title">
          <strong>{{ item.title }}</strong>
          <span>{{ item.type }}</span>
        </div>
        <p>{{ item.desc }}</p>
      </div>
    </article>
  </section>
</template>

<script setup lang="ts">
defineEmits<{
  action: [];
}>();

defineProps<{
  title: string;
  actionLabel?: string;
  items: Array<{
    title: string;
    type: string;
    desc: string;
    tone?: 'purple' | 'blue' | 'green';
  }>;
}>();
</script>

<style scoped>
.ui-evidence-list {
  display: grid;
  gap: 10px;
}

.ui-evidence-list__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.ui-evidence-list h3 {
  margin: 0;
}

.ui-evidence-list button {
  border: 0;
  background: transparent;
  color: var(--ui-primary);
  cursor: pointer;
  font-weight: 700;
}

.ui-evidence-item {
  display: grid;
  grid-template-columns: 28px minmax(0, 1fr);
  gap: 12px;
  padding: 14px;
  border: 1px solid var(--ui-border);
  border-radius: 12px;
  background: #fff;
}

.ui-evidence-item__index {
  display: grid;
  width: 24px;
  height: 24px;
  place-items: center;
  border-radius: 8px;
  color: #fff;
  font-size: 13px;
  font-weight: 800;
}

.ui-evidence-item__index.purple {
  background: #6b5cff;
}

.ui-evidence-item__index.blue {
  background: #3b82f6;
}

.ui-evidence-item__index.green {
  background: #22c55e;
}

.ui-evidence-item__title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.ui-evidence-item__title span {
  padding: 3px 8px;
  border-radius: 8px;
  background: #f2eeff;
  color: var(--ui-primary);
  font-size: 12px;
  font-weight: 700;
}

.ui-evidence-item p {
  margin: 8px 0 0;
  color: var(--ui-muted);
  line-height: 1.55;
}
</style>
