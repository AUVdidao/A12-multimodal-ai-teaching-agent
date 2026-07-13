<template>
  <section class="page" v-loading="loading">
    <template v-if="workspace">
      <ProjectContextHeader :project="workspace.project" />
      <ProjectWorkspaceNav :project-id="workspace.project.id" />

      <div class="step-strip">
        <span class="is-active">上传资料</span>
        <span>标记用途</span>
        <span>解析摘要</span>
        <span>知识检索</span>
        <span>意图确认</span>
      </div>

      <div class="material-layout">
        <section class="grid material-main">
          <section class="panel">
            <div class="panel__header">
              <div>
                <h3>上传资料</h3>
                <p>支持 {{ workspace.uploadPolicy.supportedExtensions.join('、') }}，单个文件最大 {{ workspace.uploadPolicy.maxFileSizeMb }}MB。</p>
              </div>
              <span v-if="!workspace.uploadPolicy.uploadEnabled" class="tag-soft warning">需先确认需求摘要</span>
            </div>
            <UiUploadDropzone
              :disabled="!workspace.uploadPolicy.uploadEnabled || uploading"
              :accept="acceptTypes"
              :title="uploading ? `正在上传 ${uploadProgress}%` : '拖拽文件到此处，或点击上传'"
              :description="!workspace.uploadPolicy.uploadEnabled ? '确认需求摘要后开放资料上传' : '上传后可标记用途、执行原型解析并建立知识索引'"
              @select="handleUpload"
            />
            <el-progress v-if="uploading" :percentage="uploadProgress" style="margin-top: 12px" />
          </section>

          <section class="panel">
            <div class="panel__header">
              <h3>资料列表（{{ workspace.statistics.total }}）</h3>
              <el-button :disabled="workspace.statistics.indexed === 0" @click="router.push(`/projects/${projectId}/knowledge`)">进入知识检索</el-button>
            </div>
            <el-table :data="workspace.materials" highlight-current-row @current-change="selectMaterial">
              <el-table-column label="文件名称" min-width="230">
                <template #default="{ row }">
                  <button class="material-name" type="button" @click="selectedMaterial = row">{{ row.originalFilename }}</button>
                </template>
              </el-table-column>
              <el-table-column prop="fileType" label="类型" width="78" />
              <el-table-column label="大小" width="96"><template #default="{ row }">{{ formatBytes(row.fileSize) }}</template></el-table-column>
              <el-table-column label="用途" min-width="180">
                <template #default="{ row }">
                  <el-select
                    :model-value="row.usageTypes"
                    multiple
                    collapse-tags
                    placeholder="选择用途"
                    @change="saveUsage(row, $event)"
                  >
                    <el-option v-for="option in workspace.purposeOptions" :key="option.code" :label="option.label" :value="option.code" />
                  </el-select>
                </template>
              </el-table-column>
              <el-table-column label="解析状态" width="108">
                <template #default="{ row }"><span :class="['tag-soft', parseStatusClass(row.parseStatus)]">{{ parseStatusLabel(row.parseStatus) }}</span></template>
              </el-table-column>
              <el-table-column label="上传时间" width="110"><template #default="{ row }">{{ formatDateTime(row.uploadedAt) }}</template></el-table-column>
              <el-table-column label="操作" width="190" fixed="right">
                <template #default="{ row }">
                  <el-button
                    text
                    type="primary"
                    :disabled="row.parseStatus === 'PROCESSING' || row.parseStatus === 'SUCCEEDED'"
                    @click="parseMaterial(row)"
                  >
                    {{ parseActionLabel(row.parseStatus) }}
                  </el-button>
                  <el-button text type="primary" :disabled="row.parseStatus !== 'SUCCEEDED'" @click="indexSelected(row)">索引</el-button>
                  <el-button text @click="download(row)">下载</el-button>
                </template>
              </el-table-column>
            </el-table>
            <el-empty v-if="workspace.materials.length === 0" description="暂无资料，上传后将显示在这里" :image-size="72" />
          </section>
        </section>

        <aside class="grid material-aside">
          <section class="panel">
            <div class="panel__header"><h3>用途标签说明</h3><span class="tag-soft">后端可选项</span></div>
            <div v-for="purpose in workspace.purposeOptions" :key="purpose.code" class="purpose-row">
              <span class="tag-soft">{{ purpose.label }}</span>
              <p>{{ purpose.description }}</p>
            </div>
          </section>

          <section class="panel parse-preview">
            <div class="panel__header">
              <h3>解析摘要预览</h3>
              <span v-if="selectedMaterial" :class="['tag-soft', parseStatusClass(selectedMaterial.parseStatus)]">{{ parseStatusLabel(selectedMaterial.parseStatus) }}</span>
            </div>
            <template v-if="selectedMaterial">
              <strong>{{ selectedMaterial.originalFilename }}</strong>
              <p v-if="selectedMaterial.parsePreview?.summary">{{ selectedMaterial.parsePreview.summary }}</p>
              <p v-else class="muted">{{ selectedMaterial.parsePreview?.failureReason || '该资料尚无解析摘要。' }}</p>
              <h3>知识点提炼</h3>
              <div class="inline-actions preview-tags">
                <span v-for="tag in selectedMaterial.parsePreview?.keywords || []" :key="tag" class="tag-soft">{{ tag }}</span>
              </div>
              <h3>适用场景</h3>
              <div class="inline-actions preview-tags">
                <span v-for="stage in selectedMaterial.parsePreview?.applicableTeachingStages || []" :key="stage" class="tag-soft info">{{ stage }}</span>
              </div>
              <div class="page-actions">
                <el-button :disabled="selectedMaterial.parseStatus !== 'SUCCEEDED'" @click="indexSelected(selectedMaterial)">建立知识索引</el-button>
                <el-button type="primary" :disabled="workspace.statistics.indexed === 0" @click="router.push(`/projects/${projectId}/knowledge`)">查看知识检索</el-button>
              </div>
            </template>
            <el-empty v-else description="选择一份资料查看解析结果" :image-size="70" />
          </section>
        </aside>
      </div>
    </template>
  </section>
