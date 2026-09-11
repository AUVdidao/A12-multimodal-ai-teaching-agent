<template>
  <section class="page" v-loading="loading">
    <ProjectWorkspaceNav :project-id="projectId" />
    <PageHeader eyebrow="真实模板 Profile" title="上传、解析与审核模板" description="模板源文件按项目隔离保存；解析状态和 Profile 版本均可追溯。Renderer / Analyzer 未配置时会明确显示未实现。" />

    <section class="panel upload-panel">
      <div class="panel__header"><div><h3>上传真实 PPTX</h3><p>只接受 .pptx 与官方 MIME；相同模板文件重复上传会复用已有 Source Version。</p></div><span class="tag-soft info">不接入旧 PPT 链</span></div>
      <div class="upload-row">
        <el-input v-model="templateName" placeholder="模板名称，例如：生物探究课模板" maxlength="200" />
        <UiUploadDropzone :disabled="uploading" accept=".pptx" title="选择真实 PPTX" description="上传后创建不可被 Profile 覆盖的 Source Version" @select="handleUpload" />
      </div>
      <el-progress v-if="uploading" :percentage="100" status="success" />
    </section>

    <div class="template-columns">
      <section class="panel">
        <div class="panel__header"><div><h3>项目模板</h3><p>{{ templates.length }} 个模板</p></div></div>
        <el-empty v-if="templates.length === 0" description="暂无真实模板" :image-size="72" />
        <button v-for="item in templates" :key="item.id" type="button" :class="['template-item', { selected: selected?.template.id === item.id }]" @click="selectTemplate(item.id)">
          <span><strong>{{ item.name }}</strong><small>Source v{{ item.activeSourceVersionId || '—' }}</small></span><el-icon><ArrowRight /></el-icon>
        </button>
      </section>

      <section v-if="selected" class="panel detail-panel">
        <div class="panel__header"><div><h3>{{ selected.template.name }}</h3><p>Source Version 与 Profile 历史均不可覆盖</p></div><el-tag type="info" effect="plain">项目 {{ selected.template.projectId }}</el-tag></div>
        <el-table :data="selected.sourceVersions" size="small">
          <el-table-column prop="version" label="Source" width="78" />
          <el-table-column prop="originalFilename" label="文件" min-width="180" />
          <el-table-column label="SHA-256" min-width="190"><template #default="{ row }"><code>{{ row.sha256.slice(0, 16) }}…</code></template></el-table-column>
          <el-table-column label="处理状态" min-width="230"><template #default="{ row }"><span class="status-line">解析 {{ statusLabel(row.parseStatus) }} · 渲染 {{ statusLabel(row.renderStatus) }} · 分析 {{ statusLabel(row.analysisStatus) }}</span></template></el-table-column>
          <el-table-column label="操作" width="250"><template #default="{ row }"><div class="row-actions"><el-button size="small" :loading="processingKey === `${row.id}-parse`" @click="process(row, 'parse')">解析</el-button><el-button size="small" @click="process(row, 'render')">渲染记录</el-button><el-button size="small" @click="process(row, 'analyze')">Analyzer</el-button></div></template></el-table-column>
        </el-table>

        <div v-if="selected.sourceVersions[0]" class="source-note"><strong>详情状态：</strong>{{ selected.sourceVersions[0].detailsLoaded ? '已读取并校验完整详情' : '摘要，详情按 Source API 懒加载' }}；<strong>解析快照：</strong>{{ selected.sourceVersions[0].structuralSnapshot ? `已保存 ${selected.sourceVersions[0].structuralSnapshot.slideCount} 页结构快照` : '尚未运行 Parser' }}；<strong>页面预览：</strong>{{ selected.sourceVersions[0].renderedSlideSet?.statusMessage || '尚无预览记录' }}</div>

        <div class="profile-section">
          <div class="panel__header"><div><h3>Profile 生命周期</h3><p>Candidate → REVIEW → CONFIRMED；CONFIRMED 只能通过新 Revision 改变</p></div></div>
          <div class="profile-actions"><el-select v-model="sourceForProfile" placeholder="选择 Source Version"><el-option v-for="source in selected.sourceVersions" :key="source.id" :label="`Source v${source.version} · ${source.originalFilename}`" :value="source.id" /></el-select><el-button type="primary" :disabled="!sourceForProfile" @click="createProfile">创建 Candidate</el-button></div>
          <el-table :data="selected.profiles" size="small" @row-click="loadProfile">
            <el-table-column prop="version" label="Profile" width="80" /><el-table-column prop="sourceVersionId" label="Source" width="80" /><el-table-column prop="status" label="状态" width="110" /><el-table-column label="checksum" min-width="180"><template #default="{ row }"><code>{{ row.checksum.slice(0, 16) }}…</code></template></el-table-column>
          </el-table>
        </div>
      </section>
      <StatePanel v-else type="info" title="选择或上传一个真实模板" description="模板源文件、结构快照、渲染记录和 Profile 版本会在这里集中查看。" />
    </div>

    <section v-if="profile" class="panel profile-editor">
      <div class="panel__header"><div><h3>Profile v{{ profile.version }} · {{ profile.status }}</h3><p>Capability View 只展示语义字段，不暴露 Shape、Group、坐标或 OXML。</p></div><el-tag :type="profile.status === 'CONFIRMED' ? 'success' : 'warning'" effect="plain">{{ profile.checksum }}</el-tag></div>
      <div class="editor-grid"><div><label>Candidate / Profile JSON（教师可编辑）</label><el-input v-model="profileJson" type="textarea" :rows="16" :disabled="profile.status === 'CONFIRMED'" /><div class="profile-buttons"><el-button :disabled="profile.status === 'CONFIRMED'" @click="saveProfile">保存编辑</el-button><el-button :disabled="profile.status !== 'CANDIDATE'" @click="reviewProfile">提交 REVIEW</el-button><el-button type="primary" :disabled="profile.status !== 'REVIEW'" @click="confirmCurrentProfile">按当前 checksum 确认</el-button></div></div><div><label>Template Capability View</label><pre class="capability-view">{{ JSON.stringify(profile.capabilityView, null, 2) }}</pre></div></div>
    </section>
  </section>
