<template>
  <StatePanel v-if="!sections.length" type="empty" title="教案成果没有可预览章节" description="当前成果内容为空。" />
  <article v-else class="doc-preview">
    <header class="doc-preview__cover">
      <span>教学设计</span>
      <h3>{{ artifact.title }}</h3>
      <small>共 {{ sections.length }} 个章节</small>
    </header>
    <section v-for="(section, index) in sections" :key="`${section.order}-${index}`" class="doc-section">
      <span class="doc-section__order">{{ String(index + 1).padStart(2, '0') }}</span>
      <div>
        <h4>{{ section.title }}</h4>
        <p v-for="(paragraph, paragraphIndex) in section.paragraphs" :key="`${paragraph}-${paragraphIndex}`">
          {{ paragraph }}
        </p>
        <p v-if="!section.paragraphs.length" class="doc-section__empty">本章节暂无正文内容</p>
      </div>
    </section>
  </article>
</template>

<script setup lang="ts">
import type { Artifact } from '@/api/generation';
import StatePanel from '@/components/StatePanel.vue';
import { normalizeDocSections } from './artifactContent';
import { computed } from 'vue';

const props = defineProps<{ artifact: Artifact }>();
const sections = computed(() => normalizeDocSections(props.artifact.content));
</script>

<style scoped>
.doc-preview {
  width: min(100%, 880px);
  min-width: 0;
  padding: clamp(20px, 4vw, 48px);
  margin: 0 auto;
  border: 1px solid var(--ui-border);
  border-radius: 6px;
  background: #fff;
  box-shadow: 0 8px 24px rgba(31, 46, 79, 0.07);
}

.doc-preview__cover {
  padding-bottom: 24px;
  border-bottom: 2px solid #2f70e8;
}

.doc-preview__cover span,
.doc-preview__cover small {
  color: var(--ui-muted);
  font-size: 12px;
}

.doc-preview__cover h3 {
  margin: 6px 0;
  color: #182235;
  font-size: 24px;
  line-height: 1.35;
  overflow-wrap: anywhere;
}

.doc-section {
  display: grid;
  grid-template-columns: 36px minmax(0, 1fr);
  gap: 14px;
  padding: 24px 0;
  border-bottom: 1px solid var(--ui-border);
}

.doc-section:last-child {
  padding-bottom: 0;
  border-bottom: 0;
}

.doc-section__order {
  display: grid;
  width: 32px;
  height: 32px;
  place-items: center;
  border-radius: 6px;
  background: #edf4ff;
  color: #2f70e8;
  font-size: 11px;
  font-weight: 700;
}

.doc-section > div {
  min-width: 0;
}

.doc-section h4,
.doc-section p {
  margin: 0;
}

.doc-section h4 {
  margin-bottom: 12px;
  color: #202a43;
  font-size: 17px;
  line-height: 1.4;
  overflow-wrap: anywhere;
}

.doc-section p {
  color: #4f5d75;
  font-size: 14px;
  line-height: 1.85;
  overflow-wrap: anywhere;
  white-space: pre-wrap;
}

.doc-section p + p {
  margin-top: 8px;
}

.doc-section p.doc-section__empty {
  color: var(--ui-faint);
  font-style: italic;
}

@media (max-width: 560px) {
  .doc-preview {
    padding: 18px 16px;
  }

  .doc-preview__cover h3 {
    font-size: 20px;
  }

  .doc-section {
    grid-template-columns: 28px minmax(0, 1fr);
    gap: 9px;
    padding: 18px 0;
  }

  .doc-section__order {
    width: 26px;
    height: 26px;
  }
}
</style>
