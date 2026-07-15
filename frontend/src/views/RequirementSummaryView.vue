<template>
  <section class="page" v-loading="loading">
    <template v-if="workspace">
      <ProjectContextHeader :project="workspace.project" />
      <ProjectWorkspaceNav :project-id="workspace.project.id" />

      <div class="summary-layout">
        <section class="panel">
          <div class="panel__header">
            <div>
              <h3>需求摘要确认</h3>
              <p>摘要来自已保存的教学需求与澄清记录，确认后进入资料增强阶段。</p>
            </div>
            <UiStatusPill :label="statusLabel" :tone="workspace.summary?.status === 'CONFIRMED' ? 'green' : 'orange'" />
          </div>

          <el-empty v-if="!workspace.summary" description="尚未生成需求摘要">
            <el-button type="primary" @click="generateSummary" :loading="generating">根据当前需求生成摘要</el-button>
          </el-empty>

          <template v-else>
            <article class="summary-block">
              <div><strong>课程基础信息</strong><p>课程、对象、基础与课时</p></div>
              <div class="summary-fields">
                <el-input v-model="form.topic" placeholder="课程主题" :disabled="!workspace.editable" />
                <el-input v-model="form.subject" placeholder="学科" :disabled="!workspace.editable" />
                <el-input v-model="form.gradeLevel" placeholder="授课对象" :disabled="!workspace.editable" />
                <el-input v-model="form.baselineLevel" placeholder="基础水平" :disabled="!workspace.editable" />
                <el-input v-model="form.lessonDuration" placeholder="课时长度" :disabled="!workspace.editable" />
              </div>
            </article>
            <article class="summary-block">
              <div><strong>教学目标</strong><p>知识、能力与素养目标</p></div>
              <el-input v-model="form.teachingGoals" type="textarea" :rows="4" :disabled="!workspace.editable" />
            </article>
            <article class="summary-block">
              <div><strong>重点与难点</strong><p>内容组织的核心依据</p></div>
              <div class="summary-fields summary-fields--stack">
                <el-input v-model="form.keyPoints" type="textarea" :rows="3" placeholder="教学重点" :disabled="!workspace.editable" />
                <el-input v-model="form.difficultPoints" type="textarea" :rows="3" placeholder="教学难点" :disabled="!workspace.editable" />
              </div>
            </article>
            <article class="summary-block">
              <div><strong>教学风格与互动</strong><p>课堂呈现与活动形式</p></div>
              <div class="summary-fields">
                <el-input v-model="form.stylePreference" placeholder="教学风格" :disabled="!workspace.editable" />
                <el-input v-model="form.interactionType" placeholder="互动设计" :disabled="!workspace.editable" />
              </div>
            </article>
            <article class="summary-block">
              <div><strong>输出内容</strong><p>期望生成的教学资源</p></div>
              <el-checkbox-group v-model="form.outputTypes" :disabled="!workspace.editable">
                <el-checkbox value="PPT">教学 PPT</el-checkbox>
                <el-checkbox value="DOCX">Word 教案</el-checkbox>
                <el-checkbox value="INTERACTION">互动内容</el-checkbox>
              </el-checkbox-group>
            </article>
          </template>
        </section>

        <aside class="grid summary-aside">
          <section class="panel">
            <h3>确认状态</h3>
            <el-alert
              :title="statusLabel"
              :description="workspace.summary?.status === 'CONFIRMED' ? '需求已锁定，可进入资料与知识增强。' : '请检查并确认摘要，确认后项目将进入下一阶段。'"
              :type="workspace.summary?.status === 'CONFIRMED' ? 'success' : 'warning'"
              show-icon
              :closable="false"
            />
            <div class="summary-actions">
              <el-button :disabled="!workspace.editable || !workspace.summary" :loading="saving" @click="saveSummary">保存修改</el-button>
              <el-button type="primary" :disabled="!workspace.canConfirm || !workspace.summary" :loading="confirming" @click="confirmSummary">确认教学需求</el-button>
            </div>
            <el-button v-if="workspace.summary?.status === 'CONFIRMED'" style="width: 100%" @click="router.push(`/projects/${projectId}/materials`)">进入资料工作台</el-button>
          </section>

          <section class="panel">
            <h3>需求来源</h3>
            <div class="data-row"><span>来源类型</span><strong>{{ workspace.source?.sourceType || '暂无' }}</strong></div>
            <div class="data-row"><span>需求编号</span><strong>{{ workspace.source?.requirementId || '暂无' }}</strong></div>
            <div class="data-row"><span>生成模式</span><strong>{{ workspace.summary?.generationMode || workspace.project.modelMode }}</strong></div>
            <div class="data-row"><span>更新时间</span><strong>{{ formatFullDateTime(workspace.summary?.updatedAt) }}</strong></div>
          </section>

          <section class="panel next-stage-panel">
            <h3>下一阶段解锁</h3>
            <p v-for="item in workspace.nextStageCapabilities" :key="item">✓ {{ item }}</p>
            <p v-if="workspace.nextStageCapabilities.length === 0" class="muted">确认后将解锁资料上传、解析与知识检索。</p>
          </section>
        </aside>
      </div>
    </template>
  </section>
