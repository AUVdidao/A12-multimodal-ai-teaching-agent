<template>
  <section class="page">
    <ProjectContextHeader :project="project" />
    <ProjectWorkspaceNav :project-id="project.id" />

    <div class="step-strip">
      <span class="is-active">上传资料</span>
      <span>标记用途</span>
      <span>解析摘要</span>
      <span>知识检索</span>
      <span>意图确认</span>
    </div>

    <div class="grid cols-2">
      <section class="grid">
        <section class="panel">
          <h3>上传资料</h3>
          <p>支持文档、图片、音视频等多种格式，单个文件最大 200MB。</p>
          <UiUploadDropzone style="margin-top: 14px" />
        </section>

        <section class="panel">
          <div class="panel__header">
            <h3>资料列表（{{ demoMaterials.length }}）</h3>
            <el-button @click="router.push(`/projects/${project.id}/knowledge`)">进入知识检索</el-button>
          </div>
          <el-table :data="demoMaterials">
            <el-table-column prop="name" label="文件名称" min-width="220" />
            <el-table-column prop="type" label="类型" width="80" />
            <el-table-column prop="size" label="大小" width="100" />
            <el-table-column label="用途" width="110">
              <template #default="{ row }"><span class="tag-soft">{{ row.purpose }}</span></template>
            </el-table-column>
            <el-table-column label="解析状态" width="110">
              <template #default="{ row }"><span class="tag-soft" :class="statusClass(row.status)">{{ row.status }}</span></template>
            </el-table-column>
            <el-table-column prop="uploadedAt" label="上传时间" width="110" />
          </el-table>
        </section>
      </section>

      <aside class="grid">
        <section class="panel">
          <div class="panel__header">
            <h3>用途标签说明</h3>
            <el-button text type="primary">管理标签</el-button>
          </div>
          <div v-for="purpose in purposes" :key="purpose.name" class="data-row" style="padding: 12px 0; border-bottom: 1px solid var(--ui-border)">
            <span class="tag-soft" :class="purpose.type">{{ purpose.name }}</span>
            <p class="muted">{{ purpose.desc }}</p>
          </div>
        </section>

        <section class="panel">
          <div class="panel__header">
            <h3>解析摘要预览</h3>
            <span class="tag-soft success">解析完成</span>
          </div>
          <strong>人工智能基础（第3版）.pdf</strong>
          <p>本书介绍了人工智能的基本概念、发展历程与核心技术，覆盖机器学习、深度学习、自然语言处理和计算机视觉等关键领域。</p>
          <h3 style="margin-top: 18px">知识点提炼</h3>
          <div class="inline-actions" style="flex-wrap: wrap">
            <span v-for="tag in tags" :key="tag" class="tag-soft">{{ tag }}</span>
          </div>
          <div class="page-actions">
            <el-button type="primary" @click="router.push(`/projects/${project.id}/knowledge`)">查看完整解析</el-button>
          </div>
        </section>
      </aside>
    </div>
  </section>
</template>

<script setup lang="ts">
import ProjectContextHeader from '@/components/ProjectContextHeader.vue';
import ProjectWorkspaceNav from '@/components/ProjectWorkspaceNav.vue';
import UiUploadDropzone from '@/components/ui/UiUploadDropzone.vue';
import { demoMaterials, getDemoProject } from '@/mock/demo';
import { useRoute, useRouter } from 'vue-router';

const route = useRoute();
const router = useRouter();
const project = getDemoProject(route.params.projectId as string);
const tags = ['人工智能定义与发展', '机器学习基本方法', '监督学习 vs 无监督学习', '深度学习原理', '典型应用场景'];
const purposes = [
  { name: '教材依据', desc: '作为课程内容的理论依据或知识来源', type: '' },
  { name: '案例素材', desc: '用于案例教学、课堂讨论或情境分析', type: 'warning' },
  { name: '图片素材', desc: '用于图示说明、课件展示或视觉辅助', type: 'success' },
  { name: '知识补充', desc: '扩展知识、背景信息或延伸阅读资料', type: 'info' },
];

function statusClass(status: string) {
  if (status === '解析完成') return 'success';
  if (status === '解析中') return 'info';
  if (status === '解析失败') return 'danger';
  return 'warning';
}
</script>
