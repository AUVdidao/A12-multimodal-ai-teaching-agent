<template>
  <section class="page">
    <ProjectContextHeader :project="project" />
    <ProjectWorkspaceNav :project-id="project.id" />

    <div class="grid cols-3">
      <article class="stat-card">
        <small>已索引资料</small>
        <strong>6</strong>
        <p class="muted">来自当前项目资料库</p>
      </article>
      <article class="stat-card">
        <small>知识片段</small>
        <strong>3</strong>
        <p class="muted">前端演示结果</p>
      </article>
      <article class="stat-card">
        <small>检索方式</small>
        <strong style="font-size: 22px">确定性检索</strong>
        <p class="muted">关键词精确匹配</p>
      </article>
    </div>

    <section class="panel" style="margin-top: 16px">
      <div class="panel__header">
        <div>
          <h3>确定性检索</h3>
          <p>在本地知识库中精确匹配关键词，返回最相关的知识片段。</p>
        </div>
        <el-button type="primary">执行检索</el-button>
      </div>
      <el-input v-model="query" size="large" clearable placeholder="机器学习中的过拟合是什么，如何解决？" />
      <div class="inline-actions" style="margin-top: 12px">
        <el-select v-model="source" style="width: 160px">
          <el-option label="全部资料" value="all" />
          <el-option label="教材依据" value="book" />
          <el-option label="案例素材" value="case" />
        </el-select>
        <el-switch v-model="caseSensitive" inactive-text="区分大小写" />
      </div>
    </section>

    <section class="panel" style="margin-top: 16px">
      <div class="panel__header">
        <h3>检索结果（共 {{ demoKnowledge.length }} 条）</h3>
        <div class="inline-actions">
          <el-button>按相关度排序</el-button>
          <el-button @click="router.push(`/projects/${project.id}/intent`)">采用并进入意图</el-button>
        </div>
      </div>

      <article v-for="item in demoKnowledge" :key="item.title" class="knowledge-card">
        <div class="knowledge-card__score">{{ item.score }}<small>%</small><span>匹配度</span></div>
        <div>
          <h3>{{ item.title }}</h3>
          <p>{{ item.content }}</p>
          <div class="inline-actions" style="flex-wrap: wrap">
            <span class="tag-soft info">{{ item.source }}</span>
            <span class="tag-soft">{{ item.location }}</span>
            <span v-for="keyword in item.keywords" :key="keyword" class="tag-soft">{{ keyword }}</span>
          </div>
        </div>
      </article>
    </section>
  </section>
</template>

<script setup lang="ts">
import ProjectContextHeader from '@/components/ProjectContextHeader.vue';
import ProjectWorkspaceNav from '@/components/ProjectWorkspaceNav.vue';
import { demoKnowledge, getDemoProject } from '@/mock/demo';
import { ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';

const route = useRoute();
const router = useRouter();
const project = getDemoProject(route.params.projectId as string);
const query = ref('机器学习中的过拟合是什么，如何解决？');
const source = ref('all');
const caseSensitive = ref(false);
</script>

<style scoped>
.knowledge-card {
  display: grid;
  grid-template-columns: 92px minmax(0, 1fr);
  gap: 18px;
  padding: 18px 0;
  border-bottom: 1px solid var(--ui-border);
}

.knowledge-card__score {
  display: grid;
  place-items: center;
  align-self: start;
  min-height: 78px;
  border-radius: 14px;
  background: #edf4ff;
  color: var(--ui-info);
  font-size: 27px;
  font-weight: 800;
}

.knowledge-card__score small,
.knowledge-card__score span {
  font-size: 12px;
  font-weight: 600;
}

.knowledge-card h3 {
  margin: 0;
}
</style>
