<template>
  <button
    :class="['intent-check-tag', { 'is-selected': selected }]"
    type="button"
    :aria-pressed="selected"
    @click="$emit('toggle')"
  >
    <span class="intent-check-tag__box">{{ selected ? '✓' : '' }}</span>
    <span>{{ label }}</span>
  </button>
</template>

<script setup lang="ts">
defineEmits<{
  toggle: [];
}>();

defineProps<{
  label: string;
  selected: boolean;
}>();
</script>

<style scoped>
.intent-check-tag {
  display: inline-flex;
  align-items: center;
  gap: 9px;
  width: 122px;
  height: 34px;
  padding: 0 10px;
  border: 1px solid #dfe4ed;
  border-radius: 7px;
  background: #fff;
  color: #364057;
  cursor: pointer;
  font-size: 12px;
  text-align: left;
  transition: border-color 120ms ease, background 120ms ease;
}

.intent-check-tag:hover {
  border-color: #a99dfd;
}

.intent-check-tag__box {
  display: grid;
  width: 16px;
  height: 16px;
  flex: 0 0 16px;
  place-items: center;
  border: 1px solid #cbd3e2;
  border-radius: 4px;
  background: #fff;
  color: #fff;
  font-size: 11px;
  font-weight: 800;
}

.intent-check-tag.is-selected {
  border-color: #ddd7ff;
}

.intent-check-tag.is-selected .intent-check-tag__box {
  border-color: #6555f6;
  background: linear-gradient(145deg, #7564ff, #523cf1);
  box-shadow: 0 2px 5px rgba(91, 69, 246, 0.2);
}
</style>
