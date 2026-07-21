<template>
  <aside class="assistant-side-panel">
    <section class="assistant-side-card">
      <h2>{{ studentMode ? '学生问题概览' : '项目进度' }}</h2>
      <div v-if="loading" class="assistant-side-skeleton">
        <i v-for="item in 4" :key="item" />
      </div>
      <div v-else class="assistant-progress-list">
        <button
          v-for="item in progressItems"
          :key="item.id"
          type="button"
          :class="['assistant-progress-item', item.tone]"
          :disabled="!item.route"
          @click="item.route && $emit('navigate', item.route)"
        >
          <span><A12AssetIcon :name="iconFor(item.id)" :size="24" /></span>
          <strong>{{ item.label }}</strong>
          <em>{{ item.value }}</em>
        </button>
      </div>
    </section>

    <section class="assistant-side-card">
      <h2>AI 已读取</h2>
      <div v-if="loading" class="assistant-source-list">
        <span v-for="item in 4" :key="item" class="is-loading"><i />正在读取</span>
      </div>
      <div v-else class="assistant-source-list">
        <span v-for="item in sources" :key="item.id" :class="`is-${item.state}`">
          <i />
          {{ item.label }}
          <small v-if="item.detail">{{ item.detail }}</small>
        </span>
      </div>
    </section>

    <section class="assistant-side-card">
      <h2>最近工作</h2>
      <div v-if="loading" class="assistant-recent-skeleton">
        <i v-for="item in 3" :key="item" />
      </div>
      <div v-else-if="recentWork.length === 0" class="assistant-side-empty">暂无最近工作</div>
      <div v-else class="assistant-recent-list">
        <button v-for="item in recentWork" :key="item.id" type="button" :disabled="!item.route" @click="item.route && $emit('navigate', item.route)">
          <span><A12AssetIcon :name="item.icon || 'document'" :size="22" /></span>
          <strong>{{ item.title }}</strong>
          <small>{{ item.time }}</small>
          <em>继续处理</em>
        </button>
      </div>
    </section>

    <section class="assistant-service-card">
      <span :class="{ 'is-error': serviceState === 'error' }"><i /> {{ serviceLabel }}</span>
      <span :class="{ 'is-loading': syncing }"><i /> {{ syncing ? '项目数据同步中' : projectSynced ? '项目数据已同步' : '项目数据待同步' }}</span>
      <button type="button" @click="$emit('show-service-detail')">查看系统详情</button>
    </section>
  </aside>
</template>

<script setup lang="ts">
import type { RouteLocationRaw } from 'vue-router';
import type { AssistantProgressItem, AssistantRecentWorkItem, AssistantSourceStatus } from '@/types/assistant';
import A12AssetIcon, { type A12AssetIconName } from '@/components/ui/A12AssetIcon.vue';

defineEmits<{
  navigate: [route: RouteLocationRaw];
  'show-service-detail': [];
}>();

defineProps<{
  progressItems: AssistantProgressItem[];
  sources: AssistantSourceStatus[];
  recentWork: AssistantRecentWorkItem[];
  loading?: boolean;
  syncing?: boolean;
  projectSynced?: boolean;
  studentMode?: boolean;
  serviceState?: 'ok' | 'error' | 'unknown';
  serviceLabel: string;
}>();

function iconFor(id: string): A12AssetIconName {
  if (id.includes('material')) return 'document';
  if (id.includes('intent')) return 'target';
  if (id.includes('artifact') || id.includes('generation')) return 'layers';
  if (id.includes('question')) return 'question-help';
  return 'clock';
}
</script>

<style scoped>
.assistant-side-panel {
  display: grid;
  align-content: start;
  gap: 14px;
  min-width: 0;
}

.assistant-side-card,
.assistant-service-card {
  border: 1px solid var(--ui-border);
  border-radius: 12px;
  background: #fff;
  box-shadow: var(--shadow-panel);
}

.assistant-side-card {
  padding: 18px;
}

.assistant-side-card h2 {
  margin: 0 0 15px;
  color: #101827;
  font-size: 19px;
  line-height: 1.3;
}

.assistant-progress-list,
.assistant-source-list,
.assistant-recent-list {
  display: grid;
  gap: 0;
}

