<template>
  <section class="page">
    <ProjectContextHeader :project="project" />
    <ProjectWorkspaceNav :project-id="project.id" />

    <div class="grid cols-2">
      <section class="panel">
        <div class="panel__header">
          <div>
            <h3>教学需求与智能澄清</h3>
            <p>AI 会围绕缺失字段继续追问，教师可直接补充需求。</p>
          </div>
          <div class="inline-actions">
            <el-button>保存草稿</el-button>
            <el-button type="primary" @click="router.push(`/projects/${project.id}/summary`)">需求摘要</el-button>
          </div>
        </div>

        <el-alert title="AI 助教正在帮助您完善教学需求，请尽可能详细描述想法。" type="info" show-icon :closable="false" />

        <div class="chat-list">
          <UiChatMessage
            v-for="message in messages"
            :key="message.content"
            :role="message.role"
            :content="message.content"
            :time="message.time"
          />
        </div>

        <el-input
          v-model="draft"
          type="textarea"
          :rows="4"
          maxlength="2000"
          show-word-limit
          placeholder="请输入您的需求或回复 AI 的问题..."
        />
        <div class="page-actions">
          <el-button type="primary" @click="sendMessage">发送回复</el-button>
          <el-button @click="draft = '希望案例多一些，风格活泼一些，需要课堂问答互动。'">填入示例</el-button>
        </div>
      </section>

      <aside class="grid">
        <section class="panel">
          <div class="panel__header">
            <h3>需求完善进度</h3>
            <span class="tag-soft">78%</span>
          </div>
          <div class="progress-line"><span style="width: 78%" /></div>
          <p>已收集 7/9 项关键教学信息。</p>
        </section>

        <section class="panel">
          <h3>关键信息收集</h3>
          <div v-for="field in requirementFields" :key="field[0]" class="data-row" style="padding: 9px 0; border-bottom: 1px solid var(--ui-border)">
            <span>{{ field[0] }}</span>
            <strong>{{ field[1] }}</strong>
            <span class="tag-soft success">已确认</span>
          </div>
        </section>

        <section class="panel">
          <h3>AI 常用追问示例</h3>
          <el-button v-for="question in questions" :key="question" style="width: 100%; justify-content: space-between; margin-top: 8px" @click="draft = question">
            {{ question }}
          </el-button>
        </section>
      </aside>
    </div>
  </section>
</template>

<script setup lang="ts">
import ProjectContextHeader from '@/components/ProjectContextHeader.vue';
import ProjectWorkspaceNav from '@/components/ProjectWorkspaceNav.vue';
import UiChatMessage from '@/components/ui/UiChatMessage.vue';
import { getDemoProject, requirementFields } from '@/mock/demo';
import { ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';

const route = useRoute();
const router = useRouter();
const project = getDemoProject(route.params.projectId as string);
const draft = ref('');
const messages = ref<Array<{ role: 'ai' | 'teacher'; content: string; time: string }>>([
  { role: 'ai', content: '您好，张老师！为了更好地生成高质量课件和教案，我需要了解本次课程希望达成的教学目标。', time: '10:25' },
  { role: 'teacher', content: '了解人工智能的基本概念、发展历程和主要应用场景。', time: '10:26' },
  { role: 'ai', content: '授课对象是哪个年级或专业的学生？他们的基础水平如何？', time: '10:26' },
  { role: 'teacher', content: '大学一年级计算机专业学生，有一定编程基础，但对 AI 了解不多。', time: '10:27' },
  { role: 'ai', content: '本次课程预计多长时间？是否有重点要强调的知识点或难点？', time: '10:28' },
]);
const questions = [
  '本次课程希望学生掌握哪些具体能力？',
  '是否有特别引用的案例或资料？',
  'PPT 风格是否需要统一模板或配色？',
  '是否需要课后作业或测试题？',
];

function sendMessage() {
  const content = draft.value.trim();
  if (!content) return;
  messages.value.push({ role: 'teacher', content, time: '现在' });
  draft.value = '';
}
</script>

<style scoped>
.chat-list {
  display: grid;
  gap: 12px;
  max-height: 480px;
  margin: 16px 0;
  overflow: auto;
}

</style>
