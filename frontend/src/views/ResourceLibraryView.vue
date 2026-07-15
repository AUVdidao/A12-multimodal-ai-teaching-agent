<template>
  <section class="page resource-library-page">
    <header class="page-hero">
      <div>
        <h2>资源库</h2>
        <p>汇总当前教师项目中的真实参考资料，可按项目、文件类型和解析状态筛选。</p>
      </div>
      <el-button :loading="loading" @click="loadLibrary">刷新资料</el-button>
    </header>

    <StatePanel
      v-if="!canAccess"
      type="info"
      title="当前角色无权访问资源库"
      description="资源库仅对教师角色开放，请切换为教师角色后查看本人项目资料。"
    />
    <StatePanel v-else-if="loading" type="loading" title="正在汇总项目资料" description="正在读取本人项目及其资料记录。" />
    <StatePanel v-else-if="errorMessage" type="error" title="资源库读取失败" :description="errorMessage">
      <template #action><el-button type="primary" @click="loadLibrary">重新加载</el-button></template>
    </StatePanel>

    <template v-else>
      <section class="panel resource-library__filters">
        <el-select v-model="projectFilter" placeholder="全部项目" clearable>
          <el-option label="全部项目" value="ALL" />
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
        <el-input v-model="keyword" clearable placeholder="按文件名、说明或用途关键词筛选" />
      </section>

      <StatePanel
        v-if="materials.length === 0"
        type="empty"
        title="还没有可汇总的资料"
        description="请先在教学项目中上传参考资料。"
      />
      <StatePanel
        v-else-if="filteredMaterials.length === 0"
        type="empty"
        title="没有符合筛选条件的资料"
        description="请调整项目、类型、解析状态或关键词后重试。"
      />
      <section v-else class="panel resource-library__table">
        <div class="resource-library__summary">共 {{ filteredMaterials.length }} 份资料，来自 {{ projectCount }} 个项目</div>
        <el-table :data="filteredMaterials" table-layout="fixed">
          <el-table-column label="资料" min-width="240">
            <template #default="{ row }">
              <div class="resource-library__file">
                <strong>{{ row.originalFilename }}</strong>
                <span>{{ row.description || '未填写资料说明' }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="项目" min-width="190">
            <template #default="{ row }">
              <strong>{{ row.projectName }}</strong>
              <span class="muted">{{ row.courseName }}</span>
            </template>
          </el-table-column>
          <el-table-column label="类型" width="100"><template #default="{ row }"><span class="tag-soft">{{ row.fileType }}</span></template></el-table-column>
          <el-table-column label="解析状态" width="120"><template #default="{ row }"><span :class="['tag-soft', parseStatusClass(row.parseStatus)]">{{ parseStatusLabel(row.parseStatus) }}</span></template></el-table-column>
          <el-table-column label="文件大小" width="110"><template #default="{ row }">{{ formatBytes(row.fileSize) }}</template></el-table-column>
          <el-table-column label="操作" width="190" fixed="right">
            <template #default="{ row }">
              <el-button text type="primary" :loading="viewingId === row.id" @click="viewFile(row)">查看文件</el-button>
              <el-button text @click="openMaterials(row.projectId)">项目资料</el-button>
            </template>
          </el-table-column>
        </el-table>
      </section>
    </template>
  </section>
</template>

<script setup lang="ts">
import { downloadMaterial, listMaterials, type MaterialParseStatus, type MaterialRecord } from '@/api/materials';
import { listProjects, type TeachingProject } from '@/api/projects';
import StatePanel from '@/components/StatePanel.vue';
import { useAuthStore } from '@/stores/auth';
import { formatBytes } from '@/utils/presentation';
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
const viewingId = ref<number>();
const errorMessage = ref('');
const projectFilter = ref('ALL');
const typeFilter = ref('ALL');
const parseFilter = ref('ALL');
const keyword = ref('');
const parseStatuses: MaterialParseStatus[] = ['NOT_STARTED', 'PROCESSING', 'SUCCEEDED', 'FAILED'];
const canAccess = computed(() => auth.activeRole === 'TEACHER');
const fileTypes = computed(() => [...new Set(materials.value.map((item) => item.fileType))].sort());
const filteredMaterials = computed(() => {
  const normalizedKeyword = keyword.value.trim().toLocaleLowerCase();
  return materials.value.filter((item) => {
    const matchesProject = !projectFilter.value || projectFilter.value === 'ALL' || String(item.projectId) === projectFilter.value;
    const matchesType = !typeFilter.value || typeFilter.value === 'ALL' || item.fileType === typeFilter.value;
    const matchesStatus = !parseFilter.value || parseFilter.value === 'ALL' || item.parseStatus === parseFilter.value;
    const searchable = [item.originalFilename, item.description, item.usageNote, item.usageTypes.join(' ')].filter(Boolean).join(' ').toLocaleLowerCase();
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

async function viewFile(material: LibraryMaterial) {
  viewingId.value = material.id;
  try {
    await downloadMaterial(material.projectId, material);
  } catch (error) {
    ElMessage.error(resolveError(error, '文件读取失败，请稍后重试。'));
  } finally {
    viewingId.value = undefined;
  }
}

function openMaterials(projectId: number) {
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
.resource-library__filters { display: grid; grid-template-columns: minmax(180px, 0.9fr) minmax(130px, 0.55fr) minmax(150px, 0.7fr) minmax(240px, 1.2fr); gap: 12px; margin-bottom: 16px; }
.resource-library__table { overflow: hidden; }
.resource-library__summary { padding: 4px 2px 14px; color: var(--ui-muted); font-size: 13px; }
.resource-library__file { min-width: 0; }
.resource-library__file strong, .resource-library__file span { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.resource-library__file strong { color: var(--ui-text); }
.resource-library__file span { margin-top: 4px; color: var(--ui-muted); font-size: 12px; }
.resource-library__table :deep(.el-table .cell) { overflow: hidden; }
@media (max-width: 860px) { .resource-library__filters { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 560px) { .resource-library__filters { grid-template-columns: 1fr; } .resource-library__table { overflow-x: auto; } .resource-library__table :deep(.el-table) { min-width: 780px; } }
</style>
