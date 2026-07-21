<template>
  <section class="page resource-library-page">
    <header class="page-hero">
      <div>
        <h2>资料库</h2>
        <p>汇总当前教师项目中的真实参考资料，可按所属项目、文件类型和解析状态筛选。</p>
      </div>
      <el-button :loading="loading" @click="loadLibrary">刷新资料</el-button>
    </header>

    <StatePanel
      v-if="!canAccess"
      type="info"
      title="当前角色无权访问资料库"
      description="资料库仅对教师角色开放，请切换为教师角色后查看本人项目资料。"
    />
    <StatePanel v-else-if="loading" type="loading" title="正在汇总项目资料" description="正在读取本人项目及其资料记录。" />
    <StatePanel v-else-if="errorMessage" type="error" title="资料库读取失败" :description="errorMessage">
      <template #action><el-button type="primary" @click="loadLibrary">重新加载</el-button></template>
    </StatePanel>

    <template v-else>
      <section class="panel resource-library__filters" aria-label="资料筛选">
        <el-select v-model="projectFilter" placeholder="全部所属项目" clearable>
          <el-option label="全部所属项目" value="ALL" />
          <el-option v-for="project in projects" :key="project.id" :label="project.projectName" :value="String(project.id)" />
        </el-select>
        <el-select v-model="typeFilter" placeholder="全部类型" clearable>
          <el-option label="全部类型" value="ALL" />
          <el-option v-for="type in fileTypes" :key="type" :label="type" :value="type" />
        </el-select>
        <el-select v-model="parseFilter" placeholder="全部解析状态" clearable>
          <el-option label="全部解析状态" value="ALL" />
          <el-option v-for="status in parseStatuses" :key="status" :label="parseStatusLabel(status)" :value="status" />
        </el-select>
        <el-input v-model="keyword" clearable placeholder="按文件名、项目或资料说明筛选" />
      </section>

      <StatePanel
        v-if="materials.length === 0"
        type="empty"
        title="还没有可汇总的资料"
        description="请先进入教学项目上传参考资料。"
      />
      <StatePanel
        v-else-if="filteredMaterials.length === 0"
        type="empty"
        title="没有符合筛选条件的资料"
        description="请调整所属项目、类型、解析状态或关键词后重试。"
      />
      <section v-else class="panel resource-library__table">
        <div class="resource-library__summary">共 {{ filteredMaterials.length }} 份资料，来自 {{ projectCount }} 个项目</div>
        <el-table :data="filteredMaterials" table-layout="fixed">
          <el-table-column label="资料名称" min-width="270">
            <template #default="{ row }">
              <div class="resource-library__file">
                <el-tooltip :content="row.originalFilename" placement="top" :show-after="400">
                  <strong>{{ row.originalFilename }}</strong>
                </el-tooltip>
                <el-tooltip :content="row.description || '未填写资料说明'" placement="top" :show-after="400">
                  <span>{{ row.description || '未填写资料说明' }}</span>
                </el-tooltip>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="来源 / 所属项目" min-width="220">
            <template #default="{ row }">
              <div class="resource-library__source">
                <el-tooltip :content="row.projectName" placement="top" :show-after="400">
                  <strong>{{ row.projectName }}</strong>
                </el-tooltip>
                <el-tooltip :content="row.courseName" placement="top" :show-after="400">
                  <span>{{ row.courseName }}</span>
                </el-tooltip>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="类型" width="90">
            <template #default="{ row }"><span class="tag-soft">{{ row.fileType }}</span></template>
          </el-table-column>
          <el-table-column label="解析状态" width="116">
            <template #default="{ row }"><span :class="['tag-soft', parseStatusClass(row.parseStatus)]">{{ parseStatusLabel(row.parseStatus) }}</span></template>
          </el-table-column>
          <el-table-column label="文件大小" width="105">
            <template #default="{ row }">{{ formatBytes(row.fileSize) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="240">
            <template #default="{ row }">
              <div class="resource-library__actions">
                <el-button text type="primary" :icon="Download" :loading="downloadingId === row.id" @click="download(row)">
                  下载
                </el-button>
                <el-button v-if="row.parseStatus === 'SUCCEEDED'" text :icon="View" @click="openParseResult(row)">
                  查看解析结果
                </el-button>
                <el-button v-else text :icon="FolderOpened" @click="openMaterials(row.projectId)">
                  进入所属项目
                </el-button>
              </div>
            </template>
          </el-table-column>
        </el-table>
      </section>
    </template>

    <el-dialog v-model="parseDialogVisible" width="min(640px, calc(100vw - 32px))" destroy-on-close>
      <template #header>
        <div class="parse-dialog__header">
          <span>资料解析结果</span>
          <el-tooltip v-if="selectedMaterial" :content="selectedMaterial.originalFilename" placement="top">
            <strong>{{ selectedMaterial.originalFilename }}</strong>
          </el-tooltip>
        </div>
      </template>

      <StatePanel v-if="parseResultLoading" type="loading" title="正在读取解析结果" description="正在加载该资料的真实解析记录。" />
      <StatePanel v-else-if="parseResultError" type="error" title="解析结果读取失败" :description="parseResultError">
        <template #action><el-button type="primary" @click="loadSelectedParseResult">重新加载</el-button></template>
      </StatePanel>
      <template v-else-if="parseResult">
        <div class="parse-dialog__status">
          <span>解析状态</span>
          <strong :class="['tag-soft', parseStatusClass(parseResult.parseStatus)]">{{ parseStatusLabel(parseResult.parseStatus) }}</strong>
        </div>
        <section class="parse-dialog__section">
          <h4>解析摘要</h4>
          <p>{{ parseResult.summary || '该资料暂未生成解析摘要。' }}</p>
        </section>
        <section v-if="parseResult.keywords.length" class="parse-dialog__section">
          <h4>关键词</h4>
          <div class="parse-dialog__tags">
            <el-tag v-for="item in parseResult.keywords" :key="item" effect="plain">{{ item }}</el-tag>
          </div>
        </section>
        <section v-if="parseResult.applicableTeachingStages.length" class="parse-dialog__section">
          <h4>适用教学环节</h4>
          <div class="parse-dialog__tags">
            <el-tag v-for="item in parseResult.applicableTeachingStages" :key="item" type="success" effect="plain">{{ item }}</el-tag>
          </div>
        </section>
      </template>

      <template #footer>
        <el-button @click="parseDialogVisible = false">关闭</el-button>
        <el-button v-if="selectedMaterial" type="primary" @click="openMaterials(selectedMaterial.projectId)">进入所属项目</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<script setup lang="ts">