</template>

<script setup lang="ts">
import {
  downloadMaterialById,
  indexMaterial,
  retryMaterialParse,
  startMaterialParse,
  updateMaterialUsages,
  uploadMaterial,
  type MaterialParseStatus,
  type MaterialUsageType,
} from '@/api/materials';
import { getMaterialWorkspace, type MaterialWorkspace, type MaterialWorkspaceItem } from '@/api/workspace';
import ProjectContextHeader from '@/components/ProjectContextHeader.vue';
import ProjectWorkspaceNav from '@/components/ProjectWorkspaceNav.vue';
import UiUploadDropzone from '@/components/ui/UiUploadDropzone.vue';
import { formatBytes, formatDateTime } from '@/utils/presentation';
import { ElMessage } from 'element-plus';
import { computed, onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';

const route = useRoute();
const router = useRouter();
const projectId = computed(() => Number(route.params.projectId));
const workspace = ref<MaterialWorkspace>();
const selectedMaterial = ref<MaterialWorkspaceItem>();
const loading = ref(true);
const uploading = ref(false);
const uploadProgress = ref(0);
const acceptTypes = computed(() => workspace.value?.uploadPolicy.supportedExtensions.map((item) => `.${item.toLowerCase()}`).join(',') || '');

async function loadWorkspace(preferredId?: number) {
  loading.value = true;
  try {
    workspace.value = await getMaterialWorkspace(projectId.value);
    const selectedId = preferredId || selectedMaterial.value?.id;
    selectedMaterial.value = workspace.value.materials.find((item) => item.id === selectedId) || workspace.value.materials[0];
  } finally {
    loading.value = false;
  }
}

function selectMaterial(row?: MaterialWorkspaceItem) {
  if (row) selectedMaterial.value = row;
}

async function handleUpload(file: File) {
  if (!workspace.value) return;
  if (file.size > workspace.value.uploadPolicy.maxFileSizeBytes) {
    ElMessage.error(`文件不能超过 ${workspace.value.uploadPolicy.maxFileSizeMb}MB`);
    return;
  }
  uploading.value = true;
  uploadProgress.value = 0;
  try {
    const uploaded = await uploadMaterial(projectId.value, file, '', (value) => { uploadProgress.value = value; });
    await loadWorkspace(uploaded.id);
    ElMessage.success('资料上传成功');
  } finally {
    uploading.value = false;
  }
}

async function saveUsage(row: MaterialWorkspaceItem, values: MaterialUsageType[]) {
  await updateMaterialUsages(projectId.value, row.id, values, row.usageNote || '');
  await loadWorkspace(row.id);
  ElMessage.success('资料用途已更新');
}

async function parseMaterial(row: MaterialWorkspaceItem) {
  if (row.parseStatus === 'PROCESSING' || row.parseStatus === 'SUCCEEDED') return;
  if (row.parseStatus === 'FAILED') await retryMaterialParse(projectId.value, row.id);
  else await startMaterialParse(projectId.value, row.id);
  await loadWorkspace(row.id);
  ElMessage.success('资料解析已完成');
}

async function indexSelected(row: MaterialWorkspaceItem) {
  await indexMaterial(projectId.value, row.id);
  await loadWorkspace(row.id);
  ElMessage.success('知识索引已建立');
}

async function download(row: MaterialWorkspaceItem) {
  await downloadMaterialById(projectId.value, row.id, row.originalFilename);
}

function parseStatusLabel(status: MaterialParseStatus) {
  const labels: Record<MaterialParseStatus, string> = {
    NOT_STARTED: '待解析',
    PROCESSING: '解析中',
    SUCCEEDED: '解析完成',
    FAILED: '解析失败',
  };
  return labels[status];
}

function parseActionLabel(status: MaterialParseStatus) {
  const labels: Record<MaterialParseStatus, string> = {
    NOT_STARTED: '解析',
    PROCESSING: '解析中',
    SUCCEEDED: '已解析',
    FAILED: '重试',
  };
  return labels[status];
}

function parseStatusClass(status: MaterialParseStatus) {
  if (status === 'SUCCEEDED') return 'success';
  if (status === 'PROCESSING') return 'info';
  if (status === 'FAILED') return 'danger';
  return 'warning';
}

onMounted(loadWorkspace);
</script>

<style scoped>
.material-layout {
  display: grid;
  grid-template-columns: minmax(0, 1.35fr) minmax(360px, 0.75fr);
  gap: 16px;
}

.material-main,
.material-aside {
  min-width: 0;
  align-content: start;
}

.material-main > .panel,
.material-aside > .panel {
  min-width: 0;
  overflow: hidden;
}

.material-name {
  max-width: 100%;
  padding: 0;
  overflow: hidden;
  border: 0;
  background: transparent;
  color: #2e3952;
  cursor: pointer;
  font: inherit;
  text-align: left;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.material-name:hover {
  color: var(--ui-primary);
}

.purpose-row {
  display: grid;
  grid-template-columns: 110px minmax(0, 1fr);
  align-items: center;
  gap: 12px;
  min-height: 48px;
  border-bottom: 1px solid var(--ui-border);
}

.purpose-row p {
  margin: 0;
  color: var(--ui-muted);
  font-size: 12px;
}

.parse-preview h3 {
  margin-top: 18px;
}

.parse-preview > p {
  line-height: 1.7;
}

.preview-tags {
  flex-wrap: wrap;
}

@media (max-width: 1120px) {
  .material-layout {
    grid-template-columns: 1fr;
  }
}
</style>
