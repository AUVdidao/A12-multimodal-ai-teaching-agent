<template>
  <section class="page home-page">
    <PageHeader
      eyebrow="M1 · 需求澄清闭环"
      title="教师备课工作台"
      description="围绕真实教学思路创建项目，通过 AI 主动澄清补齐需求，并形成可确认的结构化摘要。"
    >
      <template #actions>
        <el-button type="primary" :icon="Plus" @click="router.push('/projects/new')">新建教学项目</el-button>
        <el-button :icon="FolderOpened" @click="router.push('/projects')">查看全部项目</el-button>
      </template>
    </PageHeader>

    <section class="home-intro" aria-labelledby="home-intro-title">
      <div class="home-intro__content">
        <span class="home-intro__label">当前可演示能力</span>
        <h2 id="home-intro-title">从教学设想到确认版本，一条清晰的共创流程</h2>
        <p>项目创建、生成模式、教学需求、AI 澄清与摘要确认均已接入真实后端持久化。</p>
      </div>
      <dl class="home-intro__metrics">
        <div><dt>5</dt><dd>个 M1 步骤</dd></div>
        <div><dt>Mock</dt><dd>可控 AI 模式</dd></div>
        <div><dt>{{ projects.length }}</dt><dd>个本地项目</dd></div>
      </dl>
    </section>

    <section class="workflow-overview" aria-labelledby="workflow-title">
      <div class="section-caption">
        <div>
          <span>演示路径</span>
          <h2 id="workflow-title">M1 需求澄清流程</h2>
        </div>
        <p>每一步都有真实状态反馈，未完成的下一阶段不会被标记为可用。</p>
      </div>
      <ol class="workflow-overview__steps">
        <li v-for="(step, index) in workflowSteps" :key="step.title">
          <span>{{ index + 1 }}</span>
          <div><strong>{{ step.title }}</strong><small>{{ step.description }}</small></div>
        </li>
      </ol>
    </section>

    <div class="home-grid">
      <section class="home-section" aria-labelledby="recent-projects-title">
        <div class="section-caption section-caption--compact">
          <div><span>最近使用</span><h2 id="recent-projects-title">教学项目</h2></div>
          <el-button link type="primary" @click="router.push('/projects')">查看全部</el-button>
        </div>

        <StatePanel v-if="projectsLoading" type="loading" title="正在读取项目" description="请稍候，正在同步本地项目状态。" />
        <StatePanel v-else-if="projectsError" type="error" title="项目读取失败" :description="projectsError">
          <template #action><el-button size="small" @click="loadProjects">重新加载</el-button></template>
        </StatePanel>
        <StatePanel v-else-if="recentProjects.length === 0" type="empty" title="还没有教学项目" description="创建第一个项目后，最近使用记录会显示在这里。">
          <template #action><el-button size="small" type="primary" @click="router.push('/projects/new')">新建项目</el-button></template>
        </StatePanel>
        <div v-else class="recent-projects">
          <article v-for="project in recentProjects" :key="project.id" class="recent-project">
            <div class="recent-project__main">
              <StatusBadge :status="project.status" />
              <strong>{{ project.projectName }}</strong>
              <span>{{ project.courseName }} · {{ project.chapterTitle }}</span>
            </div>
            <el-button :icon="Right" text type="primary" :aria-label="`继续 ${project.projectName}`" @click="continueProject(project)" />
          </article>
        </div>
      </section>

      <section class="home-section service-section" aria-labelledby="service-title">
        <div class="section-caption section-caption--compact">
          <div><span>运行环境</span><h2 id="service-title">服务状态</h2></div>
          <el-button :icon="Refresh" text :loading="checking" aria-label="重新检查服务状态" @click="handleCheckHealth" />
        </div>
        <div class="service-status">
          <StatusBadge :status="healthStatus" :label="healthLabel" />
          <p>{{ healthDescription }}</p>
        </div>
        <div class="provider-note">
          <el-icon><Cpu /></el-icon>
          <div><strong>Mock AI Provider</strong><span>使用确定性结果保障比赛演示稳定，不调用真实 Dify。</span></div>
        </div>
      </section>
    </div>
  </section>
</template>

