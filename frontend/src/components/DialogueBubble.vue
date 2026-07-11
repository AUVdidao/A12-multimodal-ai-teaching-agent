<template>
  <article :class="['dialogue-bubble', `dialogue-bubble--${role}`]">
    <div class="dialogue-bubble__avatar" aria-hidden="true">
      <el-icon><component :is="roleIcon" /></el-icon>
    </div>
    <div class="dialogue-bubble__body">
      <div class="dialogue-bubble__meta">
        <strong>{{ roleLabel }}</strong>
        <span v-if="roundNo">第 {{ roundNo }} 轮</span>
        <time v-if="createdAt" :datetime="createdAt">{{ formattedTime }}</time>
      </div>
      <p>{{ content }}</p>
    </div>
  </article>
</template>

<script setup lang="ts">
import { Cpu, Setting, User } from '@element-plus/icons-vue';
import { computed } from 'vue';

const props = defineProps<{
  sender: string;
  content: string;
  roundNo?: number;
  createdAt?: string;
}>();

const role = computed(() => {
  if (props.sender === 'AI' || props.sender === 'ASSISTANT') return 'ai';
  if (props.sender === 'SYSTEM') return 'system';
  return 'teacher';
});
const roleLabel = computed(() => ({ ai: 'AI 助教', teacher: '教师', system: '系统' }[role.value]));
const roleIcon = computed(() => ({ ai: Cpu, teacher: User, system: Setting }[role.value]));
const formattedTime = computed(() => {
  if (!props.createdAt) return '';
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(props.createdAt));
});
</script>

<style scoped>
.dialogue-bubble {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  width: min(88%, 720px);
}

.dialogue-bubble--teacher {
  justify-self: end;
  flex-direction: row-reverse;
}

.dialogue-bubble--system {
  justify-self: center;
}

.dialogue-bubble__avatar {
  display: grid;
  flex: 0 0 32px;
  width: 32px;
  height: 32px;
  place-items: center;
  border: 1px solid #d4d0fa;
  border-radius: 50%;
  background: var(--color-ai-soft);
  color: var(--color-ai);
}

.dialogue-bubble--teacher .dialogue-bubble__avatar {
  border-color: #bce8db;
  background: var(--color-success-soft);
  color: var(--color-success);
}

.dialogue-bubble__body {
  min-width: 0;
  padding: 12px 14px;
  border: 1px solid #d4d0fa;
  border-radius: 4px 8px 8px 8px;
  background: #faf9ff;
}

.dialogue-bubble--teacher .dialogue-bubble__body {
  border-color: #bce8db;
  border-radius: 8px 4px 8px 8px;
  background: #f3fbf8;
}

.dialogue-bubble--system .dialogue-bubble__body {
  border-color: var(--color-border);
  background: var(--color-surface-subtle);
}

.dialogue-bubble__meta {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 10px;
  color: var(--color-text-muted);
  font-size: 11px;
}

.dialogue-bubble__meta strong {
  color: var(--color-text);
  font-size: 12px;
}

p {
  margin: 7px 0 0;
  color: var(--color-text);
  font-size: 13px;
  line-height: 1.65;
  overflow-wrap: anywhere;
  white-space: pre-wrap;
}

@media (max-width: 640px) {
  .dialogue-bubble {
    width: 100%;
  }
}
</style>