import {
  downloadMaterial,
  getMaterialParseResult,
  listMaterials,
  type MaterialParseResult,
  type MaterialParseStatus,
  type MaterialRecord,
} from '@/api/materials';
import { listProjects, type TeachingProject } from '@/api/projects';
import StatePanel from '@/components/StatePanel.vue';
import { useAuthStore } from '@/stores/auth';
import { formatBytes } from '@/utils/presentation';
import { Download, FolderOpened, View } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import { computed, ref, watch } from 'vue';
import { useRouter } from 'vue-router';

interface LibraryMaterial extends MaterialRecord {
  projectName: string;
  courseName: string;
}

const auth = useAuthStore();
const router = useRouter();
const projects = ref<TeachingProject[]>([]);
const materials = ref<LibraryMaterial[]>([]);
const loading = ref(false);
const downloadingId = ref<number>();
const errorMessage = ref('');
const projectFilter = ref('ALL');
const typeFilter = ref('ALL');
const parseFilter = ref('ALL');
const keyword = ref('');
const parseDialogVisible = ref(false);
const parseResultLoading = ref(false);
const parseResultError = ref('');
const selectedMaterial = ref<LibraryMaterial>();
const parseResult = ref<MaterialParseResult>();
const parseStatuses: MaterialParseStatus[] = ['NOT_STARTED', 'PROCESSING', 'SUCCEEDED', 'FAILED'];
const canAccess = computed(() => auth.activeRole === 'TEACHER');
const fileTypes = computed(() => [...new Set(materials.value.map((item) => item.fileType))].sort());
const filteredMaterials = computed(() => {
  const normalizedKeyword = keyword.value.trim().toLocaleLowerCase();
  return materials.value.filter((item) => {
    const matchesProject = !projectFilter.value || projectFilter.value === 'ALL' || String(item.projectId) === projectFilter.value;
    const matchesType = !typeFilter.value || typeFilter.value === 'ALL' || item.fileType === typeFilter.value;
    const matchesStatus = !parseFilter.value || parseFilter.value === 'ALL' || item.parseStatus === parseFilter.value;
    const searchable = [
      item.originalFilename,
      item.projectName,
      item.courseName,
      item.description,
      item.usageNote,
      item.usageTypes.join(' '),
    ].filter(Boolean).join(' ').toLocaleLowerCase();
    return matchesProject && matchesType && matchesStatus && (!normalizedKeyword || searchable.includes(normalizedKeyword));
  });
});
const projectCount = computed(() => new Set(filteredMaterials.value.map((item) => item.projectId)).size);

watch(canAccess, (allowed) => {
  if (allowed) loadLibrary();
  else {
    projects.value = [];
    materials.value = [];
  }
}, { immediate: true });

