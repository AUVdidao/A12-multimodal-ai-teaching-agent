<template>
  <section class="spec-page">
    <ProjectContextHeader v-if="projectContext" :project="projectContext" />
    <ProjectWorkspaceNav :project-id="projectId" />

    <header class="spec-hero">
      <div>
        <span class="spec-eyebrow">PPT Engine 上游输入</span>
        <h2>Specification 生命周期与教师确认</h2>
        <p>教师在 DRAFT 中编辑，提交 REVIEW 后冻结，确认后形成不可变 LOCKED 版本。</p>
      </div>
      <el-tag v-if="specification" :type="statusType(specification.status)" effect="dark">{{ specification.status }}</el-tag>
    </header>

    <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" />
    <div v-if="loading" class="spec-state"><el-icon class="is-loading"><Loading /></el-icon> 正在读取 Specification 历史…</div>
    <template v-else>
      <section v-if="!specification" class="spec-empty">
        <el-icon><DocumentChecked /></el-icon>
        <h3>尚未生成 Specification DRAFT</h3>
        <p>Planning Agent proposal 边界已就绪；当前页面不调用模型。可用下方表单创建首个结构化草稿。</p>
        <el-button type="primary" @click="showCreate = true">创建 DRAFT</el-button>
      </section>

      <template v-else>
        <section class="spec-toolbar">
          <div>
            <strong>当前版本 v{{ specification.version }}</strong>
            <span> · checksum {{ specification.checksum }}</span>
          </div>
          <div class="spec-toolbar__actions">
            <el-button v-if="specification.status === 'DRAFT'" type="primary" :loading="saving" @click="saveDraft">保存草稿</el-button>
            <el-button v-if="specification.status === 'DRAFT'" type="success" :loading="submitting" @click="submitReview">提交 REVIEW</el-button>
            <el-button v-if="specification.status === 'REVIEW'" type="warning" :loading="returning" @click="returnDraft">退回新 DRAFT</el-button>
            <el-button v-if="specification.status === 'REVIEW'" type="primary" :loading="locking" @click="lockSpecification">批准并 LOCKED</el-button>
          </div>
        </section>

        <section class="spec-layout">
          <main class="spec-editor">
            <div class="spec-card">
              <div class="spec-card__heading"><h3>确认绑定与生成约束</h3><el-tag size="small">结构化字段</el-tag></div>
              <div class="spec-grid">
                <label>Template Profile ID<input v-model="specification.templateProfileId" :disabled="!editable" /></label>
                <label>Profile Version<input v-model.number="specification.templateProfileVersion" type="number" :disabled="!editable" /></label>
                <label>Capability View Version<input v-model.number="specification.templateCapabilityViewVersion" type="number" :disabled="!editable" /></label>
                <label>Capability View Checksum<input v-model="specification.templateCapabilityViewChecksum" :disabled="!editable" /></label>
                <label>目标页数<input v-model.number="specification.targetSlideCount" type="number" min="1" :disabled="!editable" /></label>
                <label>页数容差<input v-model.number="specification.slideCountTolerance" type="number" min="0" :disabled="!editable" /></label>
                <label>Locale<input v-model="specification.locale" :disabled="!editable" /></label>
                <label>Provider / Model<input v-model="modelLabel" :disabled="!editable" /></label>
              </div>
            </div>

            <div v-for="slide in specification.slides" :key="slide.slideId" class="spec-card slide-card">
              <div class="slide-card__heading"><span>第 {{ slide.pageNumber }} 页</span><strong>{{ slide.slideId }}</strong></div>
              <div class="spec-grid spec-grid--slide">
                <label>页面标题<input v-model="slide.title" :disabled="!editable" /></label>
                <label>教学目标<input v-model="slide.teachingGoal" :disabled="!editable" /></label>
              </div>
              <div class="structured-section">
                <h4>语义布局</h4>
                <div class="spec-grid">
                  <label>主语义角色<input v-model="slide.semanticLayout.primaryRole" :disabled="!editable" /></label>
                  <label>请求变换<input v-model="slide.semanticLayout.requestedTransform" :disabled="!editable" placeholder="例如 TRANSLATE_ONLY" /></label>
                </div>
                <div v-for="region in slide.semanticLayout.regions" :key="region.regionId" class="structured-row">
                  <input v-model="region.regionId" aria-label="区域 ID" :disabled="!editable" />
                  <input v-model="region.semanticRole" aria-label="区域语义角色" :disabled="!editable" />
                  <input v-model="region.preferredPosition" aria-label="区域位置" :disabled="!editable" />
                  <input v-model.number="region.maxItems" aria-label="区域最大项目数" type="number" :disabled="!editable" />
                </div>
              </div>
              <div class="structured-section">
                <h4>内容块</h4>
                <div v-for="block in slide.contentBlocks" :key="block.blockId" class="block-row">
                  <input v-model="block.blockId" aria-label="内容块 ID" :disabled="!editable" />
                  <select v-model="block.type" aria-label="内容块类型" :disabled="!editable"><option v-for="type in contentTypes" :key="type">{{ type }}</option></select>
                  <textarea v-model="block.content" aria-label="内容" :disabled="!editable" />
                  <label class="check-label"><input v-model="block.locked" type="checkbox" :disabled="!editable" /> 已锁定</label>
                </div>
                <p v-if="!slide.contentBlocks.length" class="muted">暂无内容块；请在 DRAFT 中补充结构化内容。</p>
              </div>
              <div class="structured-section">
                <h4>素材需求</h4>
                <div v-for="asset in slide.assetRequirements" :key="asset.assetId" class="structured-row structured-row--asset">
                  <input v-model="asset.assetId" aria-label="素材 ID" :disabled="!editable" />
                  <input v-model="asset.assetType" aria-label="素材类型" :disabled="!editable" />
                  <input v-model="asset.placementIntent" aria-label="放置意图" :disabled="!editable" />
                  <span>{{ asset.required ? 'Required' : 'Optional' }} · {{ asset.approvalStatus }}</span>
                </div>
                <p v-if="!slide.assetRequirements.length" class="muted">暂无素材需求。</p>
              </div>
            </div>
          </main>

          <aside class="spec-history spec-card">
            <h3>历史版本</h3>
            <button v-for="item in history.versions" :key="item.id" class="history-item" :class="{ 'is-current': item.id === specification.id }" @click="selectVersion(item)">
              <span>v{{ item.version }}</span><el-tag size="small" :type="statusType(item.status)">{{ item.status }}</el-tag>
              <small>{{ formatDateTime(item.createdAt) }}</small>
            </button>
            <p class="history-note">版本不会被覆盖。退回 REVIEW 会创建新的 DRAFT 版本；LOCKED 内容不可编辑。</p>
          </aside>
        </section>
      </template>
    </template>

    <el-dialog v-model="showCreate" title="创建首个 Specification DRAFT" width="720px">
      <div class="spec-grid">
        <label>Template Profile ID<input v-model="createForm.templateProfileId" /></label>
        <label>Template Profile Version<input v-model.number="createForm.templateProfileVersion" type="number" min="1" /></label>
        <label>Capability View Version<input v-model.number="createForm.templateCapabilityViewVersion" type="number" min="1" /></label>
        <label>Capability View Checksum<input v-model="createForm.templateCapabilityViewChecksum" /></label>
        <label>目标页数<input v-model.number="createForm.targetSlideCount" type="number" min="1" /></label>
        <label>页数容差<input v-model.number="createForm.slideCountTolerance" type="number" min="0" /></label>
        <label>页面标题<input v-model="createForm.slides[0].title" /></label>
        <label>教学目标<input v-model="createForm.slides[0].teachingGoal" /></label>
      </div>
      <template #footer><el-button @click="showCreate = false">取消</el-button><el-button type="primary" :loading="creating" @click="createDraft">创建 DRAFT</el-button></template>
    </el-dialog>
  </section>