</template>

<script setup lang="ts">
import {
  confirmRequirementSummary,
  generateRequirementSummary,
  updateRequirementSummary,
  type RequirementSummaryPayload,
} from '@/api/requirementSummaries';
import { getRequirementSummaryWorkspace, type RequirementSummaryWorkspace } from '@/api/workspace';
import ProjectContextHeader from '@/components/ProjectContextHeader.vue';
import ProjectWorkspaceNav from '@/components/ProjectWorkspaceNav.vue';
import UiStatusPill from '@/components/ui/UiStatusPill.vue';
import { formatFullDateTime } from '@/utils/presentation';
import { ElMessage } from 'element-plus';
import { computed, onMounted, reactive, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';

const route = useRoute();
const router = useRouter();
const projectId = computed(() => Number(route.params.projectId));
const workspace = ref<RequirementSummaryWorkspace>();
const loading = ref(true);
const generating = ref(false);
const saving = ref(false);
const confirming = ref(false);
const form = reactive<RequirementSummaryPayload>(emptySummary());
const statusLabel = computed(() => {
  if (!workspace.value?.summary) return '未生成';
  return workspace.value.summary.status === 'CONFIRMED' ? '已确认' : '待确认';
});

function emptySummary(): RequirementSummaryPayload {
  return {
    gradeLevel: '',
    subject: '',
    topic: '',
    baselineLevel: '',
    lessonDuration: '',
    teachingGoals: '',
    keyPoints: '',
    difficultPoints: '',
    outputTypes: [],
    stylePreference: '',
    interactionType: '',
  };
}

async function loadWorkspace() {
  loading.value = true;
  try {
    workspace.value = await getRequirementSummaryWorkspace(projectId.value);
    Object.assign(form, emptySummary(), workspace.value.summary || {});
    form.outputTypes = [...(workspace.value.summary?.outputTypes || [])];
  } finally {
    loading.value = false;
  }
}

async function generateSummary() {
  generating.value = true;
  try {
    await generateRequirementSummary(projectId.value);
    await loadWorkspace();
    ElMessage.success('需求摘要已生成');
  } finally {
    generating.value = false;
  }
}

async function saveSummary() {
  const summaryId = workspace.value?.summary?.id;
  if (!summaryId) return;
  saving.value = true;
  try {
    await updateRequirementSummary(projectId.value, summaryId, { ...form, outputTypes: [...form.outputTypes] });
    await loadWorkspace();
    ElMessage.success('摘要修改已保存');
  } finally {
    saving.value = false;
  }
}

async function confirmSummary() {
  const summaryId = workspace.value?.summary?.id;
  if (!summaryId) return;
  confirming.value = true;
  try {
    if (workspace.value?.editable) await saveSummary();
    await confirmRequirementSummary(projectId.value, summaryId);
    await loadWorkspace();
    ElMessage.success('教学需求已确认');
  } finally {
    confirming.value = false;
  }
}

onMounted(loadWorkspace);
</script>

<style scoped>
.summary-layout {
  display: grid;
  grid-template-columns: minmax(0, 1.5fr) minmax(330px, 0.75fr);
  gap: 16px;
}

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

.summary-fields {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

.summary-fields--stack {
  grid-template-columns: 1fr;
}

.summary-actions {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
  margin: 18px 0 10px;
}

.summary-actions .el-button {
  margin: 0;
}

.next-stage-panel p {
  color: #268d55;
}

@media (max-width: 1050px) {
  .summary-layout {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 760px) {
  .summary-block,
  .summary-fields,
  .summary-actions {
    grid-template-columns: 1fr;
  }
}
</style>
