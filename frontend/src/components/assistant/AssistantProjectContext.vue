<template>
  <section :class="['assistant-project-context', { 'is-loading': loading, 'is-empty': empty }]">
    <div class="assistant-project-context__main">
      <div class="assistant-project-context__icon">
        <A12AssetIcon :name="empty ? 'plus-circle' : 'folder'" :size="30" />
      </div>
      <div class="assistant-project-context__copy">
        <span>{{ empty ? '当前没有可分析项目' : '当前项目' }}</span>
        <h2>{{ empty ? '创建项目并填写基本信息后，AI 将自动读取项目上下文' : projectName }}</h2>
        <p v-if="!empty && !loading">
          <strong>课程：</strong>{{ courseName || '未提供' }}
          <strong>章节：</strong>{{ chapterTitle || '未提供' }}
          <strong>授课对象：</strong>{{ targetStudents || '未提供' }}
          <strong>课时：</strong>{{ lessonDuration || '未提供' }}
          <strong>当前阶段：</strong><em>{{ stageLabel }}</em>
        </p>
        <p v-else-if="loading" class="assistant-project-context__sync">
          <span class="assistant-project-context__spinner" />
          正在同步项目上下文
        </p>
      </div>
    </div>
    <div class="assistant-project-context__actions">
      <el-button v-if="empty" type="primary" :icon="CirclePlus" @click="$emit('create-project')">创建教学项目</el-button>
      <el-button v-if="empty" plain :icon="Document" @click="$emit('view-projects')">查看项目列表</el-button>
      <template v-else>
        <el-select
          :model-value="selectedProjectId"
          class="assistant-project-context__select"
          filterable
          :disabled="loading"
          placeholder="切换项目"
          @change="$emit('select-project', Number($event))"
        >
          <el-option v-for="project in projects" :key="project.id" :label="project.projectName" :value="project.id" />
        </el-select>
        <el-button plain :disabled="loading" :icon="Switch" @click="$emit('open-switch')">切换项目</el-button>
        <el-button type="primary" :disabled="loading" :icon="View" @click="$emit('overview')">查看项目概览</el-button>
      </template>
    </div>
  </section>
</template>

<script setup lang="ts">
import type { TeachingProject } from '@/api/projects';
import A12AssetIcon from '@/components/ui/A12AssetIcon.vue';
import { CirclePlus, Document, Switch, View } from '@element-plus/icons-vue';

defineEmits<{
  'create-project': [];
  'view-projects': [];
  'select-project': [projectId: number];
  'open-switch': [];
  overview: [];
}>();

defineProps<{
  empty?: boolean;
  loading?: boolean;
  projects: TeachingProject[];
  selectedProjectId?: number;
  projectName?: string;
  courseName?: string;
  chapterTitle?: string;
  targetStudents?: string;
  lessonDuration?: string;
  stageLabel?: string;
}>();
</script>

<style scoped>
.assistant-project-context {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: center;
  gap: 18px;
  min-height: 88px;
  padding: 16px 20px;
  border: 1px solid var(--ui-border);
  border-radius: 12px;
  background: #fff;
  box-shadow: var(--shadow-panel);
}

.assistant-project-context__main {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 14px;
}

.assistant-project-context__icon {
  display: grid;
  width: 52px;
  height: 52px;
  flex: 0 0 auto;
  place-items: center;
  border-radius: 50%;
  background: var(--ui-primary-soft);
}

.assistant-project-context__copy {
  min-width: 0;
}

.assistant-project-context__copy > span {
  color: var(--ui-text);
  font-size: 13px;
  font-weight: 800;
}

.assistant-project-context__copy h2 {
  margin: 5px 0 0;
  color: var(--ui-primary);
  font-size: 18px;
  line-height: 1.35;
  overflow-wrap: anywhere;
}

.assistant-project-context.is-empty .assistant-project-context__copy h2 {
  color: var(--ui-muted);
  font-size: 14px;
  font-weight: 600;
}

.assistant-project-context__copy p {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 28px;
  margin: 10px 0 0;
  color: #47556c;
  font-size: 13px;
  line-height: 1.45;
}

.assistant-project-context__copy strong {
  margin-right: 2px;
  color: var(--ui-muted);
  font-weight: 700;
}

.assistant-project-context__copy em {
  color: var(--ui-warning);
  font-style: normal;
  font-weight: 800;
}

.assistant-project-context__actions {
  display: flex;
  flex: 0 0 auto;
  align-items: center;
  gap: 10px;
}

.assistant-project-context__select {
  width: 190px;
}

.assistant-project-context__sync {
  align-items: center;
  color: var(--ui-muted) !important;
}

.assistant-project-context__spinner {
  width: 15px;
  height: 15px;
  border: 2px solid #cdd6f5;
  border-top-color: var(--ui-primary);
  border-radius: 50%;
  animation: assistant-spin 1s linear infinite;
}

@keyframes assistant-spin {
  to {
    transform: rotate(360deg);
  }
}

@media (max-width: 1180px) {
  .assistant-project-context {
    grid-template-columns: 1fr;
  }

  .assistant-project-context__actions {
    justify-content: flex-end;
  }
}

@media (max-width: 720px) {
  .assistant-project-context__actions,
  .assistant-project-context__actions :deep(.el-button),
  .assistant-project-context__select {
    width: 100%;
  }

  .assistant-project-context__actions {
    flex-wrap: wrap;
  }
}
</style>