</template>

<script setup lang="ts">
import { createPptSpecification, getPptSpecificationHistory, lockPptSpecification, returnPptSpecificationToDraft, submitPptSpecificationForReview, updatePptSpecification, type PptSpecification, type PptSpecificationHistory, type SpecificationWritePayload } from '@/api/pptSpecifications';
import { getProjectWorkspaceOverview, type ProjectBrief } from '@/api/workspace';
import ProjectContextHeader from '@/components/ProjectContextHeader.vue';
import ProjectWorkspaceNav from '@/components/ProjectWorkspaceNav.vue';
import { formatDateTime } from '@/utils/presentation';
import { DocumentChecked, Loading } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { computed, onMounted, reactive, ref } from 'vue';
import { useRoute } from 'vue-router';

const route = useRoute();
const projectId = computed(() => Number(route.params.projectId));
const history = ref<PptSpecificationHistory>({ projectId: projectId.value, versions: [] });
const specification = ref<PptSpecification>();
const projectContext = ref<ProjectBrief>();
const loading = ref(true); const creating = ref(false); const saving = ref(false); const submitting = ref(false); const returning = ref(false); const locking = ref(false);
const error = ref(''); const showCreate = ref(false);
const contentTypes = ['TITLE', 'BODY', 'BULLETS', 'QUOTE', 'TABLE', 'CHART', 'IMAGE', 'TEXT'];
const createForm = reactive<SpecificationWritePayload>({ contractVersion: '1.0.0', templateProfileId: 'confirmed-profile', templateProfileVersion: 1, templateCapabilityViewVersion: 1, templateCapabilityViewChecksum: '0'.repeat(64), targetSlideCount: 1, slideCountTolerance: 0, locale: 'zh-CN', provider: 'MOCK', model: 'proposal-boundary', aiSupplementPolicy: 'DISABLED', slides: [{ slideId: 'slide-1', pageNumber: 1, title: '', teachingGoal: '', semanticLayout: { primaryRole: 'content', regions: [], requestedTransform: null }, contentBlocks: [], assetRequirements: [], provenance: [], notes: '' }] });
const editable = computed(() => specification.value?.status === 'DRAFT');
const modelLabel = computed({ get: () => specification.value ? `${specification.value.provider} / ${specification.value.model}` : '', set: (value: string) => { const [provider, ...model] = value.split('/'); if (specification.value) { specification.value.provider = provider.trim(); specification.value.model = model.join('/').trim(); } } });

