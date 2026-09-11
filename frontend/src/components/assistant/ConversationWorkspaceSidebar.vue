<template>
  <aside class="conversation-sidebar" aria-label="会话与最近任务">
    <header class="conversation-sidebar__header">
      <div>
        <span>Conversation Workspace</span>
        <h2>会话 / 最近任务</h2>
      </div>
      <el-button type="primary" :icon="Plus" circle aria-label="新建会话" title="新建会话" @click="$emit('new-session')" />
    </header>

    <section v-if="currentProjectName" class="conversation-sidebar__current">
      <span>当前任务</span>
      <strong>{{ currentProjectName }}</strong>
      <small>{{ currentSessionTitle || '当前会话' }}</small>
    </section>

    <div class="conversation-sidebar__list">
      <button
        v-for="item in sessions"
        :key="item.id"
        type="button"
        :class="['conversation-sidebar__item', { 'is-active': item.id === activeSessionId }]"
        @click="$emit('select-session', item.id)"
      >
        <span class="conversation-sidebar__item-icon"><A12AssetIcon name="document" :size="18" /></span>
        <span class="conversation-sidebar__item-copy">
          <strong>{{ item.title }}</strong>
          <small>{{ formatTime(item.updatedAt) }}</small>
        </span>
        <i :class="{ 'is-active': item.status === 'active' }" />
      </button>
      <div v-if="sessions.length === 0" class="conversation-sidebar__empty">
        还没有本地会话索引。发送第一条消息后会出现在这里。
      </div>
    </div>

    <footer class="conversation-sidebar__note">
      <A12AssetIcon name="info" :size="16" />
      <span>消息仍通过现有 dialogues API 保存；此处只保存最近会话索引。</span>
    </footer>
  </aside>
</template>

<script setup lang="ts">
import A12AssetIcon from '@/components/ui/A12AssetIcon.vue';
import type { ConversationSessionSummary } from '@/stores/conversationWorkspace';
import { Plus } from '@element-plus/icons-vue';

defineProps<{
  sessions: ConversationSessionSummary[];
  activeSessionId?: string;
  currentProjectName?: string;
  currentSessionTitle?: string;
}>();

defineEmits<{
  'new-session': [];
  'select-session': [id: string];
}>();

function formatTime(value: string) {
  const timestamp = Date.parse(value);
  if (!Number.isFinite(timestamp)) return '刚刚';
  const minutes = Math.max(0, Math.floor((Date.now() - timestamp) / 60000));
  if (minutes < 1) return '刚刚';
  if (minutes < 60) return `${minutes} 分钟前`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours} 小时前`;
  return `${Math.floor(hours / 24)} 天前`;
}
</script>

<style scoped>
.conversation-sidebar {
  display: flex;
  min-width: 220px;
  min-height: 0;
  flex-direction: column;
  border: 1px solid var(--ui-border);
  border-radius: 14px;
  background: #fff;
  box-shadow: var(--shadow-panel);
}

.conversation-sidebar__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  padding: 17px 15px;
  border-bottom: 1px solid var(--ui-border);
}

.conversation-sidebar__header span,
.conversation-sidebar__current span {
  color: var(--ui-primary);
  font-size: 11px;
  font-weight: 800;
  letter-spacing: .04em;
  text-transform: uppercase;
}

.conversation-sidebar h2 {
  margin: 5px 0 0;
  color: var(--ui-text);
  font-size: 17px;
}

.conversation-sidebar__current {
  display: grid;
  gap: 5px;
  margin: 12px;
  padding: 12px;
  border-radius: 10px;
  background: var(--ui-primary-soft);
}

.conversation-sidebar__current strong {
  color: var(--ui-text);
  font-size: 13px;
  overflow-wrap: anywhere;
}

.conversation-sidebar__current small,
.conversation-sidebar__item small {
  color: var(--ui-muted);
  font-size: 11px;
}

.conversation-sidebar__list {
  display: grid;
  min-height: 0;
  overflow: auto;
}

.conversation-sidebar__item {
  display: grid;
  grid-template-columns: 30px minmax(0, 1fr) 7px;
  align-items: center;
  gap: 9px;
  min-height: 62px;
  padding: 10px 12px;
  border: 0;
  border-bottom: 1px solid var(--ui-border);
  background: #fff;
  color: inherit;
  cursor: pointer;
  text-align: left;
}

.conversation-sidebar__item:hover,
.conversation-sidebar__item.is-active {
  background: #f7f5ff;
}

.conversation-sidebar__item-icon {
  display: grid;
  width: 30px;
  height: 30px;
  place-items: center;
  border-radius: 9px;
  background: #f0edff;
  color: var(--ui-primary);
}

.conversation-sidebar__item-copy {
  display: grid;
  min-width: 0;
  gap: 5px;
}

.conversation-sidebar__item-copy strong {
  color: var(--ui-text);
  font-size: 12px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.conversation-sidebar__item > i {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #d5dce8;
}

.conversation-sidebar__item > i.is-active {
  background: var(--ui-success);
}

.conversation-sidebar__empty {
  padding: 24px 16px;
  color: var(--ui-muted);
  font-size: 12px;
  line-height: 1.6;
  text-align: center;
}

.conversation-sidebar__note {
  display: flex;
  align-items: flex-start;
  gap: 7px;
  margin-top: auto;
  padding: 12px;
  border-top: 1px solid var(--ui-border);
  color: var(--ui-muted);
  font-size: 11px;
  line-height: 1.5;
}
</style>
