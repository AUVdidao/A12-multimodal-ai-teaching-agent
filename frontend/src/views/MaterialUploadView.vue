<template>
  <section class="page material-page">
    <PageHeader eyebrow="M2 · 资料增强" title="参考资料与原型解析" description="上传真实参考文件，标记教学用途，再生成可解释的演示解析结果。" :project-label="projectLabel">
      <template #actions><el-button :icon="Refresh" :loading="loading" @click="loadWorkspace">刷新状态</el-button></template>
    </PageHeader>

    <M2ProgressSteps v-if="projectId" :current-step="currentStep" :project-id="projectId" :has-materials="hasMaterials" :has-usages="hasUsages" :has-parsed="hasParsed" :has-knowledge="hasKnowledge" :intent-confirmed="intentConfirmed" />

    <StatePanel v-if="!projectId" type="error" title="没有可用的教学项目" description="请从项目列表进入资料增强流程。"><template #action><el-button type="primary" @click="router.push('/projects')">返回项目列表</el-button></template></StatePanel>
    <StatePanel v-else-if="loading && !summaryChecked" type="loading" title="正在读取 M2 工作区" description="核对需求摘要、资料和知识片段状态。" />
    <StatePanel v-else-if="prerequisiteError" type="error" title="M1 尚未完成" :description="prerequisiteError"><template #action><el-button type="primary" @click="router.push(`/projects/${projectId}/requirement-summary`)">返回需求摘要</el-button></template></StatePanel>

    <template v-else>
      <section class="surface-panel upload-workspace">
        <div class="upload-workspace__copy"><span>SECURE LOCAL UPLOAD</span><h2>添加一份教学参考资料</h2><p>支持 PDF、DOCX、PPTX、PNG、JPG、JPEG，单文件不超过 20 MB。文件使用安全随机名保存在项目隔离目录。</p></div>
        <div :class="['drop-zone', { 'is-dragging': dragging }]" role="button" tabindex="0" @click="fileInput?.click()" @keydown.enter="fileInput?.click()" @dragenter.prevent="dragging = true" @dragover.prevent @dragleave.prevent="dragging = false" @drop.prevent="onDrop">
          <input ref="fileInput" class="sr-only" type="file" accept=".pdf,.docx,.pptx,.png,.jpg,.jpeg" @change="onFileChange" />
          <el-icon><UploadFilled /></el-icon><strong>{{ selectedFile ? selectedFile.name : '点击选择或拖拽文件到这里' }}</strong><small>{{ selectedFile ? formatFileSize(selectedFile.size) : '选择后不会自动上传，请再次确认' }}</small>
        </div>
        <el-input v-model="uploadDescription" maxlength="300" placeholder="可选：补充资料说明，例如“第二章教材例题”" />
        <el-progress v-if="uploading" :percentage="uploadProgress" :stroke-width="8" />
        <div class="upload-workspace__actions"><el-button v-if="selectedFile" @click="clearSelection">取消选择</el-button><el-button type="primary" :icon="Upload" :loading="uploading" :disabled="!selectedFile" @click="confirmUpload">确认上传</el-button></div>
        <el-alert v-if="errorMessage" :title="errorMessage" type="error" show-icon :closable="false" />
      </section>

      <section class="material-list-section">
        <div class="section-title"><div><span>项目资料 {{ materials.length }} 份</span><h2>用途绑定与原型解析</h2></div><p>资料用途和解析结果会在刷新后恢复。</p></div>
        <StatePanel v-if="loading && materials.length === 0" type="loading" title="正在读取资料" />
        <StatePanel v-else-if="materials.length === 0" type="empty" title="还没有参考资料" description="先上传一份非敏感教学资料，再标记它在本课中的用途。" />
        <div v-else class="material-list">
          <MaterialCard v-for="material in materials" :key="material.id" :material="material" :parse-result="parseResults[material.id]" :usage-types="usageDrafts[material.id]?.types || []" :usage-note="usageDrafts[material.id]?.note || ''" :saving-usage="savingUsageId === material.id" :parsing="parsingId === material.id" @update:usage-types="setUsageTypes(material.id, $event)" @update:usage-note="setUsageNote(material.id, $event)" @save-usages="saveUsages(material)" @parse="parseMaterial(material, false)" @retry="parseMaterial(material, true)" @download="handleDownload(material)" />
        </div>
      </section>

      <PrimaryActionBar>
        <template #info>{{ hasParsed ? '已有成功解析资料，可以进入本地知识检索。' : '至少完成一份资料的用途绑定与原型解析后进入下一步。' }}</template>
        <template #secondary><el-button @click="router.push(`/projects/${projectId}/requirement-summary`)">返回需求摘要</el-button></template>
        <el-button type="primary" :disabled="!hasParsed" @click="router.push(`/projects/${projectId}/knowledge`)">下一步：本地知识检索</el-button>
      </PrimaryActionBar>
    </template>
  </section>
</template>

