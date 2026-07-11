<template>
  <article class="knowledge-hit">
    <header><div><span>匹配分数 {{ hit.score.toFixed(1) }}</span><h3>{{ hit.title }}</h3></div><el-tag type="success" effect="plain">本地原型命中</el-tag></header>
    <p class="knowledge-hit__content">{{ hit.content }}</p>
    <div class="knowledge-hit__reason"><el-icon><Search /></el-icon><strong>{{ hit.hitReason }}</strong></div>
    <footer><span><el-icon><Document /></el-icon>{{ hit.sourceFilename }}</span><div class="tag-row"><el-tag v-for="usage in hit.usageTypes" :key="usage" effect="plain">{{ usageLabels[usage] }}</el-tag><el-tag v-for="keyword in hit.keywords.slice(0, 4)" :key="keyword" type="info" effect="plain">{{ keyword }}</el-tag></div></footer>
  </article>
</template>

<script setup lang="ts">
import type { KnowledgeHit } from '@/api/knowledge';
import { usageLabels } from '@/utils/materialLabels';
import { Document, Search } from '@element-plus/icons-vue';
defineProps<{ hit: KnowledgeHit }>();
</script>

<style scoped>
.knowledge-hit { padding: 18px; border: 1px solid var(--color-border); border-radius: var(--radius-lg); background: var(--color-surface); box-shadow: var(--shadow-card); }
header { display: flex; align-items: flex-start; justify-content: space-between; gap: 14px; }
header span { color: var(--color-primary); font-size: 10px; font-weight: 800; }
h3, p { margin: 0; }
h3 { margin-top: 4px; font-size: 15px; }
.knowledge-hit__content { margin-top: 12px; color: var(--color-text-secondary); font-size: 12px; line-height: 1.7; }
.knowledge-hit__reason { display: flex; align-items: flex-start; gap: 8px; margin-top: 12px; padding: 10px 12px; border-radius: var(--radius-md); background: var(--color-primary-soft); color: var(--color-primary); font-size: 11px; line-height: 1.5; }
footer { display: flex; align-items: center; justify-content: space-between; gap: 14px; margin-top: 14px; padding-top: 12px; border-top: 1px solid var(--color-border); }
footer > span { display: inline-flex; align-items: center; min-width: 0; gap: 6px; overflow-wrap: anywhere; color: var(--color-text-muted); font-size: 11px; }
.tag-row { display: flex; flex-wrap: wrap; justify-content: flex-end; gap: 5px; }
@media (max-width: 640px) { header, footer { align-items: flex-start; flex-direction: column; } .tag-row { justify-content: flex-start; } }
</style>
