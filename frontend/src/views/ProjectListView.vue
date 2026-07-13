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
            <el-radio-button label="ALL">全部状态</el-radio-button>
            <el-radio-button label="REQUIREMENT_CLARIFYING">需求澄清中</el-radio-button>
            <el-radio-button label="MATERIAL_ANALYZING">资料解析中</el-radio-button>
            <el-radio-button label="INTENT_CONFIRMED">意图已确认</el-radio-button>
            <el-radio-button label="FINALIZED">已定稿</el-radio-button>
          </el-radio-group>
          <el-button @click="toggleSort">更新时间 {{ sortDesc ? '↓' : '↑' }}</el-button>
        </div>
      </div>

      <el-table v-loading="loading" :data="projects" @row-click="openProject">
        <el-table-column label="教学项目" min-width="260">
          <template #default="{ row }">
            <div class="project-table-identity">
              <UiSubjectIcon :icon="projectIcon(row.id)" :tone="projectTone(row.id)" />
              <div>
                <strong>{{ row.projectName }}</strong>
                <span>{{ row.subtitle || row.chapterTitle }}</span>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="课程" min-width="160">
          <template #default="{ row }">
            {{ row.courseName }}<div class="muted">{{ row.chapterTitle }}</div>
          </template>
        </el-table-column>
        <el-table-column prop="targetStudents" label="面向受众" min-width="150" />
        <el-table-column label="当前阶段" width="140">
          <template #default="{ row }">
            <UiStatusPill :label="row.stageLabel" :tone="stageTone(row.stage)" />
          </template>
        </el-table-column>
        <el-table-column label="进度" width="170">
          <template #default="{ row }">
            <div class="project-table-progress" :aria-label="`进度 ${row.progress}%`">
              <span class="project-table-progress__track">
                <i :class="projectTone(row.id)" :style="{ width: `${row.progress}%` }" />
              </span>
              <strong>{{ row.progress }}%</strong>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="更新时间" width="160">
          <template #default="{ row }">
            <time :datetime="row.updatedAt">{{ formatDateTime(row.updatedAt) }}</time>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="160" fixed="right">
          <template #default="{ row }">
            <el-button type="primary" plain @click.stop="openProject(row)">继续项目</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="project-list-footer">
        <p class="muted">共 {{ total }} 个项目</p>
        <el-pagination
          v-model:current-page="currentPage"
          :page-size="pageSize"
          :total="total"
          layout="prev, pager, next"
          @current-change="loadProjects"
        />
      </div>
    </section>
  </section>
</template>

<script setup lang="ts">
import { getWorkspaceProjects, type ProjectBrief } from '@/api/workspace';
import UiStatusPill from '@/components/ui/UiStatusPill.vue';
import UiSubjectIcon from '@/components/ui/UiSubjectIcon.vue';
import { formatDateTime, projectIcon, projectTone, stageTone } from '@/utils/presentation';
import { onMounted, ref, watch } from 'vue';
import { useRouter } from 'vue-router';

const router = useRouter();
const keyword = ref('');
const filter = ref('ALL');
const sortDesc = ref(true);
const projects = ref<ProjectBrief[]>([]);
const total = ref(0);
const currentPage = ref(1);
const pageSize = 10;
const loading = ref(false);
let searchTimer: number | undefined;

async function loadProjects() {
  loading.value = true;
  try {
    const result = await getWorkspaceProjects({
      query: keyword.value.trim() || undefined,
      stage: filter.value,
      page: currentPage.value - 1,
      size: pageSize,
      sort: sortDesc.value ? 'UPDATED_DESC' : 'UPDATED_ASC',
    });
    projects.value = result.items;
    total.value = result.totalElements;
  } finally {
    loading.value = false;
  }
}

function openProject(project: ProjectBrief) {
  router.push(`/projects/${project.id}`);
}

function toggleSort() {
  sortDesc.value = !sortDesc.value;
  loadProjects();
}

watch(filter, () => {
  currentPage.value = 1;
  loadProjects();
});

watch(keyword, () => {
  window.clearTimeout(searchTimer);
  searchTimer = window.setTimeout(() => {
    currentPage.value = 1;
    loadProjects();
  }, 260);
});

onMounted(loadProjects);
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

.project-list-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-top: 14px;
}

.project-list-footer p {
  margin: 0;
}
</style>
