<template>
  <article class="ui-progress-ring-card">
    <svg viewBox="0 0 100 100" aria-hidden="true">
      <circle cx="50" cy="50" r="38" class="track" />
      <circle
        cx="50"
        cy="50"
        r="38"
        class="value"
        :style="{ strokeDashoffset: dashOffset }"
      />
      <text x="50" y="55" text-anchor="middle">{{ value }}%</text>
    </svg>
    <div>
      <strong>{{ title }}</strong>
      <p>{{ note }}</p>
    </div>
  </article>
</template>

<script setup lang="ts">
import { computed } from 'vue';

const props = defineProps<{
  value: number;
  title: string;
  note: string;
}>();

const circumference = 2 * Math.PI * 38;
const dashOffset = computed(() => `${circumference * (1 - props.value / 100)}`);
</script>

<style scoped>
.ui-progress-ring-card {
  display: flex;
  align-items: center;
  gap: 18px;
  padding: 18px;
  border: 1px solid var(--ui-border);
  border-radius: 12px;
  background: #fff;
  box-shadow: var(--shadow-panel);
}

.ui-progress-ring-card svg {
  width: 104px;
  height: 104px;
}

.track,
.value {
  fill: none;
  stroke-width: 10;
}

.track {
  stroke: #eef1f6;
}

.value {
  stroke: #4f7dff;
  stroke-linecap: round;
  stroke-dasharray: 238.761;
  transform: rotate(-90deg);
  transform-origin: 50% 50%;
}

text {
  fill: var(--ui-text);
  font-size: 20px;
  font-weight: 800;
}

strong {
  display: block;
  font-size: 18px;
}

p {
  margin: 8px 0 0;
  color: var(--ui-muted);
}
</style>