async function load() {
  loading.value = true; error.value = '';
  try { const [result, overview] = await Promise.all([getPptSpecificationHistory(projectId.value), getProjectWorkspaceOverview(projectId.value)]); history.value = result; specification.value = result.latest || undefined; projectContext.value = overview.project; }
  catch (e) { error.value = resolveError(e, 'Specification 历史读取失败，请稍后重试。'); }
  finally { loading.value = false; }
}
function selectVersion(value: PptSpecification) { specification.value = structuredClone(value); }
function statusType(status: string) { return status === 'LOCKED' ? 'success' : status === 'REVIEW' ? 'warning' : 'info'; }
function payload(): SpecificationWritePayload { const value = specification.value!; return { contractVersion: value.contractVersion, templateProfileId: value.templateProfileId, templateProfileVersion: value.templateProfileVersion, templateCapabilityViewVersion: value.templateCapabilityViewVersion, templateCapabilityViewChecksum: value.templateCapabilityViewChecksum, targetSlideCount: value.targetSlideCount, slideCountTolerance: value.slideCountTolerance, locale: value.locale, provider: value.provider, model: value.model, aiSupplementPolicy: value.aiSupplementPolicy, slides: value.slides, expectedChecksum: value.checksum, expectedEntityVersion: value.entityVersion }; }
async function createDraft() { creating.value = true; error.value = ''; try { specification.value = await createPptSpecification(projectId.value, createForm); showCreate.value = false; await load(); ElMessage.success('DRAFT 已创建'); } catch (e) { error.value = resolveError(e, 'DRAFT 创建失败，请检查结构化字段。'); } finally { creating.value = false; } }
async function saveDraft() { if (!specification.value) return; saving.value = true; try { specification.value = await updatePptSpecification(projectId.value, specification.value.id, payload()); await load(); ElMessage.success('DRAFT 已保存'); } catch (e) { error.value = resolveError(e, '草稿保存失败，可能已有并发修改。'); } finally { saving.value = false; } }
async function submitReview() { if (!specification.value) return; submitting.value = true; try { if (specification.value.entityVersion && payload().expectedChecksum) specification.value = await updatePptSpecification(projectId.value, specification.value.id, payload()); specification.value = await submitPptSpecificationForReview(projectId.value, specification.value.id, specification.value.checksum); await load(); ElMessage.success('已提交 REVIEW，内容已冻结'); } catch (e) { error.value = resolveError(e, '提交 REVIEW 失败，请刷新后重试。'); } finally { submitting.value = false; } }
async function returnDraft() { if (!specification.value) return; try { const result = await ElMessageBox.prompt('请输入退回原因', '退回并创建新 DRAFT', { inputPattern: /\S+/, inputErrorMessage: '退回原因不能为空' }); returning.value = true; specification.value = await returnPptSpecificationToDraft(projectId.value, specification.value.id, specification.value.checksum, result.value); await load(); ElMessage.success('已保留旧 REVIEW 并创建新 DRAFT'); } catch (e) { if (String((e as { message?: string }).message) !== 'cancel') error.value = resolveError(e, '退回失败，请刷新后重试。'); } finally { returning.value = false; } }
async function lockSpecification() { if (!specification.value) return; locking.value = true; try { specification.value = await lockPptSpecification(projectId.value, specification.value.id, specification.value.checksum); await load(); ElMessage.success('Specification 已 LOCKED'); } catch (e) { error.value = resolveError(e, 'LOCKED 失败，请确认 checksum 和内容锁定状态。'); } finally { locking.value = false; } }
function resolveError(e: unknown, fallback: string) { const message = (e as { response?: { data?: { message?: string } } }).response?.data?.message; return message && !/(Exception|stack trace|java\.|Axios|[A-Za-z]:\\|token|api[_-]?key)/i.test(message) ? message : fallback; }
onMounted(load);
</script>

