<template>
  <section class="project-context">
    <button class="project-context__back" type="button" @click="router.push(backTarget)">
      <el-icon><ArrowLeft /></el-icon>
      {{ backLabel }}
    </button>
    <div class="project-context__main">
      <div class="project-context__icon">
        <el-icon><FolderOpened /></el-icon>
      </div>
      <div>
        <div class="project-context__eyebrow">{{ project.courseName }} / {{ project.chapterTitle }}</div>
        <h2>{{ project.projectName }}</h2>
        <p>{{ project.targetStudents || '授课对象待补充' }} · {{ project.lessonDurationLabel || '课时待补充' }} · {{ modeLabel }}</p>
      </div>
    </div>
    <div class="project-context__meta">
      <UiStatusPill :label="project.stageLabel" :tone="stageTone(project.stage)" dot />
      <span>更新于 {{ formatRelativeTime(project.updatedAt) }}</span>
    </div>
  </section>
</template>

<script setup lang="ts">
import type { ProjectBrief } from '@/api/workspace';
import { formatRelativeTime, stageTone } from '@/utils/presentation';
import { ArrowLeft, FolderOpened } from '@element-plus/icons-vue';
import { computed } from 'vue';
import { useRouter } from 'vue-router';
import UiStatusPill from '@/components/ui/UiStatusPill.vue';

const props = withDefaults(defineProps<{
  project: ProjectBrief;
  backTo?: string;
  backLabel?: string;
}>(), {
  backTo: '',
  backLabel: '返回概览',
});

const router = useRouter();
const backTarget = computed(() => props.backTo || `/projects/${props.project.id}`);
const modeLabel = computed(() => {
  const labels: Record<string, string> = {
    STANDARD: '标准模式',
    QUALITY: '高质量模式',
    ECONOMY: '经济模式',
  };
  return labels[props.project.modelMode] || props.project.modelMode || '标准模式';
});
</script>
