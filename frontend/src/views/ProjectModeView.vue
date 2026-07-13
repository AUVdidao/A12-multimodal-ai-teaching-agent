<template>
  <section class="page">
    <ProjectContextHeader :project="project" />

    <section class="panel">
      <div class="panel__header">
        <div>
          <h3>选择生成模式</h3>
          <p>该设置后续会影响 AI 工作流成本、速度和质量。当前为前端演示选择。</p>
        </div>
      </div>

      <el-radio-group v-model="mode" class="grid cols-3" style="width: 100%">
        <el-radio-button v-for="item in modes" :key="item.code" :label="item.code">
          <strong>{{ item.name }}</strong>
          <span>{{ item.desc }}</span>
        </el-radio-button>
      </el-radio-group>

      <div class="page-actions">
        <el-button type="primary" @click="router.push(`/projects/${project.id}/requirements`)">保存并进入教学需求</el-button>
        <el-button @click="router.push(`/projects/${project.id}`)">返回项目概览</el-button>
      </div>
    </section>
  </section>
</template>

<script setup lang="ts">
import ProjectContextHeader from '@/components/ProjectContextHeader.vue';
import { getDemoProject } from '@/mock/demo';
import { ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';

const route = useRoute();
const router = useRouter();
const project = getDemoProject(route.params.projectId as string);
const mode = ref('STANDARD');
const modes = [
  { code: 'STANDARD', name: '标准模式', desc: '平衡质量、速度与成本，适合常规备课。' },
  { code: 'QUALITY', name: '质量优先', desc: '更重视内容完整度和证据质量。' },
  { code: 'ECONOMY', name: '快速草稿', desc: '快速产出可编辑初稿。' },
];
</script>

<style scoped>
:deep(.el-radio-button__inner) {
  width: 100%;
  min-height: 120px;
  padding: 20px;
  border-radius: 10px !important;
  text-align: left;
  white-space: normal;
}

:deep(.el-radio-button__inner span),
:deep(.el-radio-button__inner strong) {
  display: block;
}

:deep(.el-radio-button__inner span) {
  margin-top: 8px;
  color: var(--ui-muted);
  line-height: 1.55;
}
</style>
