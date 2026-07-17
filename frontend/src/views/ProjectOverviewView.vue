<template>
  <section class="page overview-page" v-loading="loading">
    <template v-if="overview">
      <section class="panel overview-timeline">
        <div class="timeline-track">
          <article v-for="step in overview.timeline" :key="step.code" :class="['timeline-step', `is-${step.state.toLowerCase()}`]">
            <span>{{ step.state === 'COMPLETED' ? '✓' : step.state === 'CURRENT' ? '•' : '' }}</span>
            <strong>{{ step.label }}</strong>
            <small>{{ step.completedAt ? formatDateTime(step.completedAt) : step.state === 'CURRENT' ? '进行中' : '待完成' }}</small>
          </article>
        </div>
      </section>

      <div class="grid cols-5 overview-metrics">
        <UiProgressRingCard :value="overview.metrics.overallProgress" title="整体进度" note="按真实阶段完成情况计算" />
        <UiMetricCard label="生成内容" :value="generatedCount" :note="`PPT ${overview.metrics.pptCount} · 教案 ${overview.metrics.docxCount}`" tone="purple" icon="document" />
        <UiMetricCard label="资料文件" :value="overview.metrics.uploadedMaterialCount" :note="`已解析 ${overview.metrics.parsedMaterialCount} · 已索引 ${overview.metrics.indexedMaterialCount}`" tone="blue" icon="folder" />
        <UiMetricCard label="知识片段" :value="overview.metrics.knowledgeChunkCount" note="来源可追溯的本地知识" tone="green" icon="book" />
        <UiMetricCard label="版本与导出" :value="overview.metrics.versionCount" :note="`导出记录 ${overview.metrics.exportCount}`" tone="orange" icon="layers" />
      </div>

      <div class="overview-lower">
        <section class="panel">
          <div class="panel__header">
            <h3>最新动态</h3>
            <span class="tag-soft">真实项目记录</span>
          </div>
          <article v-for="activity in overview.recentActivities" :key="`${activity.type}-${activity.occurredAt}`" class="activity-row">
            <span class="activity-dot" />
            <div>
              <strong>{{ activity.title }}</strong>
              <p>{{ activity.description }}</p>
            </div>
            <time>{{ formatRelativeTime(activity.occurredAt) }}</time>
          </article>
          <el-empty v-if="overview.recentActivities.length === 0" description="项目尚无活动记录" :image-size="72" />
        </section>

        <aside class="grid">
          <section class="panel">
            <div class="panel__header">
              <h3>快速操作</h3>
              <UiStatusPill :label="overview.project.stageLabel" :tone="stageTone(overview.project.stage)" />
            </div>
            <div class="quick-actions">
              <el-button
                v-for="action in overview.quickActions"
                :key="action.code"
                :disabled="!action.enabled"
                @click="router.push(action.path)"
              >
                {{ action.label }}
              </el-button>
            </div>
          </section>

          <section class="panel project-details">
            <div class="panel__header"><h3>项目信息</h3></div>
            <dl>
              <div><dt>项目编号</dt><dd>PJ{{ String(overview.project.id).padStart(8, '0') }}</dd></div>
              <div><dt>所属课程</dt><dd>{{ overview.project.courseName }}</dd></div>
              <div><dt>授课对象</dt><dd>{{ overview.project.targetStudents || '待补充' }}</dd></div>
              <div><dt>课时长度</dt><dd>{{ overview.project.lessonDurationLabel || '待补充' }}</dd></div>
              <div><dt>创建时间</dt><dd>{{ formatFullDateTime(overview.project.createdAt) }}</dd></div>
            </dl>
          </section>
        </aside>
      </div>
    </template>

    <el-result v-else-if="!loading" icon="error" title="项目加载失败" sub-title="请返回项目列表后重试">
      <template #extra><el-button type="primary" @click="router.push('/projects')">返回项目列表</el-button></template>
    </el-result>
  </section>
</template>

