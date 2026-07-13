<template>
  <section class="page">
    <ProjectContextHeader :project="project" />
    <ProjectWorkspaceNav :project-id="project.id" />

    <div class="grid cols-3">
      <UiProgressRingCard :value="project.progress" title="整体进度" note="由当前前端演示状态派生" />
      <section class="panel" style="grid-column: span 2">
        <div class="panel__header">
          <div>
            <h3>下一任务</h3>
            <p>系统根据当前阶段给出唯一推荐操作。</p>
          </div>
          <el-button type="primary" @click="router.push(`/projects/${project.id}/requirements`)">继续编辑</el-button>
        </div>
        <div class="data-row">
          <div>
            <strong>{{ project.nextTask }}</strong>
            <p class="muted">完成需求确认后，可进入资料解析和知识检索。</p>
          </div>
          <UiStatusPill :label="stageLabel(project.status)" tone="orange" />
        </div>
      </section>
    </div>

    <div class="grid cols-4" style="margin-top: 16px">
      <UiMetricCard v-for="item in stageCards" :key="item.title" :label="item.title" :value="item.value" :note="item.note" :tone="item.tone" :fallback-icon="item.icon" />
    </div>

    <div class="grid cols-2" style="margin-top: 16px">
      <section class="panel">
        <div class="panel__header">
          <h3>阶段任务</h3>
          <span class="tag-soft">M1/M2</span>
        </div>
        <div v-for="task in tasks" :key="task.name" class="data-row" style="padding: 13px 0; border-bottom: 1px solid var(--ui-border)">
          <div>
            <strong>{{ task.name }}</strong>
            <p class="muted">{{ task.desc }}</p>
          </div>
          <el-button @click="router.push(task.path)">进入</el-button>
        </div>
      </section>

      <section class="panel">
        <div class="panel__header">
          <h3>快速操作</h3>
          <span class="tag-soft info">前端已串联</span>
        </div>
        <div class="grid cols-2">
          <el-button @click="router.push(`/projects/${project.id}/requirements`)">查看对话记录</el-button>
          <el-button @click="router.push(`/projects/${project.id}/summary`)">需求摘要</el-button>
          <el-button @click="router.push(`/projects/${project.id}/materials`)">管理资料</el-button>
          <el-button @click="router.push(`/projects/${project.id}/intent`)">确认意图</el-button>
        </div>
        <el-alert title="内容生成、版本和导出属于后续 M3/M4，本页只保留入口与禁用说明。" type="info" show-icon :closable="false" style="margin-top: 16px" />
      </section>
    </div>
  </section>
</template>

<script setup lang="ts">
import ProjectContextHeader from '@/components/ProjectContextHeader.vue';
import ProjectWorkspaceNav from '@/components/ProjectWorkspaceNav.vue';
import UiMetricCard from '@/components/ui/UiMetricCard.vue';
import UiProgressRingCard from '@/components/ui/UiProgressRingCard.vue';
import UiStatusPill from '@/components/ui/UiStatusPill.vue';
import { getDemoProject, stageLabel } from '@/mock/demo';
import { computed } from 'vue';
import { useRoute, useRouter } from 'vue-router';

const route = useRoute();
const router = useRouter();
const project = computed(() => getDemoProject(route.params.projectId as string)).value;

const stageCards = [
  { title: '需求状态', value: '7/9', note: '关键字段已收集', tone: 'purple' as const, icon: '✓' },
  { title: '资料文件', value: '6', note: '3 份解析完成', tone: 'green' as const, icon: '▣' },
  { title: '知识片段', value: '3', note: '前端演示检索结果', tone: 'blue' as const, icon: 'K' },
  { title: '意图状态', value: '待确认', note: '需确认后进入生成阶段', tone: 'orange' as const, icon: '!' },
];

const tasks = [
  { name: '教学需求', desc: '录入需求、AI 澄清和完整度检查', path: `/projects/${project.id}/requirements` },
  { name: '参考资料', desc: '上传资料、标记用途并查看解析摘要', path: `/projects/${project.id}/materials` },
  { name: '知识库', desc: '检索知识片段、查看来源和证据', path: `/projects/${project.id}/knowledge` },
  { name: '教学意图', desc: '确认生成目标、内容依据和输出类型', path: `/projects/${project.id}/intent` },
];
</script>