<script setup lang="ts">
import { getKnowledgeOverview } from '@/api/knowledge';
import { downloadMaterial, getMaterialParseResult, listMaterials, retryMaterialParse, startMaterialParse, updateMaterialUsages, uploadMaterial, type MaterialRecord, type MaterialUsageType, type MaterialParseResult } from '@/api/materials';
import { getProject } from '@/api/projects';
import { getLatestRequirementSummary, type RequirementSummary } from '@/api/requirementSummaries';
import { getLatestTeachingIntent } from '@/api/teachingIntents';
import M2ProgressSteps from '@/components/M2ProgressSteps.vue';
import MaterialCard from '@/components/MaterialCard.vue';
import PageHeader from '@/components/PageHeader.vue';
import PrimaryActionBar from '@/components/PrimaryActionBar.vue';
import StatePanel from '@/components/StatePanel.vue';
import { formatFileSize } from '@/utils/materialLabels';
import { Refresh, Upload, UploadFilled } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { computed, onMounted, reactive, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';

interface UsageDraft { types: MaterialUsageType[]; note: string }
const route = useRoute();
const router = useRouter();
const projectId = computed(() => { const value = Number(route.params.projectId); return Number.isInteger(value) && value > 0 ? value : null; });
const materials = ref<MaterialRecord[]>([]);
const parseResults = reactive<Record<number, MaterialParseResult>>({});
const usageDrafts = reactive<Record<number, UsageDraft>>({});
const summary = ref<RequirementSummary | null>(null);
const projectLabel = ref<string>();
const loading = ref(false);
const summaryChecked = ref(false);
const prerequisiteError = ref('');
const errorMessage = ref('');
const selectedFile = ref<File | null>(null);
const uploadDescription = ref('');
const uploading = ref(false);
const uploadProgress = ref(0);
const dragging = ref(false);
const fileInput = ref<HTMLInputElement>();
const savingUsageId = ref<number | null>(null);
const parsingId = ref<number | null>(null);
const knowledgeCount = ref(0);
const intentConfirmed = ref(false);
const hasMaterials = computed(() => materials.value.length > 0);
const hasUsages = computed(() => hasMaterials.value && materials.value.every((item) => item.usageTypes.length > 0));
const hasParsed = computed(() => materials.value.some((item) => item.parseStatus === 'SUCCEEDED'));
const hasKnowledge = computed(() => knowledgeCount.value > 0);
const currentStep = computed(() => hasParsed.value ? 2 : hasUsages.value ? 1 : 0);

onMounted(loadWorkspace);

async function loadWorkspace() {
  if (!projectId.value) return;
  loading.value = true; errorMessage.value = ''; prerequisiteError.value = '';
  try {
    const [project, latestSummary] = await Promise.all([getProject(projectId.value), getLatestRequirementSummary(projectId.value)]);
    projectLabel.value = project.projectName;
    summary.value = latestSummary;
    summaryChecked.value = true;
    if (latestSummary?.status !== 'CONFIRMED') { prerequisiteError.value = '请先确认 M1 教学需求摘要，再上传增强资料。'; return; }
    const [materialList, overview, intent] = await Promise.all([listMaterials(projectId.value), getKnowledgeOverview(projectId.value), getLatestTeachingIntent(projectId.value)]);
    materials.value = materialList;
    knowledgeCount.value = overview.chunkCount;
    intentConfirmed.value = intent?.status === 'CONFIRMED';
    materialList.forEach((item) => { usageDrafts[item.id] = { types: [...item.usageTypes], note: item.usageNote || '' }; });
    const results = await Promise.all(materialList.map((item) => getMaterialParseResult(projectId.value!, item.id)));
    results.forEach((result) => { parseResults[result.materialId] = result; });
  } catch (error) { errorMessage.value = resolveError(error, 'M2 工作区读取失败，请稍后重试。'); }
  finally { loading.value = false; summaryChecked.value = true; }
}

function onFileChange(event: Event) { const input = event.target as HTMLInputElement; chooseFile(input.files?.[0]); }
function onDrop(event: DragEvent) { dragging.value = false; chooseFile(event.dataTransfer?.files?.[0]); }
function chooseFile(file?: File) {
  if (!file) return;
  const error = validateFile(file);
  if (error) { ElMessage.error(error); clearSelection(); return; }
  selectedFile.value = file;
}
function validateFile(file: File) {
  const extension = file.name.split('.').pop()?.toLowerCase();
  const allowed: Record<string, string[]> = { pdf: ['application/pdf'], docx: ['application/vnd.openxmlformats-officedocument.wordprocessingml.document'], pptx: ['application/vnd.openxmlformats-officedocument.presentationml.presentation'], png: ['image/png'], jpg: ['image/jpeg', 'image/jpg'], jpeg: ['image/jpeg', 'image/jpg'] };
  if (!extension || !allowed[extension]) return '仅支持 PDF、DOCX、PPTX、PNG、JPG 和 JPEG。';
  if (!allowed[extension].includes(file.type.toLowerCase())) return '文件类型与扩展名不匹配，请重新选择。';
  if (!file.size) return '不能上传空文件。';
  if (file.size > 20 * 1024 * 1024) return '单文件不能超过 20 MB。';
  return '';
}
function clearSelection() { selectedFile.value = null; uploadDescription.value = ''; uploadProgress.value = 0; if (fileInput.value) fileInput.value.value = ''; }

async function confirmUpload() {
  if (!projectId.value || !selectedFile.value || uploading.value) return;
  try { await ElMessageBox.confirm(`确认上传“${selectedFile.value.name}”到当前教学项目？`, '确认上传资料', { confirmButtonText: '确认上传', cancelButtonText: '取消', type: 'info', autofocus: false }); } catch { return; }
  uploading.value = true; errorMessage.value = '';
  try { await uploadMaterial(projectId.value, selectedFile.value, uploadDescription.value, (value) => { uploadProgress.value = value; }); ElMessage.success('资料上传成功'); clearSelection(); await loadWorkspace(); }
  catch (error) { errorMessage.value = resolveError(error, '资料上传失败，请检查文件后重试。'); }
  finally { uploading.value = false; }
}

function setUsageTypes(materialId: number, value: MaterialUsageType[]) { usageDrafts[materialId] = { types: [...value], note: usageDrafts[materialId]?.note || '' }; }
function setUsageNote(materialId: number, value: string) { usageDrafts[materialId] = { types: usageDrafts[materialId]?.types || [], note: value }; }
async function saveUsages(material: MaterialRecord, notify = true) {
  if (!projectId.value || savingUsageId.value) return false;
  const draft = usageDrafts[material.id];
  if (!draft?.types.length) { ElMessage.warning('请至少选择一种资料用途'); return false; }
  savingUsageId.value = material.id;
  try { const result = await updateMaterialUsages(projectId.value, material.id, draft.types, draft.note); material.usageTypes = [...result.usageTypes]; material.usageNote = result.note; if (notify) ElMessage.success('资料用途已保存'); return true; }
  catch (error) { ElMessage.error(resolveError(error, '资料用途保存失败。')); return false; }
  finally { savingUsageId.value = null; }
}
async function parseMaterial(material: MaterialRecord, retry: boolean) {
  if (!projectId.value || parsingId.value) return;
  const saved = await saveUsages(material, false); if (!saved) return;
  parsingId.value = material.id;
  try { const result = retry ? await retryMaterialParse(projectId.value, material.id) : await startMaterialParse(projectId.value, material.id); parseResults[material.id] = result; material.parseStatus = result.parseStatus; ElMessage[result.parseStatus === 'SUCCEEDED' ? 'success' : 'warning'](result.parseStatus === 'SUCCEEDED' ? '原型解析与知识索引已完成' : '解析未完成，请重试'); await loadWorkspace(); }
  catch (error) { ElMessage.error(resolveError(error, '原型解析失败。')); }
  finally { parsingId.value = null; }
}
async function handleDownload(material: MaterialRecord) { try { await downloadMaterial(projectId.value!, material); } catch (error) { ElMessage.error(resolveError(error, '文件下载失败。')); } }
function resolveError(error: unknown, fallback: string) { const message = (error as { response?: { data?: { message?: string } } }).response?.data?.message; return message && !/(Exception|java\.|Axios)/i.test(message) ? message : fallback; }
</script>

<style scoped>
.upload-workspace { display: grid; grid-template-columns: minmax(230px, .72fr) minmax(360px, 1.28fr); gap: 15px 22px; padding: 22px; }
.upload-workspace__copy { grid-row: span 3; }
.upload-workspace__copy span, .section-title span { color: var(--color-primary); font-size: 10px; font-weight: 800; }
.upload-workspace__copy h2, .section-title h2, .upload-workspace__copy p, .section-title p { margin: 0; }
.upload-workspace__copy h2 { margin-top: 6px; font-size: 18px; }
.upload-workspace__copy p { margin-top: 8px; color: var(--color-text-secondary); font-size: 12px; line-height: 1.7; }
.drop-zone { display: grid; min-height: 132px; place-items: center; align-content: center; gap: 7px; padding: 18px; border: 1px dashed var(--color-primary-border); border-radius: var(--radius-lg); background: var(--color-primary-soft); color: var(--color-primary); cursor: pointer; text-align: center; }
.drop-zone.is-dragging { border-style: solid; background: #dfe8ff; }
.drop-zone .el-icon { font-size: 28px; }
.drop-zone strong { max-width: 100%; overflow-wrap: anywhere; font-size: 13px; }
.drop-zone small { color: var(--color-text-muted); font-size: 10px; }
.sr-only { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0, 0, 0, 0); }
.upload-workspace__actions { display: flex; justify-content: flex-end; gap: 9px; }
.material-list-section { margin-top: 26px; }
.section-title { display: flex; align-items: end; justify-content: space-between; gap: 20px; margin-bottom: 13px; }
.section-title h2 { margin-top: 4px; font-size: 18px; }
.section-title p { color: var(--color-text-muted); font-size: 11px; }
.material-list { display: grid; gap: 14px; }
@media (max-width: 820px) { .upload-workspace { grid-template-columns: 1fr; } .upload-workspace__copy { grid-row: auto; } }
@media (max-width: 560px) { .section-title { align-items: flex-start; flex-direction: column; gap: 5px; } .upload-workspace__actions, .upload-workspace__actions .el-button { width: 100%; } }
</style>
