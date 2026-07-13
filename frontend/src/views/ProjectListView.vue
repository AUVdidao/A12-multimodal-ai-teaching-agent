<template>
  <section class="page">
    <header class="page-hero">
      <div>
        <h2>教学项目</h2>
        <p>集中管理备课项目，快速筛选当前阶段并继续下一项教学任务。</p>
      </div>
      <el-button type="primary" @click="router.push('/projects/new')">新建教学项目</el-button>
    </header>

    <section class="panel">
      <div class="panel__header">
        <el-input v-model="keyword" clearable placeholder="搜索项目名称、课程或受众..." style="max-width: 360px" />
        <div class="inline-actions">
          <el-radio-group v-model="filter" size="large">
            <el-radio-button label="全部状态" />
            <el-radio-button label="需求澄清中" />
            <el-radio-button label="资料解析中" />
            <el-radio-button label="意图已确认" />
            <el-radio-button label="已定稿" />
          </el-radio-group>
          <el-button @click="toggleSort">更新时间 {{ sortDesc ? '↓' : '↑' }}</el-button>
        </div>
      </div>

      <el-table :data="visibleProjects" @row-click="openProject">
        <el-table-column label="教学项目" min-width="260">
          <template #default="{ row }">
            <div class="project-table-identity">
              <UiSubjectIcon :icon="projectPresentation[row.id].icon" :tone="projectPresentation[row.id].tone" />
              <div>
                <strong>{{ row.projectName }}</strong>
                <span>{{ projectPresentation[row.id].subtitle }}</span>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="课程" min-width="160">
          <template #default="{ row }">
            {{ row.courseName }}<div class="muted">{{ row.textbook }}</div>
          </template>
        </el-table-column>
        <el-table-column prop="targetStudents" label="面向受众" min-width="150" />
        <el-table-column label="当前阶段" width="140">
          <template #default="{ row }">
            <UiStatusPill :label="stageLabel(row.status)" :tone="statusTone(row.status)" />
          </template>
        </el-table-column>
        <el-table-column label="进度" width="170">
          <template #default="{ row }">
            <div class="project-table-progress" :aria-label="`进度 ${row.progress}%`">
              <span class="project-table-progress__track">
                <i :class="projectPresentation[row.id].tone" :style="{ width: `${row.progress}%` }" />
              </span>
              <strong>{{ row.progress }}%</strong>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="更新时间" width="160">
          <template #default="{ row }">
            <time :datetime="row.updatedAt">{{ projectPresentation[row.id].updatedLabel }}</time>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="160" fixed="right">
          <template #default="{ row }">
            <el-button type="primary" plain @click.stop="openProject(row)">继续项目</el-button>
          </template>
        </el-table-column>
      </el-table>

      <p class="muted" style="margin: 14px 0 0">共 {{ visibleProjects.length }} 个项目</p>
    </section>
  </section>
</template>

<script setup lang="ts">
import { demoProjects, stageLabel, type DemoProject } from '@/mock/demo';
import UiStatusPill from '@/components/ui/UiStatusPill.vue';
import UiSubjectIcon from '@/components/ui/UiSubjectIcon.vue';
import { projectPresentation } from '@/mock/projectPresentation';
import { computed, ref } from 'vue';
import { useRouter } from 'vue-router';

const router = useRouter();
const keyword = ref('');
const filter = ref('全部状态');
const sortDesc = ref(true);

const visibleProjects = computed(() => {
  const text = keyword.value.trim().toLowerCase();
  return [...demoProjects]
    .filter((project) => filter.value === '全部状态' || stageLabel(project.status) === filter.value)
    .filter((project) => {
      if (!text) return true;
      return `${project.projectName} ${project.courseName} ${project.targetStudents}`.toLowerCase().includes(text);
    })
    .sort((left, right) => (sortDesc.value ? right.updatedAt.localeCompare(left.updatedAt) : left.updatedAt.localeCompare(right.updatedAt)));
});

function openProject(project: DemoProject) {
  router.push(`/projects/${project.id}`);
}

function toggleSort() {
  sortDesc.value = !sortDesc.value;
}

function statusTone(status: string) {
  if (status === 'MATERIAL_ANALYZING') return 'blue';
  if (status === 'INTENT_CONFIRMED') return 'green';
  if (status === 'DRAFT_READY') return 'purple';
  return 'orange';
}
</script>

<style scoped>
.project-table-identity {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 12px;
}

.project-table-identity > div {
  min-width: 0;
}

.project-table-identity > div > strong,
.project-table-identity > div > span {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.project-table-identity > div > strong {
  color: #222b3f;
  line-height: 20px;
}

.project-table-identity > div > span {
  margin-top: 2px;
  color: #77809a;
  font-size: 12px;
  line-height: 17px;
}

.project-table-progress {
  display: grid;
  grid-template-columns: 92px 42px;
  align-items: center;
  gap: 10px;
}

.project-table-progress strong {
  color: #4d5670;
  font-size: 13px;
}

.project-table-progress__track {
  display: block;
  overflow: hidden;
  height: 7px;
  border-radius: 999px;
  background: #edf0f5;
}

.project-table-progress__track i {
  display: block;
  height: 100%;
  border-radius: inherit;
}

.project-table-progress__track .purple {
  background: #635bff;
}

.project-table-progress__track .green {
  background: #18aa55;
}

.project-table-progress__track .orange {
  background: #ff941f;
}

.project-table-progress__track .blue {
  background: #3f91f7;
}

.project-table-progress__track .red {
  background: #ff6278;
}

time {
  color: #66708a;
  white-space: nowrap;
}
</style>
