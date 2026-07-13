<template>
  <section class="intent-evidence-panel">
    <header>
      <div class="intent-evidence-panel__title">
        <h2>依据证据（{{ items.length }}）</h2>
        <A12AssetIcon name="info" :size="17" />
      </div>
      <button type="button" @click="$emit('expand')">全部展开 <span>›</span></button>
    </header>

    <div class="intent-evidence-panel__scroll">
      <article v-for="(item, index) in items" :key="item.title" class="intent-evidence-item">
        <div class="intent-evidence-item__heading">
          <span :class="['intent-evidence-item__index', `is-${item.tone}`]">{{ index + 1 }}</span>
          <strong>{{ item.title }}</strong>
          <span :class="['intent-evidence-item__type', `is-${item.tone}`]">{{ item.type }}</span>
        </div>
        <p><b>来源：</b>{{ item.source }}</p>
        <p><b>匹配理由：</b>{{ item.reason }}</p>
        <div class="intent-evidence-item__fragment">
          <p><b>匹配片段：</b>{{ item.fragment }}</p>
          <button type="button">查看更多 <span>→</span></button>
        </div>
      </article>
    </div>

    <footer>
      <span>找不到合适依据？</span>
      <button type="button" @click="$emit('search')">
        去资料库搜索
        <A12AssetIcon name="search" :size="17" />
      </button>
    </footer>
  </section>
</template>

<script setup lang="ts">
import A12AssetIcon from '@/components/ui/A12AssetIcon.vue';

defineEmits<{
  expand: [];
  search: [];
}>();

defineProps<{
  items: Array<{
    title: string;
    type: string;
    source: string;
    reason: string;
    fragment: string;
    tone: 'purple' | 'blue' | 'green';
  }>;
}>();
</script>

<style scoped>
.intent-evidence-panel {
  display: grid;
  grid-template-rows: 50px minmax(0, 1fr) 45px;
  height: 100%;
  border: 1px solid #e5e9f1;
  border-radius: 12px;
  background: #fff;
  overflow: hidden;
}

.intent-evidence-panel > header,
.intent-evidence-panel > footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 0 16px;
}

.intent-evidence-panel > header {
  border-bottom: 1px solid #edf0f5;
}

.intent-evidence-panel__title {
  display: flex;
  align-items: center;
  gap: 6px;
}

.intent-evidence-panel h2 {
  margin: 0;
  color: #171b2c;
  font-size: 16px;
}

.intent-evidence-panel button {
  border: 0;
  background: transparent;
  color: #6b5af6;
  cursor: pointer;
  font-size: 12px;
}

.intent-evidence-panel__scroll {
  min-height: 0;
  margin: 0 10px 0 16px;
  border: 1px solid #e8ebf2;
  border-radius: 8px;
  overflow-y: auto;
  scrollbar-color: #b8c1d2 transparent;
  scrollbar-width: thin;
}

.intent-evidence-panel__scroll::-webkit-scrollbar {
  width: 5px;
}

.intent-evidence-panel__scroll::-webkit-scrollbar-thumb {
  border-radius: 999px;
  background: #b8c1d2;
}

.intent-evidence-item {
  min-height: 139px;
  padding: 10px 11px 8px;
  border-bottom: 1px solid #e8ebf2;
}

.intent-evidence-item:last-child {
  border-bottom: 0;
}

.intent-evidence-item__heading {
  display: grid;
  grid-template-columns: 22px auto auto;
  justify-content: start;
  align-items: center;
  gap: 8px;
  margin-bottom: 7px;
}

.intent-evidence-item__heading strong {
  max-width: 310px;
  overflow: hidden;
  color: #20263a;
  font-size: 12.5px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.intent-evidence-item__index {
  display: grid;
  width: 21px;
  height: 21px;
  place-items: center;
  border-radius: 4px;
  background: #6555f6;
  color: #fff;
  font-size: 11px;
  font-weight: 800;
}

.intent-evidence-item__index.is-blue {
  background: #3c82f6;
}

.intent-evidence-item__index.is-green {
  background: #24ad63;
}

.intent-evidence-item__type {
  padding: 3px 7px;
  border-radius: 5px;
  background: #f0edff;
  color: #6453ee;
  font-size: 10px;
  white-space: nowrap;
}

.intent-evidence-item__type.is-blue {
  background: #edf4ff;
  color: #3476e8;
}

.intent-evidence-item__type.is-green {
  background: #eaf8ef;
  color: #239d5c;
}

.intent-evidence-item p {
  margin: 4px 0 0 30px;
  color: #6d7892;
  font-size: 11px;
  line-height: 1.45;
}

.intent-evidence-item b {
  color: #343b50;
  font-weight: 700;
}

.intent-evidence-item__fragment {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 8px;
}

.intent-evidence-item__fragment p {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.intent-evidence-item__fragment button {
  flex: 0 0 auto;
  padding: 2px 0;
  font-weight: 700;
}

.intent-evidence-panel > footer {
  border-top: 1px solid #edf0f5;
  color: #8993a9;
  font-size: 11px;
}

.intent-evidence-panel > footer button {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-weight: 700;
}
</style>