<script setup lang="ts">
import { checkBackendHealth } from '@/api/health';
import { listProjects, type TeachingProject } from '@/api/projects';
import PageHeader from '@/components/PageHeader.vue';
import StatePanel from '@/components/StatePanel.vue';
import StatusBadge from '@/components/StatusBadge.vue';
import { Cpu, FolderOpened, Plus, Refresh, Right } from '@element-plus/icons-vue';
import { computed, onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';

const router = useRouter();
const projects = ref<TeachingProject[]>([]);
const projectsLoading = ref(false);
const projectsError = ref('');
const checking = ref(false);
const healthStatus = ref('UNKNOWN');

const workflowSteps = [
  { title: '创建项目', description: '建立课程与章节上下文' },
  { title: '选择模式', description: '确定生成质量与效率策略' },
  { title: '输入需求', description: '沉淀教师原始教学设想' },
  { title: 'AI 澄清', description: '主动识别并补齐缺失信息' },
  { title: '摘要确认', description: '形成可追溯的确认版本' },
];

const recentProjects = computed(() => projects.value.slice(0, 3));
const healthLabel = computed(() => healthStatus.value === 'UP' ? '服务正常' : healthStatus.value === 'DOWN' ? '服务不可用' : '等待检查');
const healthDescription = computed(() => healthStatus.value === 'UP'
  ? '前端与后端连接正常，可以开始完整 M1 演示。'
  : healthStatus.value === 'DOWN'
    ? '暂时无法连接后端，请确认本地服务已经启动。'
    : '正在确认当前演示环境是否可用。');

onMounted(() => {
  loadProjects();
  handleCheckHealth();
});

async function loadProjects() {
  projectsLoading.value = true;
  projectsError.value = '';
  try {
    projects.value = await listProjects();
  } catch {
    projectsError.value = '暂时无法读取项目，请检查服务后重试。';
  } finally {
    projectsLoading.value = false;
  }
}

async function handleCheckHealth() {
  checking.value = true;
  try {
    const result = await checkBackendHealth();
    healthStatus.value = result.data.status;
  } catch {
    healthStatus.value = 'DOWN';
  } finally {
    checking.value = false;
  }
}

function continueProject(project: TeachingProject) {
  router.push(project.status === 'REQUIREMENT_CONFIRMED'
    ? `/projects/${project.id}/requirement-summary`
    : `/projects/${project.id}/mode`);
}
</script>

<style scoped>
.home-intro {
  display: grid;
  grid-template-columns: minmax(0, 1.35fr) minmax(360px, 0.65fr);
  align-items: center;
  gap: 32px;
  margin-bottom: 28px;
  padding: 28px 30px;
  border: 1px solid var(--color-primary-border);
  border-radius: var(--radius-lg);
  background: var(--color-primary-soft);
}

.home-intro__label,
.section-caption span {
  color: var(--color-primary);
  font-size: 11px;
  font-weight: 800;
}

.home-intro h2,
.home-intro p,
.section-caption h2,
.section-caption p {
  margin: 0;
}

.home-intro h2 {
  margin-top: 7px;
  font-size: 22px;
  line-height: 1.35;
}

.home-intro p {
  max-width: 650px;
  margin-top: 9px;
  color: var(--color-text-secondary);
  line-height: 1.7;
}

.home-intro__metrics {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 1px;
  margin: 0;
  overflow: hidden;
  border: 1px solid var(--color-primary-border);
  border-radius: var(--radius-md);
  background: var(--color-primary-border);
}

.home-intro__metrics div {
  padding: 17px 12px;
  background: rgba(255, 255, 255, 0.82);
  text-align: center;
}

.home-intro__metrics dt {
  color: var(--color-text);
  font-size: 20px;
  font-weight: 800;
}

.home-intro__metrics dd {
  margin: 3px 0 0;
  color: var(--color-text-muted);
  font-size: 10px;
}

.workflow-overview {
  margin-bottom: 30px;
}

.section-caption {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 24px;
  margin-bottom: 16px;
}

.section-caption h2 {
  margin-top: 4px;
  font-size: 18px;
}

.section-caption p {
  color: var(--color-text-muted);
  font-size: 12px;
}

.section-caption--compact {
  align-items: center;
}

.workflow-overview__steps {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 1px;
  margin: 0;
  padding: 0;
  overflow: hidden;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  background: var(--color-border);
  list-style: none;
}

.workflow-overview__steps li {
  display: flex;
  min-width: 0;
  gap: 10px;
  padding: 18px 14px;
  background: var(--color-surface);
}

.workflow-overview__steps li > span {
  display: grid;
  flex: 0 0 25px;
  width: 25px;
  height: 25px;
  place-items: center;
  border-radius: 50%;
  background: var(--color-primary-soft);
  color: var(--color-primary);
  font-size: 11px;
  font-weight: 800;
}

.workflow-overview__steps strong,
.workflow-overview__steps small {
  display: block;
}

.workflow-overview__steps strong {
  font-size: 13px;
}

.workflow-overview__steps small {
  margin-top: 3px;
  color: var(--color-text-muted);
  font-size: 10px;
  line-height: 1.5;
}

.home-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.45fr) minmax(300px, 0.55fr);
  gap: 28px;
}

.home-section {
  min-width: 0;
}

.recent-projects {
  border-top: 1px solid var(--color-border);
}

.recent-project {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  min-height: 78px;
  padding: 15px 4px;
  border-bottom: 1px solid var(--color-border);
}

.recent-project__main {
  display: grid;
  min-width: 0;
  grid-template-columns: auto 1fr;
  gap: 4px 10px;
}

.recent-project__main .status-badge {
  grid-row: 1 / span 2;
  align-self: center;
}

.recent-project__main strong,
.recent-project__main span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.recent-project__main span {
  color: var(--color-text-muted);
  font-size: 11px;
}

.service-section {
  padding-left: 28px;
  border-left: 1px solid var(--color-border);
}

.service-status {
  padding: 18px 0;
  border-top: 1px solid var(--color-border);
  border-bottom: 1px solid var(--color-border);
}

.service-status p {
  margin: 10px 0 0;
  color: var(--color-text-secondary);
  font-size: 12px;
  line-height: 1.6;
}

.provider-note {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  margin-top: 18px;
  color: var(--color-ai);
}

.provider-note strong,
.provider-note span {
  display: block;
}

.provider-note strong {
  color: var(--color-text);
  font-size: 12px;
}

.provider-note span {
  margin-top: 3px;
  color: var(--color-text-muted);
  font-size: 10px;
  line-height: 1.5;
}

@media (max-width: 1000px) {
  .home-intro,
  .home-grid {
    grid-template-columns: 1fr;
  }

  .workflow-overview__steps {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }

  .service-section {
    padding-left: 0;
    border-left: 0;
  }
}

@media (max-width: 640px) {
  .home-intro {
    padding: 22px 18px;
  }

  .home-intro__metrics,
  .workflow-overview__steps {
    grid-template-columns: 1fr;
  }

  .section-caption {
    align-items: flex-start;
    flex-direction: column;
    gap: 8px;
  }

  .recent-project__main {
    grid-template-columns: 1fr;
  }

  .recent-project__main .status-badge {
    grid-row: auto;
    justify-self: start;
  }
}
</style>
