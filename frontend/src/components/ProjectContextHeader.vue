<template>
  <section class="project-context" :class="{ 'is-loading': context.loading }" aria-label="当前项目上下文">
    <el-skeleton v-if="context.loading && !context.project" :rows="1" animated />
    <template v-else-if="context.project">
      <div class="project-context__main">
        <el-button text :icon="ArrowLeft" class="project-context__back" aria-label="返回项目列表" @click="router.push('/projects')" />
        <div>
          <div class="project-context__eyebrow">教学项目</div>
          <div class="project-context__title-row">
            <h1>{{ context.project.projectName }}</h1>
            <StatusBadge :status="context.project.status" />
          </div>
          <p>{{ projectMeta }}</p>
        </div>
      </div>
      <div class="project-context__actions">
        <span>更新于 {{ formatDate(context.project.updatedAt) }}</span>
        <el-button type="primary" @click="goPrimary">{{ primaryLabel }}</el-button>
      </div>
    </template>
    <el-alert v-else-if="context.error" :title="context.error" type="warning" show-icon :closable="false" />
  </section>
</template>

<script setup lang="ts">
import { ArrowLeft } from '@element-plus/icons-vue';
import { computed, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import StatusBadge from './StatusBadge.vue';
import { useProjectContextStore } from '@/stores/projectContext';

const route = useRoute();
const router = useRouter();
const context = useProjectContextStore();
const projectId = computed(() => Number(route.params.projectId));
const projectMeta = computed(() => [context.project?.targetStudents, context.project?.courseName, context.project?.chapterTitle].filter(Boolean).join(' · '));
const primaryLabel = computed(() => '继续项目');

watch(projectId, (value) => {
  if (Number.isInteger(value) && value > 0) context.load(value);
}, { immediate: true });

function goPrimary() {
  if (!projectId.value) return;
  router.push(`/projects/${projectId.value}/overview`);
}

function formatDate(value: string) {
  return new Intl.DateTimeFormat('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }).format(new Date(value));
}
</script>

<style scoped>
.project-context { display: flex; align-items: center; justify-content: space-between; gap: 20px; min-height: 84px; padding: 0 2px 18px; border-bottom: 1px solid var(--color-border); }
.project-context__main { display: flex; min-width: 0; align-items: flex-start; gap: 8px; }
.project-context__back { margin: 17px 0 0 -8px; }
.project-context__eyebrow { color: var(--color-text-muted); font-size: 11px; font-weight: 700; }
.project-context__title-row { display: flex; align-items: center; flex-wrap: wrap; gap: 9px; margin-top: 3px; }
h1, p { margin: 0; }
h1 { font-size: 23px; line-height: 1.25; overflow-wrap: anywhere; }
p { margin-top: 4px; color: var(--color-text-secondary); font-size: 12px; }
.project-context__actions { display: flex; flex: 0 0 auto; align-items: center; gap: 12px; }
.project-context__actions > span { color: var(--color-text-muted); font-size: 11px; }
@media (max-width: 720px) { .project-context { align-items: flex-start; flex-direction: column; gap: 12px; } .project-context__actions { width: 100%; justify-content: space-between; } }
</style>