</template>

<script setup lang="ts">
import { confirmProfile, createCandidate, defaultProfile, editCandidate, getProfile, getSourceVersion, getTemplate, listTemplates, processTemplate, submitProfileReview, uploadTemplate, type ProfileResponse, type SourceVersion, type TemplateDetail, type TemplateSummary } from '@/api/templates';
import PageHeader from '@/components/PageHeader.vue';
import ProjectContextHeader from '@/components/ProjectContextHeader.vue';
import ProjectWorkspaceNav from '@/components/ProjectWorkspaceNav.vue';
import StatePanel from '@/components/StatePanel.vue';
import UiUploadDropzone from '@/components/ui/UiUploadDropzone.vue';
import { ArrowRight } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import { onMounted, ref } from 'vue';
import { useRoute } from 'vue-router';

const route = useRoute();
const projectId = Number(route.params.projectId);
const templates = ref<TemplateSummary[]>([]);
const selected = ref<TemplateDetail>();
const profile = ref<ProfileResponse>();
const profileJson = ref('');
const templateName = ref('');
const sourceForProfile = ref<number>();
const loading = ref(true);
const uploading = ref(false);
const processingKey = ref('');

async function load() {
  loading.value = true;
  try { templates.value = await listTemplates(projectId); if (selected.value) await selectTemplate(selected.value.template.id); } finally { loading.value = false; }
}

