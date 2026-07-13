<template>
  <article :class="['ui-chat-message', role]">
    <div class="ui-chat-message__avatar">{{ role === 'ai' ? 'AI' : '您' }}</div>
    <div class="ui-chat-message__bubble">
      <p>{{ content }}</p>
    </div>
    <time>{{ time }}</time>
  </article>
</template>

<script setup lang="ts">
defineProps<{
  role: 'ai' | 'teacher';
  content: string;
  time: string;
}>();
</script>

<style scoped>
.ui-chat-message {
  display: grid;
  grid-template-columns: 36px minmax(0, 1fr) 44px;
  align-items: start;
  gap: 10px;
  max-width: 88%;
}

.ui-chat-message.teacher {
  grid-template-columns: 44px minmax(0, 1fr) 36px;
  justify-self: end;
}

.ui-chat-message.teacher .ui-chat-message__avatar {
  grid-column: 3;
  background: #f1edff;
  color: #5b45f6;
}

.ui-chat-message.teacher .ui-chat-message__bubble {
  grid-column: 2;
  grid-row: 1;
  background: #f3f1ff;
}

.ui-chat-message.teacher time {
  grid-column: 1;
  grid-row: 1;
  justify-self: end;
}

.ui-chat-message__avatar {
  display: grid;
  width: 34px;
  height: 34px;
  place-items: center;
  border-radius: 50%;
  background: linear-gradient(135deg, #7c6cff, #4e67ff);
  color: #fff;
  font-size: 12px;
  font-weight: 800;
}

.ui-chat-message__bubble {
  padding: 12px 14px;
  border: 1px solid var(--ui-border);
  border-radius: 12px;
  background: #f8fafd;
}

.ui-chat-message__bubble p {
  margin: 0;
  line-height: 1.65;
}

.ui-chat-message time {
  color: var(--ui-muted);
  font-size: 12px;
  line-height: 34px;
}
</style>
