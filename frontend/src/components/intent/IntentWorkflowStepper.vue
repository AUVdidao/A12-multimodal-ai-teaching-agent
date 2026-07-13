<template>
  <nav class="intent-stepper" aria-label="教学方案生成流程">
    <div
      v-for="(step, index) in steps"
      :key="step.label"
      :class="['intent-stepper__item', { 'is-complete': index === 0, 'is-active': index === 1 }]"
    >
      <span class="intent-stepper__circle">{{ index === 0 ? '✓' : index + 1 }}</span>
      <span>{{ step.label }}</span>
    </div>
  </nav>
</template>

<script setup lang="ts">
const steps = [
  { label: 'M1 项目导入' },
  { label: '教学意图确认' },
  { label: '教学内容生成' },
  { label: '教学方案生成' },
  { label: '教学资源生成' },
  { label: '方案预览与发布' },
];
</script>

<style scoped>
.intent-stepper {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 48px;
  padding: 0 14px;
  color: #737e98;
  font-size: 13px;
}

.intent-stepper__item {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  min-width: 0;
  height: 36px;
  white-space: nowrap;
}

.intent-stepper__item::after {
  position: absolute;
  top: 50%;
  right: -82px;
  width: 92px;
  height: 1px;
  background: #e2e7f0;
  content: "";
}

.intent-stepper__item:last-child::after {
  display: none;
}

.intent-stepper__circle {
  position: relative;
  z-index: 1;
  display: grid;
  width: 26px;
  height: 26px;
  flex: 0 0 26px;
  place-items: center;
  border: 1px solid #aab4c8;
  border-radius: 50%;
  background: #fff;
  color: #7e89a1;
  font-size: 12px;
  font-weight: 700;
}

.intent-stepper__item.is-complete {
  color: #5d4ff3;
}

.intent-stepper__item.is-complete .intent-stepper__circle {
  border-color: #6757f6;
  color: #6757f6;
  font-size: 15px;
}

.intent-stepper__item.is-active {
  padding: 0 14px;
  border-radius: 18px;
  background: #f3f0ff;
  color: #5b45f6;
  font-weight: 700;
}

.intent-stepper__item.is-active .intent-stepper__circle {
  border-color: transparent;
  background: linear-gradient(145deg, #7665ff, #503af0);
  box-shadow: 0 4px 10px rgba(91, 69, 246, 0.25);
  color: #fff;
}

@media (max-width: 1280px) {
  .intent-stepper {
    font-size: 12px;
  }

  .intent-stepper__item {
    gap: 5px;
  }

  .intent-stepper__item::after {
    right: -56px;
    width: 62px;
  }
}
</style>
