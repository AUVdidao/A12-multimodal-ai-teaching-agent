<template>
  <section
    :class="[
      'intent-form-section',
      { 'is-start': align === 'start', 'is-stacked': layout === 'stacked' },
    ]"
  >
    <div class="intent-form-section__meta">
      <span :class="['intent-form-section__icon', `is-${tone}`]">
        <A12AssetIcon :name="icon" :size="28" />
      </span>
      <div>
        <h3>{{ title }}</h3>
        <p>{{ description }}</p>
      </div>
    </div>
    <div class="intent-form-section__content">
      <slot />
    </div>
  </section>
</template>

<script setup lang="ts">
import A12AssetIcon, { type A12AssetIconName } from '@/components/ui/A12AssetIcon.vue';

withDefaults(
  defineProps<{
    title: string;
    description: string;
    icon: A12AssetIconName;
    tone?: 'purple' | 'blue' | 'green' | 'orange' | 'gray';
    align?: 'center' | 'start';
    layout?: 'standard' | 'stacked';
  }>(),
  {
    tone: 'purple',
    align: 'center',
    layout: 'standard',
  },
);
</script>

<style scoped>
.intent-form-section {
  display: grid;
  grid-template-columns: 256px minmax(0, 1fr);
  align-items: center;
  height: 100%;
  padding: 12px 14px;
  border: 1px solid #e6eaf2;
  border-radius: 12px;
  background: #fff;
}

.intent-form-section__meta {
  display: grid;
  grid-template-columns: 42px minmax(0, 1fr);
  align-items: start;
  gap: 10px;
  min-width: 0;
}

.intent-form-section__icon {
  display: grid;
  width: 42px;
  height: 42px;
  place-items: center;
  border-radius: 10px;
  background: #f2efff;
  overflow: hidden;
}

.intent-form-section__icon.is-blue {
  background: #edf5ff;
}

.intent-form-section__icon.is-green {
  background: #edf9f1;
}

.intent-form-section__icon.is-orange {
  background: #fff5e8;
}

.intent-form-section__icon.is-gray {
  background: #f3f5f8;
}

.intent-form-section h3 {
  margin: 1px 0 0;
  color: #171b2c;
  font-size: 15px;
  font-weight: 700;
}

.intent-form-section p {
  max-width: 178px;
  margin: 5px 0 0;
  color: #8a94aa;
  font-size: 11px;
  line-height: 1.45;
}

.intent-form-section__content {
  min-width: 0;
}

.intent-form-section.is-start {
  align-items: start;
  padding-top: 18px;
}

.intent-form-section.is-stacked {
  grid-template-columns: 42px minmax(0, 1fr);
  grid-template-rows: 16px 54px;
  align-items: start;
  gap: 3px 12px;
  padding: 12px 14px;
}

.intent-form-section.is-stacked .intent-form-section__meta {
  display: contents;
}

.intent-form-section.is-stacked .intent-form-section__icon {
  grid-row: 1 / 3;
  grid-column: 1;
}

.intent-form-section.is-stacked .intent-form-section__meta > div {
  grid-row: 1;
  grid-column: 2;
}

.intent-form-section.is-stacked .intent-form-section__meta p {
  display: none;
}

.intent-form-section.is-stacked .intent-form-section__content {
  grid-row: 2;
  grid-column: 2;
  margin-right: 15px;
}

@media (max-width: 1280px) {
  .intent-form-section {
    grid-template-columns: 210px minmax(0, 1fr);
  }
}
</style>
