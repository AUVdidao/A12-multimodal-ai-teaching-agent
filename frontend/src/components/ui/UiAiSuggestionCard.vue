<template>
  <article :class="['ui-ai-suggestion-card', tone]">
    <div class="ui-ai-suggestion-card__spark" aria-hidden="true">
      <span />
    </div>
    <div class="ui-ai-suggestion-card__content">
      <small>{{ eyebrow }}</small>
      <strong>{{ title }}</strong>
      <p>{{ description }}</p>
      <el-button v-if="actionLabel" type="primary" size="small" @click="$emit('action')">
        {{ actionLabel }}
      </el-button>
    </div>
    <button v-if="closable" type="button" aria-label="关闭建议" @click="$emit('close')">×</button>
  </article>
</template>

<script setup lang="ts">
defineEmits<{
  action: [];
  close: [];
}>();

withDefaults(
  defineProps<{
    eyebrow?: string;
    title: string;
    description: string;
    actionLabel?: string;
    tone?: 'purple' | 'green';
    closable?: boolean;
  }>(),
  {
    eyebrow: '基于你的项目进度，AI 助手为你推荐',
    actionLabel: '',
    tone: 'purple',
    closable: false,
  },
);
</script>

<style scoped>
.ui-ai-suggestion-card {
  display: grid;
  grid-template-columns: 44px minmax(0, 1fr) auto;
  gap: 14px;
  padding: 18px;
  border: 1px solid #ece8ff;
  border-radius: 12px;
  background: linear-gradient(135deg, #fbf9ff, #f4f1ff);
}

.ui-ai-suggestion-card.green {
  border-color: #d9f2e4;
  background: linear-gradient(135deg, #f7fffa, #eefaf3);
}

.ui-ai-suggestion-card__spark {
  position: relative;
  width: 38px;
  height: 38px;
}

.ui-ai-suggestion-card__spark::before,
.ui-ai-suggestion-card__spark::after,
.ui-ai-suggestion-card__spark span {
  position: absolute;
  background: linear-gradient(135deg, #7c6cff, #4e67ff);
  content: "";
}

.green .ui-ai-suggestion-card__spark::before,
.green .ui-ai-suggestion-card__spark::after,
.green .ui-ai-suggestion-card__spark span {
  background: linear-gradient(135deg, #23b26d, #4f8cff);
}

.ui-ai-suggestion-card__spark::before {
  inset: 8px 13px;
  transform: rotate(45deg);
}

.ui-ai-suggestion-card__spark::after {
  width: 10px;
  height: 10px;
  left: 2px;
  top: 18px;
  transform: rotate(45deg);
}

.ui-ai-suggestion-card__spark span {
  width: 8px;
  height: 8px;
  right: 3px;
  top: 3px;
  transform: rotate(45deg);
}

.ui-ai-suggestion-card small {
  display: block;
  color: var(--ui-muted);
  font-size: 12px;
}

.ui-ai-suggestion-card strong {
  display: block;
  margin-top: 5px;
  font-size: 17px;
}

.ui-ai-suggestion-card p {
  margin: 7px 0 12px;
  color: var(--ui-muted);
  line-height: 1.55;
}

.ui-ai-suggestion-card > button {
  align-self: start;
  border: 0;
  background: transparent;
  color: #8d86be;
  cursor: pointer;
  font-size: 20px;
}
</style>