async function loadLibrary() {
  if (!canAccess.value) return;
  loading.value = true;
  errorMessage.value = '';
  try {
    const ownedProjects = await listProjects();
    const materialLists = await Promise.all(ownedProjects.map((project) => listMaterials(project.id)));
    projects.value = ownedProjects;
    materials.value = materialLists.flatMap((list, index) => list.map((material) => ({
      ...material,
      projectName: ownedProjects[index].projectName,
      courseName: ownedProjects[index].courseName,
    })));
  } catch (error) {
    errorMessage.value = resolveError(error, '暂时无法读取本人项目资料，请稍后重试。');
  } finally {
    loading.value = false;
  }
}

async function download(material: LibraryMaterial) {
  downloadingId.value = material.id;
  try {
    await downloadMaterial(material.projectId, material);
    ElMessage.success('资料已开始下载');
  } catch (error) {
    ElMessage.error(resolveError(error, '资料下载失败，请稍后重试。'));
  } finally {
    downloadingId.value = undefined;
  }
}

function openParseResult(material: LibraryMaterial) {
  selectedMaterial.value = material;
  parseDialogVisible.value = true;
  loadSelectedParseResult();
}

async function loadSelectedParseResult() {
  if (!selectedMaterial.value) return;
  parseResultLoading.value = true;
  parseResultError.value = '';
  parseResult.value = undefined;
  try {
    parseResult.value = await getMaterialParseResult(selectedMaterial.value.projectId, selectedMaterial.value.id);
  } catch (error) {
    parseResultError.value = resolveError(error, '暂时无法读取这份资料的解析结果，请稍后重试。');
  } finally {
    parseResultLoading.value = false;
  }
}

function openMaterials(projectId: number) {
  parseDialogVisible.value = false;
  router.push({ name: 'project-materials', params: { projectId } });
}

function parseStatusLabel(status: MaterialParseStatus) {
  return { NOT_STARTED: '待解析', PROCESSING: '解析中', SUCCEEDED: '解析完成', FAILED: '解析失败' }[status];
}

function parseStatusClass(status: MaterialParseStatus) {
  if (status === 'SUCCEEDED') return 'success';
  if (status === 'PROCESSING') return 'info';
  if (status === 'FAILED') return 'danger';
  return 'warning';
}

function resolveError(error: unknown, fallback: string) {
  const message = (error as { response?: { data?: { message?: string } } }).response?.data?.message;
  return message && !/(Exception|java\.|Axios)/i.test(message) ? message : fallback;
}
</script>

<style scoped>
.resource-library__filters {
  display: grid;
  grid-template-columns: minmax(180px, 0.9fr) minmax(130px, 0.55fr) minmax(150px, 0.7fr) minmax(240px, 1.2fr);
  gap: 12px;
  margin-bottom: 16px;
}

.resource-library__table {
  overflow-x: auto;
}

.resource-library__table :deep(.el-table) {
  min-width: 1040px;
}

.resource-library__summary {
  padding: 4px 2px 14px;
  color: var(--ui-muted);
  font-size: 13px;
}

.resource-library__file,
.resource-library__source {
  min-width: 0;
}

.resource-library__file strong,
.resource-library__file span,
.resource-library__source strong,
.resource-library__source span {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.resource-library__file strong,
.resource-library__source strong {
  color: var(--ui-text);
}

.resource-library__file span,
.resource-library__source span {
  margin-top: 4px;
  color: var(--ui-muted);
  font-size: 12px;
}

.resource-library__actions {
  display: flex;
  align-items: center;
  gap: 4px;
  white-space: nowrap;
}

.resource-library__actions .el-button + .el-button {
  margin-left: 0;
}

.parse-dialog__header span,
.parse-dialog__header strong {
  display: block;
}

.parse-dialog__header span {
  color: var(--ui-muted);
  font-size: 12px;
}

.parse-dialog__header strong {
  max-width: 520px;
  margin-top: 4px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--ui-text);
}

.parse-dialog__status {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding-bottom: 14px;
  border-bottom: 1px solid var(--ui-border);
  color: var(--ui-muted);
  font-size: 12px;
}

.parse-dialog__section {
  margin-top: 18px;
}

.parse-dialog__section h4,
.parse-dialog__section p {
  margin: 0;
}

.parse-dialog__section p {
  margin-top: 8px;
  color: var(--ui-muted);
  line-height: 1.7;
}

.parse-dialog__tags {
  display: flex;
  flex-wrap: wrap;
  gap: 7px;
  margin-top: 9px;
}

@media (max-width: 860px) {
  .resource-library__filters {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 560px) {
  .resource-library__filters {
    grid-template-columns: 1fr;
  }
}
</style>
