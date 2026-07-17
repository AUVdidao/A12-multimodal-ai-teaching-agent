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
      <div class="panel__header project-list-toolbar">
        <el-input v-model="keyword" clearable placeholder="搜索项目名称、课程或受众..." style="max-width: 360px" />
        <div class="inline-actions">
          <el-radio-group v-model="filter" size="large">
            <el-radio-button value="ALL">全部状态</el-radio-button>
            <el-radio-button value="REQUIREMENT_CLARIFYING">需求澄清中</el-radio-button>
            <el-radio-button value="MATERIAL_ANALYZING">资料解析中</el-radio-button>
            <el-radio-button value="INTENT_CONFIRMED">意图已确认</el-radio-button>
            <el-radio-button value="FINALIZED">已定稿</el-radio-button>
          </el-radio-group>
          <el-button @click="toggleSort">更新时间 {{ sortDesc ? '↓' : '↑' }}</el-button>
        </div>
      </div>

      <div v-if="errorMessage" class="project-list-error">
        <el-alert :title="errorMessage" type="error" show-icon :closable="false" />
        <el-button :loading="loading" @click="loadProjects">重新加载</el-button>
      </div>

      <el-table v-if="!isMobileViewport" v-loading="loading" :data="projects" @row-click="openProject">
        <el-table-column label="教学项目" min-width="220">
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
        <el-table-column v-if="!isNarrowTable" label="课程" min-width="140">
          <template #default="{ row }">
            {{ row.courseName }}<div class="muted">{{ row.chapterTitle }}</div>
          </template>
        </el-table-column>
        <el-table-column v-if="!isCompactTable" prop="targetStudents" label="面向受众" min-width="150" />
        <el-table-column label="当前阶段" width="120">
          <template #default="{ row }">
            <UiStatusPill :label="row.stageLabel" :tone="stageTone(row.stage)" />
          </template>
        </el-table-column>
        <el-table-column label="进度" width="135">
          <template #default="{ row }">
            <div class="project-table-progress" :aria-label="`进度 ${row.progress}%`">
              <span class="project-table-progress__track">
                <i :class="projectTone(row.id)" :style="{ width: `${row.progress}%` }" />
              </span>
              <strong>{{ row.progress }}%</strong>
            </div>
          </template>
        </el-table-column>
        <el-table-column v-if="!isCompactTable" label="更新时间" width="150">
          <template #default="{ row }">
            <time :datetime="row.updatedAt">{{ formatDateTime(row.updatedAt) }}</time>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="136" fixed="right">
          <template #default="{ row }">
            <div class="project-row-actions">
              <el-button
                class="project-row-actions__continue"
                type="primary"
                size="small"
                :icon="ArrowRight"
                @click.stop="openProject(row)"
              >
                继续
              </el-button>
              <el-dropdown
                trigger="click"
                :disabled="deletingProjectId === row.id"
                @click.stop
                @command="handleProjectCommand($event, row)"
              >
                <button
                  class="project-row-menu-button"
                  type="button"
                  title="更多操作"
                  :aria-label="`${row.projectName}的更多操作`"
                  :disabled="deletingProjectId === row.id"
                  @click.stop
                >
                  <el-icon v-if="deletingProjectId === row.id" class="is-loading"><Loading /></el-icon>
                  <el-icon v-else><MoreFilled /></el-icon>
                </button>
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item command="recycle">
                      <span class="project-recycle-command">移入回收站</span>
                    </el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
            </div>
          </template>
        </el-table-column>
      </el-table>

      <section v-else class="project-mobile-list" v-loading="loading" aria-label="教学项目列表">
        <el-empty v-if="!loading && projects.length === 0" description="暂无教学项目" :image-size="72" />
        <article v-for="project in projects" :key="project.id" class="project-mobile-card">
          <div class="project-mobile-card__heading">
            <div class="project-mobile-card__identity">
              <UiSubjectIcon :icon="projectIcon(project.id)" :tone="projectTone(project.id)" />
              <div>
                <strong>{{ project.projectName }}</strong>
                <span>{{ project.subtitle || project.chapterTitle }}</span>
              </div>
            </div>
            <span class="project-mobile-card__status">
              <UiStatusPill :label="project.stageLabel" :tone="stageTone(project.stage)" />
            </span>
          </div>
          <dl class="project-mobile-card__facts">
            <div>
              <dt>课程</dt>
              <dd>{{ project.courseName }} · {{ project.chapterTitle }}</dd>
            </div>
            <div>
              <dt>面向受众</dt>
              <dd>{{ project.targetStudents }}</dd>
            </div>
            <div>
              <dt>更新时间</dt>
              <dd><time :datetime="project.updatedAt">{{ formatDateTime(project.updatedAt) }}</time></dd>
            </div>
          </dl>
          <div class="project-mobile-card__progress" :aria-label="`进度 ${project.progress}%`">
            <span class="project-table-progress__track"><i :class="projectTone(project.id)" :style="{ width: `${project.progress}%` }" /></span>
            <strong>{{ project.progress }}%</strong>
          </div>
          <div class="project-mobile-card__actions">
            <el-button
              class="project-row-actions__continue"
              type="primary"
              :icon="ArrowRight"
              @click="openProject(project)"
            >
              继续
            </el-button>
            <el-dropdown
              trigger="click"
              :disabled="deletingProjectId === project.id"
              @command="handleProjectCommand($event, project)"
            >
              <button
                class="project-row-menu-button project-row-menu-button--mobile"
                type="button"
                title="更多操作"
                :aria-label="`${project.projectName}的更多操作`"
                :disabled="deletingProjectId === project.id"
              >
                <el-icon v-if="deletingProjectId === project.id" class="is-loading"><Loading /></el-icon>
                <el-icon v-else><MoreFilled /></el-icon>
              </button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="recycle">
                    <span class="project-recycle-command">移入回收站</span>
                  </el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </div>
        </article>
      </section>

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
import { deleteProject } from '@/api/projects';
import { getWorkspaceProjects, type ProjectBrief } from '@/api/workspace';
import UiStatusPill from '@/components/ui/UiStatusPill.vue';
import UiSubjectIcon from '@/components/ui/UiSubjectIcon.vue';
import { formatDateTime, projectIcon, projectTone, stageTone } from '@/utils/presentation';
import { ArrowRight, Loading, MoreFilled } from '@element-plus/icons-vue';
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';