async function selectTemplate(id: number) { const detail = await getTemplate(projectId, id); detail.sourceVersions = await Promise.all(detail.sourceVersions.map((source) => getSourceVersion(projectId, id, source.id))); selected.value = detail; profile.value = undefined; sourceForProfile.value = selected.value.sourceVersions[0]?.id; }
async function handleUpload(file: File) { if (!templateName.value.trim()) { ElMessage.warning('请先填写模板名称'); return; } uploading.value = true; try { const result = await uploadTemplate(projectId, templateName.value, file); await load(); await selectTemplate(result.template.id); ElMessage.success(result.deduplicated ? '已复用相同 Source Version' : '真实模板上传成功'); } catch (error) { ElMessage.error(errorMessage(error, '模板上传失败，请稍后重试')); } finally { uploading.value = false; } }
async function process(source: SourceVersion, operation: 'parse' | 'render' | 'analyze') { processingKey.value = `${source.id}-${operation}`; try { await processTemplate(projectId, source.templateId, source.id, operation); await selectTemplate(source.templateId); ElMessage.info(operation === 'parse' ? 'Parser 已完成或失败状态已记录' : '处理边界状态已记录'); } catch (error) { ElMessage.error(errorMessage(error, '模板处理失败，请稍后重试')); } finally { processingKey.value = ''; } }
async function createProfile() { if (!selected.value || !sourceForProfile.value) return; try { const templateId = selected.value.template.id; const created = await createCandidate(projectId, templateId, sourceForProfile.value, defaultProfile(selected.value.template.name)); await selectTemplate(templateId); profile.value = await getProfile(projectId, templateId, created.id); profileJson.value = JSON.stringify(profile.value.profile, null, 2); ElMessage.success('Candidate Profile 已创建'); } catch (error) { ElMessage.error(errorMessage(error, 'Candidate 创建失败')); } }
async function loadProfile(row: { id: number }) { if (!selected.value) return; profile.value = await getProfile(projectId, selected.value.template.id, row.id); profileJson.value = JSON.stringify(profile.value.profile, null, 2); }
async function saveProfile() { if (!selected.value || !profile.value) return; try { const next = JSON.parse(profileJson.value) as Record<string, unknown>; profile.value = await editCandidate(projectId, selected.value.template.id, profile.value.id, next); profileJson.value = JSON.stringify(profile.value.profile, null, 2); ElMessage.success('Candidate 已保存，之后由教师拥有'); } catch (error) { ElMessage.error(errorMessage(error, 'Profile JSON 无效或保存失败')); } }
async function reviewProfile() { if (!selected.value || !profile.value) return; profile.value = await submitProfileReview(projectId, selected.value.template.id, profile.value.id, '教师提交审核'); ElMessage.success('已进入 REVIEW'); }
async function confirmCurrentProfile() { if (!selected.value || !profile.value) return; profile.value = await confirmProfile(projectId, selected.value.template.id, profile.value.id, profile.value.checksum); ElMessage.success('已按 checksum 确认，Profile 不可原地修改'); }
function statusLabel(status: string) { return ({ NOT_STARTED: '待运行', PROCESSING: '处理中', NOT_READY: '未就绪', SUCCEEDED: '完成', FAILED: '失败', NOT_IMPLEMENTED: '未实现' } as Record<string, string>)[status] || status; }
function errorMessage(error: unknown, fallback: string) { const message = (error as { response?: { data?: { message?: string } } }).response?.data?.message; return message || fallback; }
onMounted(load);
</script>

<style scoped>
.upload-panel { margin-bottom: 16px; }.upload-row { display: grid; grid-template-columns: minmax(240px, .75fr) minmax(320px, 1.25fr); gap: 16px; align-items: center; }.template-columns { display: grid; grid-template-columns: minmax(230px, .34fr) minmax(0, 1fr); gap: 16px; align-items: start; }.template-item { display: flex; width: 100%; justify-content: space-between; align-items: center; padding: 13px 14px; border: 0; border-bottom: 1px solid var(--ui-border); background: transparent; color: var(--ui-text); cursor: pointer; text-align: left; }.template-item.selected { background: var(--ui-primary-soft); color: var(--ui-primary); }.template-item span { display: grid; gap: 4px; }.template-item small { color: var(--ui-muted); }.row-actions { display: flex; gap: 4px; }.status-line, .source-note { color: var(--ui-muted); font-size: 12px; line-height: 1.6; }.source-note { margin-top: 14px; padding: 10px 12px; background: var(--ui-surface-muted); border-radius: 6px; }.profile-section { margin-top: 22px; }.profile-actions { display: flex; gap: 10px; margin-bottom: 12px; }.editor-grid { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: 18px; }.editor-grid label { display: block; margin-bottom: 8px; color: var(--ui-text-secondary); font-size: 12px; }.profile-buttons { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 12px; }.capability-view { min-height: 330px; max-height: 450px; margin: 0; padding: 12px; overflow: auto; border: 1px solid var(--ui-border); border-radius: 6px; background: var(--ui-surface-muted); color: var(--ui-text-secondary); font-size: 12px; line-height: 1.55; white-space: pre-wrap; }.profile-editor { margin-top: 16px; } code { font-size: 11px; } @media (max-width: 960px) { .template-columns, .editor-grid, .upload-row { grid-template-columns: 1fr; } }
</style>
