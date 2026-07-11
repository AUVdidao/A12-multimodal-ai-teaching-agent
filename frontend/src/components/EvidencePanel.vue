<template>
  <section class="evidence-panel">
    <header><div><span>增强证据 {{ evidence.length }} 条</span><h2>资料与知识片段引用依据</h2></div><el-tag type="warning" effect="plain">确定性原型</el-tag></header>
    <div class="evidence-list">
      <article v-for="(item, index) in evidence" :key="`${item.knowledgeChunkId}-${index}`">
        <div class="evidence-index">{{ index + 1 }}</div>
        <div><h3>{{ item.sourceFilename }}</h3><p class="reason">{{ item.hitReason }}</p><p>{{ item.contentExcerpt }}</p><div class="tag-row"><el-tag v-for="usage in item.usageTypes" :key="usage" effect="plain">{{ usageLabels[usage] }}</el-tag><span>知识片段 #{{ item.knowledgeChunkId }}</span></div></div>
      </article>
    </div>
  </section>
</template>

<script setup lang="ts">
import type { TeachingIntentEvidence } from '@/api/teachingIntents';
import { usageLabels } from '@/utils/materialLabels';
defineProps<{ evidence: TeachingIntentEvidence[] }>();
</script>

<style scoped>
.evidence-panel { padding: 21px; border: 1px solid var(--color-border); border-radius: var(--radius-lg); background: var(--color-surface); box-shadow: var(--shadow-card); }
header { display: flex; align-items: flex-start; justify-content: space-between; gap: 14px; padding-bottom: 15px; border-bottom: 1px solid var(--color-border); }
header span { color: var(--color-primary); font-size: 10px; font-weight: 800; }
h2, h3, p { margin: 0; }
h2 { margin-top: 4px; font-size: 16px; }
.evidence-list { display: grid; gap: 0; }
article { display: grid; grid-template-columns: 28px minmax(0, 1fr); gap: 11px; padding: 15px 0; border-bottom: 1px solid var(--color-border); }
article:last-child { padding-bottom: 0; border-bottom: 0; }
.evidence-index { display: grid; width: 26px; height: 26px; place-items: center; border-radius: 50%; background: var(--color-primary-soft); color: var(--color-primary); font-size: 11px; font-weight: 800; }
h3 { overflow-wrap: anywhere; font-size: 13px; }
p { margin-top: 6px; color: var(--color-text-secondary); font-size: 11px; line-height: 1.6; }
.reason { color: var(--color-primary); font-weight: 650; }
.tag-row { display: flex; flex-wrap: wrap; align-items: center; gap: 6px; margin-top: 9px; }
.tag-row > span:last-child { color: var(--color-text-muted); font-size: 10px; }
</style>
