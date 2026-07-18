<template>
  <article class="knowledge-hit">
    <header>
      <div>
        <span>相关度 {{ relevanceLabel }} · 相关性估计 {{ hit.score.toFixed(1) }}</span>
        <h3>{{ hit.title }}</h3>
      </div>
      <el-tag type="success" effect="plain">当前项目命中</el-tag>
    </header>
    <p class="knowledge-hit__content">{{ hit.content }}</p>
    <div class="knowledge-hit__reason">
      <el-icon><Search /></el-icon>
      <div><span>匹配理由</span><strong>{{ hit.hitReason }}</strong></div>
    </div>
    <footer>
      <el-tooltip :content="hit.sourceFilename" placement="top" :show-after="400">
        <span class="knowledge-hit__source"><el-icon><Document /></el-icon>{{ hit.sourceFilename }}</span>
      </el-tooltip>
      <div class="tag-row">
        <el-tag v-for="usage in hit.usageTypes" :key="usage" effect="plain">{{ usageLabels[usage] }}</el-tag>
        <el-tag v-for="keyword in hit.keywords.slice(0, 4)" :key="keyword" type="info" effect="plain">{{ keyword }}</el-tag>
      </div>
    </footer>
  </article>
</template>

<script setup lang="ts">
import type { KnowledgeHit } from '@/api/knowledge';
import { usageLabels } from '@/utils/materialLabels';
import { Document, Search } from '@element-plus/icons-vue';
import { computed } from 'vue';

const props = defineProps<{ hit: KnowledgeHit }>();
const relevanceLabel = computed(() => {
  if (props.hit.score >= 8) return '高';
  if (props.hit.score >= 4) return '中';
  return '低';
});
</script>

<style scoped>
.knowledge-hit { padding: 18px; border: 1px solid var(--color-border); border-radius: var(--radius-lg); background: var(--color-surface); box-shadow: var(--shadow-card); }
header { display: flex; align-items: flex-start; justify-content: space-between; gap: 14px; }
header span { color: var(--color-primary); font-size: 10px; font-weight: 800; }
h3, p { margin: 0; }
h3 { margin-top: 4px; font-size: 15px; }
.knowledge-hit__content { margin-top: 12px; color: var(--color-text-secondary); font-size: 12px; line-height: 1.7; }
.knowledge-hit__reason { display: flex; align-items: flex-start; gap: 8px; margin-top: 12px; padding: 10px 12px; border-radius: var(--radius-md); background: var(--color-primary-soft); color: var(--color-primary); font-size: 11px; line-height: 1.5; }
.knowledge-hit__reason div { min-width: 0; }
.knowledge-hit__reason span, .knowledge-hit__reason strong { display: block; }
.knowledge-hit__reason span { margin-bottom: 2px; font-size: 10px; font-weight: 700; }
footer { display: flex; align-items: center; justify-content: space-between; gap: 14px; margin-top: 14px; padding-top: 12px; border-top: 1px solid var(--color-border); }
.knowledge-hit__source { display: inline-flex; align-items: center; min-width: 0; max-width: 360px; gap: 6px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--color-text-muted); font-size: 11px; }
.tag-row { display: flex; flex-wrap: wrap; justify-content: flex-end; gap: 5px; }
@media (max-width: 640px) { header, footer { align-items: flex-start; flex-direction: column; } .tag-row { justify-content: flex-start; } }
</style>