const router = useRouter();
const keyword = ref('');
const filter = ref('ALL');
const sortDesc = ref(true);
const projects = ref<ProjectBrief[]>([]);
const total = ref(0);
const currentPage = ref(1);
const pageSize = 10;
const loading = ref(false);
const deletingProjectId = ref<number | null>(null);
const errorMessage = ref('');
const viewportWidth = ref(window.innerWidth);
let searchTimer: number | undefined;

const isMobileViewport = computed(() => viewportWidth.value <= 760);
const isCompactTable = computed(() => viewportWidth.value <= 1500);
const isNarrowTable = computed(() => viewportWidth.value <= 1180);

async function loadProjects() {
  loading.value = true;
  errorMessage.value = '';
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
  } catch (error) {
    projects.value = [];
    total.value = 0;
    errorMessage.value = resolveError(error, '暂时无法读取项目列表，请稍后重试。');
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

function handleProjectCommand(command: string, project: ProjectBrief) {
  if (command === 'recycle') {
    void moveToRecycleBin(project);
  }
}

async function moveToRecycleBin(project: ProjectBrief) {
  if (deletingProjectId.value !== null) return;
  try {
    await ElMessageBox.confirm(`确认将“${project.projectName}”移入回收站吗？`, '移入回收站', {
      confirmButtonText: '移入回收站',
      cancelButtonText: '取消',
      type: 'warning',
    });
  } catch {
    return;
  }

  deletingProjectId.value = project.id;
  try {
    await deleteProject(project.id);
    ElMessage.success('项目已移入回收站');
    await loadProjects();
  } catch (error: any) {
    ElMessage.error(error?.response?.data?.message || '移入回收站失败，请稍后重试');
  } finally {
    deletingProjectId.value = null;
  }
}

function resolveError(error: unknown, fallback: string) {
  const message = (error as { response?: { data?: { message?: string } } }).response?.data?.message;
  return message && !/(Exception|java\.|Axios)/i.test(message) ? message : fallback;
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

function updateViewportWidth() {
  viewportWidth.value = window.innerWidth;
}

onMounted(() => {
  window.addEventListener('resize', updateViewportWidth);
  void loadProjects();
});

onBeforeUnmount(() => window.removeEventListener('resize', updateViewportWidth));
</script>

<style scoped>
.page,
.panel,
.project-list-toolbar,
.project-list-toolbar > * {
  min-width: 0;
}

.project-list-toolbar {
  flex-wrap: wrap;
}

.project-list-toolbar > :first-child {
  flex: 1 1 260px;
  min-width: 0;
}

.project-list-toolbar .inline-actions {
  display: flex;
  min-width: 0;
  flex: 1 1 560px;
  flex-wrap: wrap;
  justify-content: flex-end;
}

.project-list-toolbar .el-radio-group {
  max-width: 100%;
  overflow-x: auto;
}

.project-list-toolbar .el-radio-button {
  flex: 0 0 auto;
}

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

.project-list-error {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 14px;
}

.project-list-error .el-alert {
  flex: 1 1 auto;
}

.project-row-actions {
  display: flex;
  min-width: 0;
  align-items: center;
  justify-content: flex-end;
  gap: 6px;
}

.project-row-actions .el-button + .el-button {
  margin-left: 0;
}

.project-row-actions__continue.el-button {
  min-width: 76px;
  height: 32px;
  padding: 0 10px;
}

.project-row-menu-button {
  display: grid;
  width: 32px;
  height: 32px;
  padding: 0;
  place-items: center;
  border: 1px solid #dfe3ec;
  border-radius: 6px;
  background: transparent;
  color: #59637a;
  cursor: pointer;
}

.project-row-menu-button:hover,
.project-row-menu-button:focus-visible {
  border-color: #bfb4f6;
  background: #f5f3ff;
  color: #4e3aef;
  outline: none;
}

.project-row-menu-button:active {
  border-color: #6d58f1;
  background: #e9e4ff;
}

.project-row-menu-button:disabled {
  cursor: wait;
  opacity: 0.62;
}

.project-recycle-command {
  color: #b63c47;
}

.is-loading {
  animation: project-action-spin 900ms linear infinite;
}

@keyframes project-action-spin {
  to {
    transform: rotate(360deg);
  }
}

.project-mobile-list {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: 12px;
  width: 100%;
  max-width: 100%;
  min-width: 0;
}

.project-mobile-card {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: 14px;
  padding: 16px;
  border: 1px solid var(--ui-border);
  border-radius: 10px;
  background: var(--ui-panel);
  width: 100%;
  max-width: 100%;
  min-width: 0;
  box-sizing: border-box;
}

.project-mobile-card__heading,
.project-mobile-card__identity,
.project-mobile-card__progress,
.project-mobile-card__actions {
  display: flex;
  align-items: center;
}

.project-mobile-card__heading {
  justify-content: space-between;
  gap: 12px;
  min-width: 0;
}

.project-mobile-card__identity {
  flex: 1 1 auto;
  min-width: 0;
  gap: 10px;
}

.project-mobile-card__status {
  flex: 0 0 auto;
  max-width: 42%;
  min-width: 0;
}

.project-mobile-card__identity > div {
  min-width: 0;
}

.project-mobile-card__identity strong,
.project-mobile-card__identity span {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.project-mobile-card__identity strong {
  color: var(--ui-text);
  font-size: 14px;
}

.project-mobile-card__identity span,
.project-mobile-card__facts {
  color: var(--ui-muted);
  font-size: 12px;
}

.project-mobile-card__identity span {
  margin-top: 3px;
}

.project-mobile-card__facts {
  display: grid;
  gap: 8px;
  margin: 0;
}

.project-mobile-card__facts div {
  display: grid;
  grid-template-columns: 64px minmax(0, 1fr);
  gap: 10px;
}

.project-mobile-card__facts dt,
.project-mobile-card__facts dd {
  margin: 0;
}

.project-mobile-card__facts dt {
  color: var(--ui-faint);
}

.project-mobile-card__facts dd {
  overflow: hidden;
  color: var(--ui-text-secondary);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.project-mobile-card__progress {
  gap: 10px;
  min-width: 0;
}

.project-mobile-card__progress .project-table-progress__track {
  flex: 1 1 auto;
  min-width: 0;
}

.project-mobile-card__progress strong {
  min-width: 38px;
  color: var(--ui-text-secondary);
  font-size: 13px;
  text-align: right;
}

.project-mobile-card__actions {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 8px;
  min-width: 0;
}

.project-mobile-card__actions > .el-button {
  width: 100%;
  min-width: 0;
  margin: 0;
}

.project-row-menu-button--mobile {
  width: 40px;
  height: 40px;
}

@media (max-width: 760px) {
  .page-hero .el-button {
    width: 100%;
    margin: 0;
  }

  .project-list-toolbar > :first-child,
  .project-list-toolbar .inline-actions {
    flex: 0 0 auto;
    width: 100%;
  }

  .project-list-toolbar > :first-child {
    max-width: none !important;
  }

  .project-list-toolbar .inline-actions {
    display: grid;
    grid-template-columns: minmax(0, 1fr) auto;
    align-items: center;
  }

  .project-list-toolbar .el-radio-group {
    min-width: 0;
    width: 100%;
  }

  .project-mobile-card__heading,
  .project-mobile-card__identity,
  .project-mobile-card__facts,
  .project-mobile-card__progress,
  .project-mobile-card__actions {
    max-width: 100%;
    min-width: 0;
  }

  .project-list-footer {
    align-items: flex-start;
    flex-direction: column;
  }

  .project-list-error {
    align-items: stretch;
    flex-direction: column;
  }

  .project-list-error .el-button {
    width: 100%;
  }
}
</style>