.assistant-progress-item,
.assistant-recent-list button {
  display: grid;
  align-items: center;
  width: 100%;
  min-width: 0;
  border: 0;
  border-bottom: 1px solid var(--ui-border);
  background: transparent;
  color: inherit;
  cursor: pointer;
  text-align: left;
}

.assistant-progress-item {
  grid-template-columns: 32px minmax(0, 1fr) auto;
  gap: 10px;
  min-height: 47px;
}

.assistant-progress-item:last-child,
.assistant-recent-list button:last-child {
  border-bottom: 0;
}

.assistant-progress-item:disabled,
.assistant-recent-list button:disabled {
  cursor: default;
}

.assistant-progress-item span {
  display: grid;
  place-items: center;
}

.assistant-progress-item strong,
.assistant-recent-list strong {
  min-width: 0;
  color: #26344d;
  font-size: 13px;
  overflow-wrap: anywhere;
}

.assistant-progress-item em {
  color: var(--ui-muted);
  font-style: normal;
  font-size: 12px;
  font-weight: 700;
  white-space: nowrap;
}

.assistant-progress-item.orange em {
  color: var(--ui-warning);
}

.assistant-progress-item.green em {
  color: var(--ui-success);
}

.assistant-progress-item.purple em {
  color: var(--ui-primary);
}

.assistant-source-list {
  gap: 10px;
}

.assistant-source-list span {
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: 24px;
  color: #44536a;
  font-size: 13px;
}

.assistant-source-list i {
  width: 16px;
  height: 16px;
  flex: 0 0 auto;
  border: 1.5px solid #c6d0df;
  border-radius: 50%;
}

.assistant-source-list small {
  margin-left: auto;
  color: var(--ui-faint);
  font-size: 11px;
}

.assistant-source-list .is-loaded i {
  border-color: var(--ui-success);
  background: radial-gradient(circle at center, var(--ui-success) 0 42%, transparent 46%);
}

.assistant-source-list .is-empty {
  color: var(--ui-faint);
}

.assistant-source-list .is-loading i {
  border-color: #cbd6e6;
  border-top-color: var(--ui-info);
  animation: assistant-side-spin 1s linear infinite;
}

.assistant-source-list .is-error {
  color: var(--ui-danger);
}

.assistant-source-list .is-error i {
  border-color: var(--ui-danger);
}

.assistant-recent-list button {
  grid-template-columns: 30px minmax(0, 1fr) auto auto;
  gap: 9px;
  min-height: 49px;
}

.assistant-recent-list small {
  color: var(--ui-muted);
  font-size: 12px;
  white-space: nowrap;
}

.assistant-recent-list em {
  color: var(--ui-primary);
  font-style: normal;
  font-size: 12px;
  font-weight: 700;
  white-space: nowrap;
}

.assistant-side-empty {
  min-height: 72px;
  display: grid;
  place-items: center;
  color: var(--ui-muted);
  font-size: 13px;
}

.assistant-side-skeleton,
.assistant-recent-skeleton {
  display: grid;
  gap: 14px;
}

.assistant-side-skeleton i,
.assistant-recent-skeleton i {
  display: block;
  height: 30px;
  border-radius: 8px;
  background: linear-gradient(90deg, #eff3f8, #f8fafd, #eff3f8);
}

.assistant-service-card {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px 18px;
  padding: 13px 16px;
  color: var(--ui-muted);
  font-size: 12px;
}

.assistant-service-card span {
  display: inline-flex;
  align-items: center;
  gap: 7px;
}

.assistant-service-card span i {
  width: 9px;
  height: 9px;
  border-radius: 50%;
  background: var(--ui-success);
}

.assistant-service-card span.is-loading i {
  border: 2px solid #cbd6e6;
  border-top-color: var(--ui-info);
  background: transparent;
  animation: assistant-side-spin 1s linear infinite;
}

.assistant-service-card span.is-error i {
  background: var(--ui-danger);
}

.assistant-service-card button {
  border: 0;
  background: transparent;
  color: var(--ui-primary);
  cursor: pointer;
  font-size: 12px;
  font-weight: 700;
}

@keyframes assistant-side-spin {
  to {
    transform: rotate(360deg);
  }
}
</style>
