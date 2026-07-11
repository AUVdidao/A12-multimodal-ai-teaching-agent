<template>
  <section class="page project-overview-page">
    <div v-if="loading" class="surface-panel overview-loading"><el-skeleton :rows="7" animated /></div>
    <StatePanel v-else-if="errorMessage" type="error" title="项目概览读取失败" :description="errorMessage"><template #action><el-button @click="loadOverview">重新加载</el-button></template></StatePanel>
    <template v-else-if="project">
      <div class="overview-grid">
        <main class="overview-main">
          <section class="overview-section"><div class="section-heading"><div><span>项目摘要</span><h2>当前备课项目</h2></div><el-button text @click="router.push(`/projects/${project.id}/mode`)">查看生成模式</el-button></div><dl class="summary-grid"><div><dt>课程</dt><dd>{{ project.courseName }}</dd></div><div><dt>课题</dt><dd>{{ project.chapterTitle }}</dd></div><div><dt>授课对象</dt><dd>{{ project.targetStudents || '待补充' }}</dd></div><div><dt>课时长度</dt><dd>{{ project.lessonDuration ? `${project.lessonDuration} 分钟` : '待补充' }}</dd></div><div><dt>生成模式</dt><dd>{{ formatMode(project.modelMode) }}</dd></div><div><dt>创建时间</dt><dd>{{ formatDate(project.createdAt) }}</dd></div></dl></section>
          <section class="overview-section"><div class="section-heading"><div><span>当前进度</span><h2>备课任务</h2></div></div><div class="progress-list"><article v-for="item in progressItems" :key="item.key" class="progress-row"><div class="progress-row__marker" :class="`is-${item.state}`"><el-icon><component :is="item.icon" /></el-icon></div><div><strong>{{ item.title }}</strong><span>{{ item.description }}</span></div><StatusBadge :status="item.status" :label="item.label" /><el-button link type="primary" @click="router.push(item.path)">{{ item.action }}</el-button></article></div></section>
        </main>
        <aside class="overview-side"><section class="next-task"><span>下一项工作</span><h2>{{ nextTask.title }}</h2><p>{{ nextTask.description }}</p><el-button type="primary" @click="router.push(nextTask.path)">{{ nextTask.action }}</el-button></section><section class="overview-section data-summary"><div class="section-heading"><div><span>项目数据</span><h2>资料与知识</h2></div></div><dl><div><dt>参考资料</dt><dd>{{ materials.length }}</dd></div><div><dt>已解析资料</dt><dd>{{ parsedCount }}</dd></div><div><dt>知识片段</dt><dd>{{ knowledge?.chunkCount ?? '-' }}</dd></div><div><dt>教学意图</dt><dd>{{ intentLabel }}</dd></div></dl></section></aside>
      </div>
    </template>
  </section>
</template>