<style scoped>
.spec-page { width: 100%; max-width: 1440px; margin: 0 auto; }
.spec-hero, .spec-toolbar, .spec-card, .spec-empty { border: 1px solid var(--ui-border); border-radius: 8px; background: var(--ui-panel); box-shadow: var(--shadow-panel); }
.spec-hero, .spec-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 18px; padding: 18px 20px; margin-bottom: 14px; }
.spec-eyebrow, .muted, .history-note { color: var(--ui-muted); font-size: 12px; }
.spec-hero h2 { margin: 4px 0 0; font-size: 21px; } .spec-hero p { margin: 4px 0 0; color: var(--ui-muted); font-size: 13px; }
.spec-state, .spec-empty { display: grid; place-items: center; min-height: 360px; padding: 32px; color: var(--ui-muted); }
.spec-empty { text-align: center; } .spec-empty .el-icon { font-size: 44px; color: var(--ui-primary); } .spec-empty h3 { margin: 14px 0 4px; color: var(--ui-text); } .spec-empty p { max-width: 520px; margin: 0 0 18px; }
.spec-toolbar { padding: 12px 16px; } .spec-toolbar span { color: var(--ui-faint); font-size: 11px; } .spec-toolbar__actions { display: flex; flex-wrap: wrap; gap: 8px; }
.spec-layout { display: grid; grid-template-columns: minmax(0, 1fr) 280px; align-items: start; gap: 14px; } .spec-editor { display: grid; gap: 14px; min-width: 0; }
.spec-card { padding: 16px; } .spec-card__heading, .slide-card__heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; } h3, h4 { margin: 0; } h4 { color: var(--ui-muted); font-size: 12px; }
.spec-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; margin-top: 14px; } .spec-grid label { display: grid; gap: 5px; color: var(--ui-muted); font-size: 11px; }
input, select, textarea { box-sizing: border-box; width: 100%; min-height: 34px; padding: 7px 9px; border: 1px solid var(--ui-border); border-radius: 5px; background: var(--ui-panel); color: var(--ui-text); font: inherit; font-size: 12px; } textarea { min-height: 70px; resize: vertical; }
.slide-card__heading { justify-content: flex-start; padding-bottom: 10px; border-bottom: 1px solid var(--ui-border); } .slide-card__heading span { color: var(--ui-primary); font-weight: 700; } .slide-card__heading strong { color: var(--ui-muted); font-size: 12px; }
.structured-section { display: grid; gap: 9px; margin-top: 16px; } .structured-row { display: grid; grid-template-columns: 1fr 1fr 1fr 90px; gap: 8px; } .structured-row--asset { grid-template-columns: 1fr 1fr 1fr 1.2fr; align-items: center; } .structured-row--asset span { color: var(--ui-muted); font-size: 11px; }
.block-row { display: grid; grid-template-columns: 110px 100px minmax(0, 1fr) 74px; align-items: start; gap: 8px; } .check-label { display: flex !important; align-items: center; gap: 4px; padding-top: 8px; white-space: nowrap; } .check-label input { width: auto; min-height: auto; }
.spec-history { position: sticky; top: 16px; } .history-item { display: grid; grid-template-columns: 36px auto 1fr; align-items: center; gap: 8px; width: 100%; padding: 10px 0; border: 0; border-bottom: 1px solid var(--ui-border); background: transparent; color: var(--ui-text); text-align: left; cursor: pointer; } .history-item.is-current { color: var(--ui-primary); } .history-item small { grid-column: 1 / -1; color: var(--ui-faint); font-size: 10px; } .history-note { line-height: 1.5; }
@media (max-width: 900px) { .spec-layout { grid-template-columns: 1fr; } .spec-history { position: static; } } @media (max-width: 640px) { .spec-hero, .spec-toolbar { align-items: flex-start; flex-direction: column; } .spec-grid { grid-template-columns: 1fr; } .structured-row, .structured-row--asset, .block-row { grid-template-columns: 1fr; } }
</style>


