<template>
  <section class="page">
    <ProjectContextHeader :project="project" />
    <ProjectWorkspaceNav :project-id="project.id" />

    <div class="grid cols-2">
      <section class="panel">
        <div class="panel__header">
          <div>
            <h3>需求摘要确认</h3>
            <p>请检查系统理解的最终教学需求，确认后进入资料与知识增强阶段。</p>
          </div>
          <UiStatusPill label="待确认" tone="orange" />
        </div>

        <article v-for="group in groups" :key="group.title" class="summary-block">
          <div>
            <strong>{{ group.title }}</strong>
            <p>{{ group.desc }}</p>
          </div>
          <el-input v-model="group.content" type="textarea" :rows="group.rows" />
        </article>
      </section>

      <aside class="grid">
        <section class="panel">
          <h3>确认状态</h3>
          <el-alert title="待确认" description="确认需求摘要后，将进入参考资料上传、知识检索和教学意图确认。" type="warning" show-icon :closable="false" />
          <div class="page-actions">
            <el-button>保存修改</el-button>
            <el-button type="primary" @click="router.push(`/projects/${project.id}/materials`)">确认教学需求</el-button>
          </div>
          <p class="muted">确认后再次修改需要重新确认，避免后续内容生成使用过期需求。</p>
        </section>

        <section class="panel">
          <h3>需求来源</h3>
          <div class="data-row"><span>来源文档</span><strong>教学需求 v1.0</strong></div>
          <div class="data-row"><span>生成模式</span><strong>标准模式</strong></div>
          <div class="data-row"><span>更新时间</span><strong>2025-05-27 14:32</strong></div>
        </section>

        <section class="panel">
          <h3>下一阶段预告</h3>
          <p>后续将围绕已确认需求上传资料、解析摘要、检索知识片段并确认教学意图。</p>
          <span class="tag-soft success">M2 工作区</span>
        </section>
      </aside>
    </div>
  </section>
</template>

<script setup lang="ts">
import ProjectContextHeader from '@/components/ProjectContextHeader.vue';
import ProjectWorkspaceNav from '@/components/ProjectWorkspaceNav.vue';
import UiStatusPill from '@/components/ui/UiStatusPill.vue';
import { getDemoProject } from '@/mock/demo';
import { reactive } from 'vue';
import { useRoute, useRouter } from 'vue-router';

const route = useRoute();
const router = useRouter();
const project = getDemoProject(route.params.projectId as string);
const groups = reactive([
  { title: '课程基础信息', desc: '课程基本信息与面向对象', rows: 4, content: '课程名称：人工智能基础概念与应用\n授课对象：大学本科一年级\n学时安排：2 课时（90 分钟）' },
  { title: '教学目标', desc: '知识、能力与素养目标', rows: 4, content: '理解人工智能的基本概念、发展历程与核心技术\n掌握机器学习、深度学习的基本原理与典型应用\n培养学生负责任的 AI 使用意识' },
  { title: '内容组织', desc: '课程内容结构与重点安排', rows: 5, content: '模块一：人工智能导论\n模块二：机器学习基础\n模块三：深度学习初步\n模块四：自然语言处理与计算机视觉\n模块五：AI 伦理与未来展望' },
  { title: '互动设计', desc: '课堂互动、讨论与实践活动', rows: 4, content: '每节课设置随堂问答与知识点投票\n2 次小组讨论与案例分析汇报\n1 次动手实践：使用开源工具完成 AI 应用体验' },
  { title: '输出内容', desc: '期望生成的教学资源', rows: 4, content: '教学大纲\n教学 PPT\n课堂活动\n习题与测评\n案例库' },
]);
</script>

<style scoped>
.summary-block {
  display: grid;
  grid-template-columns: 180px minmax(0, 1fr);
  gap: 18px;
  padding: 16px 0;
  border-bottom: 1px solid var(--ui-border);
}

.summary-block p {
  margin: 6px 0 0;
  color: var(--ui-muted);
  line-height: 1.6;
}
</style>