<script setup lang="ts">
import { getKnowledgeOverview, type KnowledgeOverview } from '@/api/knowledge';
import { listMaterials, type MaterialRecord } from '@/api/materials';
import { getProject, type TeachingProject } from '@/api/projects';
import { getLatestRequirementSummary, type RequirementSummary } from '@/api/requirementSummaries';
import { getLatestTeachingIntent, type TeachingIntent } from '@/api/teachingIntents';
import StatePanel from '@/components/StatePanel.vue';
import StatusBadge from '@/components/StatusBadge.vue';
import { Aim, CircleCheck, DocumentChecked, EditPen, Files, Reading } from '@element-plus/icons-vue';
import { computed, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';

const route = useRoute(); const router = useRouter(); const projectId = computed(() => Number(route.params.projectId));
const project = ref<TeachingProject | null>(null); const summary = ref<RequirementSummary | null>(null); const materials = ref<MaterialRecord[]>([]); const knowledge = ref<KnowledgeOverview | null>(null); const intent = ref<TeachingIntent | null>(null); const loading = ref(false); const errorMessage = ref('');
const parsedCount = computed(() => materials.value.filter((item) => item.parseStatus === 'SUCCEEDED').length);
const intentLabel = computed(() => intent.value?.status === 'CONFIRMED' ? '已确认' : intent.value ? '待确认' : '未生成');
const progressItems = computed(() => {
  const id = projectId.value;
  return [
    { key: 'requirements', title: '教学需求', description: '记录教师的课程设想与教学目标', icon: EditPen, status: summary.value ? 'CONFIRMED' : 'DRAFT', label: summary.value ? '已整理' : '待完善', state: summary.value ? 'complete' : 'current', path: `/projects/${id}/requirements`, action: '进入' },
    { key: 'summary', title: '需求确认', description: '确认结构化需求摘要', icon: DocumentChecked, status: summary.value?.status === 'CONFIRMED' ? 'CONFIRMED' : 'DRAFT', label: summary.value?.status === 'CONFIRMED' ? '已确认' : '待确认', state: summary.value?.status === 'CONFIRMED' ? 'complete' : 'pending', path: `/projects/${id}/requirement-summary`, action: '查看' },
    { key: 'materials', title: '参考资料', description: '上传、绑定并解析教学资料', icon: Files, status: materials.value.length ? 'CONFIRMED' : 'WAITING', label: materials.value.length ? `${materials.value.length} 份资料` : '待上传', state: materials.value.length ? 'complete' : 'pending', path: `/projects/${id}/materials`, action: '进入' },
    { key: 'knowledge', title: '知识库', description: '检索已建立的本地知识片段', icon: Reading, status: knowledge.value?.chunkCount ? 'CONFIRMED' : 'WAITING', label: knowledge.value?.chunkCount ? `${knowledge.value.chunkCount} 个片段` : '待建立', state: knowledge.value?.chunkCount ? 'complete' : 'pending', path: `/projects/${id}/knowledge`, action: '查看' },
    { key: 'intent', title: '教学意图', description: '确认生成前的教学意图与证据', icon: Aim, status: intent.value?.status || 'WAITING', label: intentLabel.value, state: intent.value?.status === 'CONFIRMED' ? 'complete' : 'pending', path: `/projects/${id}/teaching-intent`, action: '进入' },
  ];
});
const nextTask = computed(() => {
  const id = projectId.value;
  if (!summary.value) return { title: '继续完善教学需求', description: '补充教师的教学设想，系统会协助整理为需求摘要。', action: '进入教学需求', path: `/projects/${id}/requirements` };
  if (summary.value.status !== 'CONFIRMED') return { title: '确认需求摘要', description: '确认后可以进入资料增强工作区。', action: '查看需求摘要', path: `/projects/${id}/requirement-summary` };
  if (!materials.value.length) return { title: '上传参考资料', description: '添加与本课相关的教学资料，作为后续增强依据。', action: '上传资料', path: `/projects/${id}/materials` };
  if (!knowledge.value?.chunkCount) return { title: '建立知识依据', description: '完成资料用途绑定和解析后，可查看本地知识片段。', action: '查看资料', path: `/projects/${id}/materials` };
  if (intent.value?.status !== 'CONFIRMED') return { title: '确认教学意图', description: '核对资料和知识证据后，形成生成前教学意图。', action: '确认教学意图', path: `/projects/${id}/teaching-intent` };
  return { title: '项目已完成当前阶段', description: 'M1 和 M2 的当前任务已确认，可以回顾已有资料和教学意图。', action: '查看教学意图', path: `/projects/${id}/teaching-intent` };
});
watch(projectId, () => loadOverview(), { immediate: true });
async function loadOverview() { if (!Number.isInteger(projectId.value) || projectId.value <= 0) return; loading.value = true; errorMessage.value = ''; try { const id = projectId.value; const [projectData, summaryData, materialsData, knowledgeData, intentData] = await Promise.all([getProject(id), getLatestRequirementSummary(id), listMaterials(id), getKnowledgeOverview(id), getLatestTeachingIntent(id)]); project.value = projectData; summary.value = summaryData; materials.value = materialsData; knowledge.value = knowledgeData; intent.value = intentData; } catch { errorMessage.value = '暂时无法读取项目状态，请稍后重试。'; } finally { loading.value = false; } }
function formatMode(value: string) { return ({ STANDARD: '标准模式', QUALITY: '高质量模式', ECONOMY: '经济模式' } as Record<string, string>)[value] || value; } function formatDate(value: string) { return new Intl.DateTimeFormat('zh-CN', { year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date(value)); }
</script>

<style scoped>
.overview-grid { display: grid; grid-template-columns: minmax(0, 1.35fr) minmax(300px, .65fr); align-items: start; gap: 24px; }.overview-main, .overview-side { display: grid; gap: 24px; }.overview-section, .next-task { padding: 20px; border: 1px solid var(--color-border); border-radius: var(--radius-lg); background: var(--color-surface); box-shadow: var(--shadow-card); }.overview-loading { padding: 24px; }.section-heading { display: flex; align-items: center; justify-content: space-between; gap: 16px; }.section-heading span, .next-task > span { color: var(--color-primary); font-size: 11px; font-weight: 750; }.section-heading h2, .next-task h2, p { margin: 0; }.section-heading h2 { margin-top: 4px; font-size: 17px; }
.summary-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 14px; margin: 20px 0 0; }.summary-grid div { min-width: 0; }.summary-grid dt, .summary-grid dd, .data-summary dt, .data-summary dd { margin: 0; }.summary-grid dt, .data-summary dt { color: var(--color-text-muted); font-size: 11px; }.summary-grid dd { margin-top: 4px; font-size: 13px; font-weight: 700; overflow-wrap: anywhere; }
.progress-list { margin-top: 12px; border-top: 1px solid var(--color-border); }.progress-row { display: grid; grid-template-columns: 34px minmax(0, 1fr) auto auto; align-items: center; gap: 12px; min-height: 64px; border-bottom: 1px solid var(--color-border); }.progress-row:last-child { border-bottom: 0; }.progress-row__marker { display: grid; width: 28px; height: 28px; place-items: center; border-radius: 50%; background: var(--color-surface-subtle); color: var(--color-text-muted); }.progress-row__marker.is-complete { background: var(--color-success-soft); color: var(--color-success); }.progress-row__marker.is-current { background: var(--color-primary-soft); color: var(--color-primary); }.progress-row strong, .progress-row span { display: block; }.progress-row span { margin-top: 2px; color: var(--color-text-muted); font-size: 11px; }
.next-task { border-color: var(--color-primary-border); background: var(--color-primary-soft); }.next-task h2 { margin-top: 5px; font-size: 19px; }.next-task p { margin: 8px 0 16px; color: var(--color-text-secondary); font-size: 13px; line-height: 1.65; }.data-summary dl { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0; margin: 16px 0 0; border-top: 1px solid var(--color-border); }.data-summary dl div { padding: 12px 0; border-bottom: 1px solid var(--color-border); }.data-summary dd { margin-top: 4px; font-weight: 750; }
@media (max-width: 1000px) { .overview-grid { grid-template-columns: 1fr; } .overview-side { grid-template-columns: repeat(2, minmax(0, 1fr)); } } @media (max-width: 680px) { .summary-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }.overview-side { grid-template-columns: 1fr; }.progress-row { grid-template-columns: 32px minmax(0, 1fr) auto; }.progress-row .status-badge { display: none; } }
</style>
