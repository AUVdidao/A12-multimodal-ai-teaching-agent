<template>
  <StatePanel v-if="!slides.length" type="empty" title="PPT 成果没有可预览页面" description="当前成果内容为空。" />
  <div v-else class="ppt-preview">
    <nav class="ppt-preview__thumbs" aria-label="PPT 页面缩略图">
      <button
        v-for="(slide, index) in slides"
        :key="`${slide.order}-${index}`"
        type="button"
        :class="['ppt-thumb', { 'is-active': selectedIndex === index }]"
        :aria-label="`查看第 ${index + 1} 页：${slide.title}`"
        :aria-current="selectedIndex === index ? 'page' : undefined"
        @click="selectedIndex = index"
      >
        <span class="ppt-thumb__number">{{ index + 1 }}</span>
        <span class="ppt-thumb__canvas">
          <strong>{{ slide.title }}</strong>
          <i v-for="bullet in slide.bullets.slice(0, 3)" :key="bullet">{{ bullet }}</i>
        </span>
      </button>
    </nav>

    <section class="ppt-stage" :aria-label="`第 ${selectedIndex + 1} 页 PPT 预览`">
      <div class="ppt-stage__content">
        <span class="ppt-stage__index">{{ String(selectedIndex + 1).padStart(2, '0') }}</span>
        <div>
          <p v-if="selectedSlide.subtitle" class="ppt-stage__subtitle">{{ selectedSlide.subtitle }}</p>
          <h3>{{ selectedSlide.title }}</h3>
          <ul v-if="selectedSlide.bullets.length">
            <li v-for="bullet in selectedSlide.bullets" :key="bullet">{{ bullet }}</li>
          </ul>
        </div>
        <p v-if="selectedSlide.notes" class="ppt-stage__notes">备注：{{ selectedSlide.notes }}</p>
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
import type { Artifact } from '@/api/generation';
import StatePanel from '@/components/StatePanel.vue';
import { normalizePptSlides } from './artifactContent';
import { computed, ref, watch } from 'vue';

const props = defineProps<{ artifact: Artifact }>();
const selectedIndex = ref(0);
const slides = computed(() => normalizePptSlides(props.artifact.content));
const selectedSlide = computed(() => slides.value[Math.min(selectedIndex.value, slides.value.length - 1)] || slides.value[0]);

watch(() => props.artifact.id, () => { selectedIndex.value = 0; });
</script>

<style scoped>
.ppt-preview {
  display: grid;
  grid-template-columns: 172px minmax(0, 1fr);
  min-width: 0;
  gap: 14px;
}

.ppt-preview__thumbs {
  display: flex;
  max-height: min(62vh, 680px);
  min-width: 0;
  flex-direction: column;
  gap: 10px;
  padding-right: 4px;
  overflow: auto;
}

.ppt-thumb {
  display: grid;
  grid-template-columns: 22px minmax(0, 1fr);
  align-items: start;
  gap: 6px;
  width: 100%;
  padding: 4px;
  border: 1px solid transparent;
  border-radius: 6px;
  background: transparent;
  color: var(--ui-text);
  cursor: pointer;
  text-align: left;
}

.ppt-thumb:hover,
.ppt-thumb.is-active {
  border-color: #bcb2ff;
  background: var(--ui-primary-soft);
}

.ppt-thumb__number {
  padding-top: 4px;
  color: var(--ui-muted);
  font-size: 11px;
  text-align: center;
}

.ppt-thumb__canvas {
  display: block;
  width: 100%;
  aspect-ratio: 16 / 9;
  padding: 8px;
  border: 1px solid var(--ui-border);
  border-radius: 4px;
  overflow: hidden;
  background: #fff;
  box-shadow: 0 2px 7px rgba(26, 36, 65, 0.08);
}

.ppt-thumb__canvas strong,
.ppt-thumb__canvas i {
  display: block;
  overflow: hidden;
  overflow-wrap: anywhere;
}

.ppt-thumb__canvas strong {
  max-height: 28px;
  color: #222a42;
  font-size: 9px;
  line-height: 1.4;
}

.ppt-thumb__canvas i {
  max-height: 11px;
  margin-top: 3px;
  color: #7c879c;
  font-size: 7px;
  font-style: normal;
  line-height: 1.4;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.ppt-stage {
  position: relative;
  width: 100%;
  min-width: 0;
  aspect-ratio: 16 / 9;
  border: 1px solid #dce2ed;
  border-radius: 6px;
  overflow: hidden;
  background: #f9fbff;
  box-shadow: 0 10px 28px rgba(30, 44, 75, 0.1);
}

.ppt-stage::before {
  position: absolute;
  top: 0;
  left: 0;
  width: 8px;
  height: 100%;
  background: var(--ui-primary);
  content: '';
}

.ppt-stage__content {
  position: absolute;
  inset: 0;
  display: grid;
  grid-template-rows: auto minmax(0, 1fr) auto;
  gap: 12px;
  padding: clamp(20px, 4.5%, 48px) clamp(24px, 7%, 72px);
  overflow: auto;
}

.ppt-stage__index {
  justify-self: end;
  color: #9589e8;
  font-size: 12px;
  font-weight: 700;
}

.ppt-stage__content > div {
  align-self: center;
  min-width: 0;
}

.ppt-stage h3,
.ppt-stage p,
.ppt-stage ul {
  margin: 0;
}

.ppt-stage h3 {
  max-width: 92%;
  color: #1d2540;
  font-size: clamp(19px, 2.2vw, 32px);
  line-height: 1.25;
  overflow-wrap: anywhere;
}

.ppt-stage__subtitle {
  margin-bottom: 7px !important;
  color: var(--ui-primary);
  font-size: 12px;
  font-weight: 700;
  overflow-wrap: anywhere;
}

.ppt-stage ul {
  display: grid;
  max-width: 92%;
  gap: 7px;
  padding-left: 20px;
  margin-top: 18px;
  color: #4f5b75;
  font-size: clamp(11px, 1.2vw, 16px);
  line-height: 1.55;
}

.ppt-stage li {
  overflow-wrap: anywhere;
}

.ppt-stage__notes {
  padding-top: 8px;
  border-top: 1px solid #e4e8f1;
  color: var(--ui-faint);
  font-size: 10px;
  line-height: 1.4;
  overflow-wrap: anywhere;
}

@media (max-width: 760px) {
  .ppt-preview {
    grid-template-columns: 1fr;
  }

  .ppt-preview__thumbs {
    max-height: none;
    flex-direction: row;
    padding: 0 0 6px;
    overflow-x: auto;
    overflow-y: hidden;
    scroll-snap-type: x proximity;
  }

  .ppt-thumb {
    width: 138px;
    flex: 0 0 138px;
    scroll-snap-align: start;
  }
}

@media (max-width: 480px) {
  .ppt-stage__content {
    gap: 5px;
    padding: 14px 20px;
  }

  .ppt-stage h3 {
    font-size: 17px;
  }

  .ppt-stage ul {
    gap: 3px;
    padding-left: 16px;
    margin-top: 8px;
    font-size: 10px;
    line-height: 1.35;
  }

  .ppt-stage__subtitle,
  .ppt-stage__notes,
  .ppt-stage__index {
    font-size: 9px;
  }
}
</style>
