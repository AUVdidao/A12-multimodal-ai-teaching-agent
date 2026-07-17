<template>
  <section class="export-page">
    <StatePanel
      v-if="loading"
      type="loading"
      title="正在读取导出文件"
      description="正在检查当前项目的可导出成果。"
    />

    <StatePanel
      v-else-if="loadError"
      type="error"
      title="导出页面加载失败"
      :description="loadError"
    >
      <template #action>
        <el-button type="primary" :icon="Refresh" @click="loadExports">重新加载</el-button>
      </template>
    </StatePanel>

    <template v-else>
      <div v-if="downloadError" class="export-notice">
        <el-alert :title="downloadError" type="error" show-icon :closable="false" />
      </div>

      <section v-if="formats.length" class="export-grid" aria-label="可导出格式">
        <article
          v-for="option in formats"
          :key="option.format"
          :class="['export-format', `export-format--${option.format.toLowerCase()}`]"
        >
          <header class="export-format__header">
            <span class="export-format__icon">
              <el-icon><component :is="formatIcon(option.format)" /></el-icon>
            </span>
            <div>
              <small>{{ option.extension.toUpperCase() }}</small>
              <h3>{{ option.label }}</h3>
            </div>
            <UiStatusPill
              :label="option.versionNumber ? `v${option.versionNumber}` : '当前版本'"
              tone="green"
            />
          </header>

          <p class="export-format__description">{{ option.description }}</p>

          <dl class="export-format__meta">
            <div>
              <dt>文件名</dt>
              <dd>{{ option.filename }}</dd>
            </div>
            <div>
              <dt>成果编号</dt>
              <dd>#{{ option.artifactId }}</dd>
            </div>
          </dl>

          <el-button
            type="primary"
            :icon="Download"
            :loading="downloadingFormat === option.format"
            :disabled="Boolean(downloadingFormat && downloadingFormat !== option.format)"
            @click="startDownload(option)"
          >
            下载 {{ option.format }}
          </el-button>
        </article>
      </section>

      <section v-else class="export-empty">
        <span><el-icon><Files /></el-icon></span>
        <h3>尚无可导出的成果</h3>
        <p>完成内容生成后，PPTX 课件和 DOCX 教案会显示在这里。</p>
      </section>
    </template>
  </section>
</template>

<script setup lang="ts">
import {
  downloadProjectExport,
  getProjectExportCatalog,
  type ExportFormat,
  type ExportOption,
} from '@/api/exports';
import StatePanel from '@/components/StatePanel.vue';
import UiStatusPill from '@/components/ui/UiStatusPill.vue';
import { DataBoard, Document, Download, Files, Refresh } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import { computed, onMounted, ref } from 'vue';
import { useRoute } from 'vue-router';

const route = useRoute();
const projectId = computed(() => Number(route.params.projectId));
const formats = ref<ExportOption[]>([]);
const loading = ref(true);
const loadError = ref('');
const downloadError = ref('');
const downloadingFormat = ref<ExportFormat>();

function formatIcon(format: ExportFormat) {
  return format === 'PPTX' ? DataBoard : Document;
}

async function loadExports() {
  loading.value = true;
  loadError.value = '';
  downloadError.value = '';
  try {
    const catalog = await getProjectExportCatalog(projectId.value);
    formats.value = catalog.formats || [];
  } catch (error) {
    formats.value = [];
    loadError.value = resolveError(error, '导出信息读取失败，请稍后重试。');
  } finally {
    loading.value = false;
  }
}

async function startDownload(option: ExportOption) {
  downloadingFormat.value = option.format;
  downloadError.value = '';
  try {
    await downloadProjectExport(projectId.value, option);
    ElMessage.success(`${option.format} 文件已开始下载`);
  } catch (error) {
    downloadError.value = resolveError(error, `${option.format} 文件生成失败，请稍后重试。`);
  } finally {
    downloadingFormat.value = undefined;
  }
}

function resolveError(error: unknown, fallback: string) {
  const response = (error as { response?: { status?: number; data?: { message?: string } } }).response;
  if (response?.status === 403) return '当前账号无权导出此项目的成果。';
  if (response?.status === 404) return '项目或可导出成果不存在。';
  const message = response?.data?.message;
  return message && !/(Exception|java\.|Axios)/i.test(message) ? message : fallback;
}

onMounted(loadExports);
</script>

<style scoped>
.export-page {
  width: 100%;
  max-width: 1440px;
  margin: 0 auto;
}

.export-format__header,
.export-notice {
  display: flex;
  align-items: center;
}

.export-empty > span {
  display: grid;
  width: 48px;
  height: 48px;
  flex: 0 0 48px;
  place-items: center;
  border-radius: 8px;
  background: var(--ui-primary-soft);
  color: var(--ui-primary);
  font-size: 24px;
}

.export-format h3,
.export-format p,
.export-empty h3,
.export-empty p {
  margin: 0;
}

.export-notice {
  margin-bottom: 14px;
}

.export-notice :deep(.el-alert) {
  min-width: 0;
  flex: 1;
}

.export-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
}

.export-format {
  display: grid;
  min-width: 0;
  gap: 18px;
  padding: 20px;
  border: 1px solid var(--ui-border);
  border-top: 3px solid var(--ui-primary);
  border-radius: 8px;
  background: var(--ui-panel);
  box-shadow: var(--shadow-panel);
}

.export-format--docx {
  border-top-color: var(--ui-success);
}

.export-format__header {
  min-width: 0;
  gap: 12px;
}

.export-format__header > div {
  min-width: 0;
  flex: 1;
}

.export-format__icon {
  display: grid;
  width: 44px;
  height: 44px;
  flex: 0 0 44px;
  place-items: center;
  border-radius: 8px;
  background: #edf4ff;
  color: var(--ui-info);
  font-size: 22px;
}

.export-format--docx .export-format__icon {
  background: #e9f8f0;
  color: var(--ui-success);
}

.export-format__header small {
  color: var(--ui-faint);
  font-size: 10px;
  font-weight: 700;
}

.export-format h3 {
  margin-top: 2px;
  font-size: 17px;
  overflow-wrap: anywhere;
}

.export-format__description {
  min-height: 22px;
  color: var(--ui-muted);
  font-size: 13px;
  line-height: 1.6;
}

.export-format__meta {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 14px;
  margin: 0;
  padding: 13px 14px;
  border-radius: 6px;
  background: var(--ui-panel-soft);
}

.export-format__meta > div {
  min-width: 0;
}

.export-format__meta dt,
.export-format__meta dd {
  margin: 0;
}

.export-format__meta dt {
  color: var(--ui-faint);
  font-size: 10px;
}

.export-format__meta dd {
  margin-top: 3px;
  color: var(--ui-text);
  font-size: 12px;
  overflow-wrap: anywhere;
}

.export-format__meta > div:last-child dd {
  white-space: nowrap;
}

.export-format > .el-button {
  width: 100%;
  margin-top: auto;
}

.export-empty {
  display: grid;
  min-height: 390px;
  align-content: center;
  justify-items: center;
  padding: 32px;
  border-top: 1px solid var(--ui-border);
  text-align: center;
}

.export-empty > span {
  width: 64px;
  height: 64px;
  font-size: 28px;
}

.export-empty h3 {
  margin-top: 16px;
  font-size: 18px;
}

.export-empty p {
  margin-top: 6px;
  color: var(--ui-muted);
}

@media (max-width: 760px) {
  .export-grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 480px) {
  .export-format {
    padding: 16px;
  }

  .export-format__header {
    align-items: flex-start;
  }

  .export-format__meta {
    grid-template-columns: 1fr;
  }
}
</style>