<script setup lang="ts">
import { getProjectWorkspaceOverview, type ProjectOverview } from '@/api/workspace';
import UiMetricCard from '@/components/ui/UiMetricCard.vue';
import UiProgressRingCard from '@/components/ui/UiProgressRingCard.vue';
import UiStatusPill from '@/components/ui/UiStatusPill.vue';
import { formatDateTime, formatFullDateTime, formatRelativeTime, stageTone } from '@/utils/presentation';
import { computed, onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';

const route = useRoute();
const router = useRouter();
const projectId = computed(() => Number(route.params.projectId));
const overview = ref<ProjectOverview>();
const loading = ref(true);
const generatedCount = computed(() => {
  if (!overview.value) return 0;
  return overview.value.metrics.pptCount + overview.value.metrics.docxCount + overview.value.metrics.interactionCount;
});

async function loadOverview() {
  loading.value = true;
  try {
    overview.value = await getProjectWorkspaceOverview(projectId.value);
  } finally {
    loading.value = false;
  }
}

onMounted(loadOverview);
</script>

<style scoped>
.overview-page {
  min-width: 0;
}

.overview-timeline {
  margin-top: 14px;
  padding: 18px 20px;
}

.timeline-track {
  display: grid;
  grid-template-columns: repeat(8, minmax(92px, 1fr));
  gap: 8px;
  overflow-x: auto;
}

.timeline-step {
  position: relative;
  display: grid;
  min-width: 92px;
  justify-items: center;
  gap: 5px;
  color: var(--ui-muted);
  text-align: center;
}

.timeline-step::after {
  position: absolute;
  top: 15px;
  left: calc(50% + 19px);
  width: calc(100% - 38px);
  height: 2px;
  background: #e5e9f1;
  content: '';
}

.timeline-step:last-child::after {
  display: none;
}

.timeline-step > span {
  z-index: 1;
  display: grid;
  width: 30px;
  height: 30px;
  place-items: center;
  border: 2px solid #d9deea;
  border-radius: 50%;
  background: #fff;
  font-weight: 800;
}

.timeline-step.is-completed > span {
  border-color: #21ad63;
  background: #21ad63;
  color: #fff;
}

.timeline-step.is-current > span {
  border-color: var(--ui-primary);
  background: #f0edff;
  color: var(--ui-primary);
}

.timeline-step.is-completed::after {
  background: #21ad63;
}

.timeline-step strong {
  color: #39435a;
  font-size: 12px;
}

.timeline-step small {
  font-size: 10px;
}

.overview-metrics {
  margin-top: 14px;
}

.overview-lower {
  display: grid;
  grid-template-columns: minmax(0, 1.45fr) minmax(360px, 0.9fr);
  gap: 16px;
  margin-top: 16px;
}

.activity-row {
  display: grid;
  grid-template-columns: 12px minmax(0, 1fr) auto;
  align-items: start;
  gap: 10px;
  padding: 13px 0;
  border-bottom: 1px solid var(--ui-border);
}

.activity-dot {
  width: 8px;
  height: 8px;
  margin-top: 6px;
  border-radius: 50%;
  background: var(--ui-primary);
}

.activity-row p {
  margin: 4px 0 0;
  color: var(--ui-muted);
}

.activity-row time {
  color: var(--ui-muted);
  font-size: 12px;
  white-space: nowrap;
}

.quick-actions {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

.quick-actions .el-button {
  height: 46px;
  margin: 0;
}

.project-details dl {
  display: grid;
  gap: 12px;
  margin: 0;
}

.project-details dl div {
  display: flex;
  justify-content: space-between;
  gap: 20px;
}

.project-details dt,
.project-details dd {
  margin: 0;
}

.project-details dt {
  color: var(--ui-muted);
}

.project-details dd {
  text-align: right;
}

@media (max-width: 1180px) {
  .overview-metrics {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .overview-lower {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 760px) {
  .overview-metrics,
  .quick-actions {
    grid-template-columns: 1fr;
  }

  .activity-row {
    grid-template-columns: 12px minmax(0, 1fr);
  }

  .activity-row time {
    grid-column: 2;
  }
}
</style>
