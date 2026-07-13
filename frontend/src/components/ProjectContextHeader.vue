<template>
  <section class="project-context">
    <button class="project-context__back" type="button" @click="router.push('/projects')">
      <el-icon><ArrowLeft /></el-icon>
      返回项目
    </button>
    <div class="project-context__main">
      <div class="project-context__icon">
        <el-icon><FolderOpened /></el-icon>
      </div>
      <div>
        <div class="project-context__eyebrow">{{ project.courseName }} / {{ project.chapterTitle }}</div>
        <h2>{{ project.projectName }}</h2>
        <p>{{ project.targetStudents }} · {{ project.lessonDuration }} · {{ project.mode }}</p>
      </div>
    </div>
    <div class="project-context__meta">
      <UiStatusPill :label="stageLabel(project.status)" tone="green" dot />
      <span>更新于 {{ project.updatedAt }}</span>
    </div>
  </section>
</template>

<script setup lang="ts">
import { stageLabel, type DemoProject } from '@/mock/demo';
import { ArrowLeft, FolderOpened } from '@element-plus/icons-vue';
import { useRouter } from 'vue-router';
import UiStatusPill from '@/components/ui/UiStatusPill.vue';

defineProps<{
  project: DemoProject;
}>();

const router = useRouter();
</script>
