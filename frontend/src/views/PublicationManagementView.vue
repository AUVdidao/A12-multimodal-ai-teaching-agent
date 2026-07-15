<template>
  <section class="page publication-page">
    <PageHeader
      eyebrow="成果发布"
      :title="isLeader ? '班级成果发布' : '项目发布记录'"
      :description="pageDescription"
    >
      <template #meta>
        <span class="role-context"><el-icon><User /></el-icon>{{ roleLabel }}</span>
      </template>
      <template #actions>
        <el-tooltip content="刷新发布记录" placement="bottom">
          <el-button
            circle
            :icon="Refresh"
            :loading="loading"
            aria-label="刷新发布记录"
            @click="loadPublications"
          />
        </el-tooltip>
        <el-button
          v-if="isLeader"
          type="primary"
          :icon="Promotion"
          @click="openPublishDialog"
        >
          发布到班级
        </el-button>
      </template>
    </PageHeader>

    <StatePanel
      v-if="!canAccess"
      type="info"
      title="当前身份无法访问发布管理"
      description="只有教研负责人和教师可以查看成果发布记录。"
    />
    <StatePanel
      v-else-if="loading && publications.length === 0"
      type="loading"
      title="正在读取发布记录"
      description="正在核对当前身份可以查看的班级发布与项目记录。"
    />
    <StatePanel
      v-else-if="errorMessage && publications.length === 0"
      type="error"
      title="发布记录读取失败"
      :description="errorMessage"
    >
      <template #action>
        <el-button type="primary" :icon="Refresh" @click="loadPublications">重新加载</el-button>
      </template>
    </StatePanel>

    <section v-else class="surface-panel publication-panel" v-loading="loading">
      <div class="publication-toolbar">
        <label class="publication-filter">
          <span>发布状态</span>
          <el-select v-model="statusFilter" aria-label="按发布状态筛选" @change="loadPublications">
            <el-option v-for="option in statusOptions" :key="option.value || 'ALL'" :label="option.label" :value="option.value" />
          </el-select>
        </label>
        <span class="publication-toolbar__count">{{ publications.length }} 条记录</span>
      </div>

      <el-alert
        v-if="errorMessage"
        class="publication-panel__alert"
        type="error"
        :title="errorMessage"
        show-icon
        :closable="false"
      />

      <el-table v-if="publications.length" class="publication-table" :data="publications" row-key="id">
        <el-table-column label="发布内容" min-width="220">
          <template #default="{ row }">
            <div class="identity-cell">
              <strong>{{ row.title }}</strong>
              <span>{{ row.projectName }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="课程与班级" min-width="180">
          <template #default="{ row }">
            <div class="identity-cell">
              <strong>{{ row.className }}</strong>
              <span>{{ row.courseName }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="批准版本" min-width="132">
          <template #default="{ row }">
            <div class="id-cell">
              <strong>版本 #{{ row.artifactVersionId }}</strong>
              <span>审批申请 #{{ row.approvalRequestId }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="112" align="center">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)" effect="light">{{ statusLabel(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="发布时间" min-width="160">
          <template #default="{ row }">{{ formatFullDateTime(row.publishedAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" :width="isLeader ? 220 : 112" fixed="right" align="right">
          <template #default="{ row }">
            <div class="row-actions">
              <el-button link type="primary" :icon="ViewIcon" @click="openDetail(row)">查看</el-button>
              <el-button
                v-if="isLeader && row.status === 'PUBLISHED'"
                link
                type="danger"
                :icon="CloseBold"
                :loading="withdrawingId === row.id"
                @click="handleWithdraw(row)"
              >撤回</el-button>
            </div>
          </template>
        </el-table-column>
      </el-table>

      <ul v-if="publications.length" class="publication-mobile-list">
        <li v-for="publication in publications" :key="publication.id" class="publication-mobile-row">
          <div class="publication-mobile-row__heading">
            <div class="identity-cell">
              <strong>{{ publication.title }}</strong>
              <span>{{ publication.projectName }} · {{ publication.className }}</span>
            </div>
            <el-tag :type="statusTagType(publication.status)" effect="light">{{ statusLabel(publication.status) }}</el-tag>
          </div>
          <dl class="publication-mobile-row__facts">
            <div><dt>课程</dt><dd>{{ publication.courseName }}</dd></div>
            <div><dt>批准版本</dt><dd>版本 #{{ publication.artifactVersionId }}</dd></div>
            <div><dt>发布时间</dt><dd>{{ formatFullDateTime(publication.publishedAt) }}</dd></div>
          </dl>
          <div class="mobile-actions">
            <el-button plain :icon="ViewIcon" @click="openDetail(publication)">查看详情</el-button>
            <el-button
              v-if="isLeader && publication.status === 'PUBLISHED'"
              type="danger"
              plain
              :icon="CloseBold"
              :loading="withdrawingId === publication.id"
              @click="handleWithdraw(publication)"
            >撤回发布</el-button>
          </div>
        </li>
      </ul>

      <StatePanel
        v-if="publications.length === 0"
        type="empty"
        :title="statusFilter ? `暂无${statusLabel(statusFilter)}记录` : '暂无发布记录'"
        :description="isLeader ? '通过审批的成果可以发布到已配置的对应课程班级。' : '你负责的项目还没有产生班级发布记录。'"
      />
    </section>

    <el-dialog v-model="publishDialogVisible" title="发布已通过成果" width="580px" :close-on-click-modal="false" @closed="resetPublishForm">
      <StatePanel v-if="referenceLoading" type="loading" title="正在读取可发布选项" description="正在读取已通过审批、项目课程和班级信息。" />
      <template v-else>
        <el-form ref="publishFormRef" :model="publishForm" :rules="publishRules" label-position="top">
          <el-form-item label="已通过审批申请" prop="approvalRequestId">
            <el-select
              v-model="publishForm.approvalRequestId"
              class="full-width"
              filterable
              placeholder="选择要发布的批准版本"
              no-data-text="暂无可发布的已通过审批"
              @change="publishForm.classId = undefined"
            >
              <el-option
                v-for="request in eligibleApprovals"
                :key="request.id"
                :label="approvalLabel(request)"
                :value="request.id"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="目标班级" prop="classId">
            <el-select
              v-model="publishForm.classId"
              class="full-width"
              filterable
              :disabled="!publishForm.approvalRequestId"
              placeholder="先选择审批申请，再选择班级"
              no-data-text="该项目课程暂无可用班级"
            >
              <el-option v-for="item in availableClasses" :key="item.id" :label="classLabel(item)" :value="item.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="发布标题" prop="title">
            <el-input v-model="publishForm.title" maxlength="200" show-word-limit placeholder="例如：函数单调性第一课" />
          </el-form-item>
          <el-form-item label="发布说明" prop="summary">
            <el-input v-model="publishForm.summary" type="textarea" :rows="4" maxlength="5000" show-word-limit placeholder="可选，给学生的学习提示或内容说明" />
          </el-form-item>
          <el-alert v-if="publishError" type="error" :title="publishError" show-icon :closable="false" />
        </el-form>
      </template>
      <template #footer>
        <el-button :disabled="publishing" @click="publishDialogVisible = false">取消</el-button>
        <el-button type="primary" :icon="Promotion" :loading="publishing" :disabled="referenceLoading || eligibleApprovals.length === 0" @click="handlePublish">确认发布</el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="detailVisible" title="发布详情" size="min(520px, 100%)" @closed="resetDetail">
      <StatePanel v-if="detailLoading" type="loading" title="正在读取发布详情" description="正在核对这条发布记录的当前状态。" />
      <StatePanel v-else-if="detailError" type="error" title="发布详情读取失败" :description="detailError">
        <template #action><el-button type="primary" :icon="Refresh" @click="loadDetail">重新加载</el-button></template>
      </StatePanel>
      <template v-else-if="detailPublication">
        <div class="detail-heading">
          <div><span>{{ detailPublication.projectName }}</span><h2>{{ detailPublication.title }}</h2></div>
          <el-tag :type="statusTagType(detailPublication.status)" effect="light">{{ statusLabel(detailPublication.status) }}</el-tag>
        </div>
        <p v-if="detailPublication.summary" class="detail-summary">{{ detailPublication.summary }}</p>
        <dl class="detail-list">
          <div><dt>课程</dt><dd>{{ detailPublication.courseName }}</dd></div>
          <div><dt>目标班级</dt><dd>{{ detailPublication.className }}</dd></div>
          <div><dt>批准版本</dt><dd>版本 #{{ detailPublication.artifactVersionId }}</dd></div>
          <div><dt>审批申请</dt><dd>申请 #{{ detailPublication.approvalRequestId }}</dd></div>
          <div><dt>发布人</dt><dd>{{ detailPublication.publishedByName }}</dd></div>
          <div><dt>发布时间</dt><dd>{{ formatFullDateTime(detailPublication.publishedAt) }}</dd></div>
          <div v-if="detailPublication.withdrawnAt" class="detail-list__wide"><dt>撤回时间</dt><dd>{{ formatFullDateTime(detailPublication.withdrawnAt) }}</dd></div>
        </dl>
        <div v-if="isLeader && detailPublication.status === 'PUBLISHED'" class="detail-actions">
          <el-button type="danger" plain :icon="CloseBold" :loading="withdrawingId === detailPublication.id" @click="handleWithdraw(detailPublication)">撤回发布</el-button>
        </div>
      </template>
    </el-drawer>
  </section>
</template>

<script setup lang="ts">
import { listApprovalRequests, type ApprovalRequest } from '@/api/approvals';
import { getCollaborationReferenceData, type ClassGroupOption } from '@/api/collaboration';
import { createPublication, getPublication, listPublications, withdrawPublication, type Publication, type PublicationStatus } from '@/api/publications';
import { listProjects, type TeachingProject } from '@/api/projects';
import PageHeader from '@/components/PageHeader.vue';
import StatePanel from '@/components/StatePanel.vue';
import { useAuthStore } from '@/stores/auth';
import { formatFullDateTime } from '@/utils/presentation';
import { CloseBold, Promotion, Refresh, User, View as ViewIcon } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox, type FormInstance, type FormRules, type TagProps } from 'element-plus';
import { computed, reactive, ref, watch } from 'vue';

type StatusFilter = PublicationStatus | '';
type TagType = TagProps['type'];
interface PublishForm { approvalRequestId?: number; classId?: number; title: string; summary: string }

const auth = useAuthStore();
const publications = ref<Publication[]>([]);
const loading = ref(false);
const errorMessage = ref('');
const statusFilter = ref<StatusFilter>('');
const approvedRequests = ref<ApprovalRequest[]>([]);
const projects = ref<TeachingProject[]>([]);
const classes = ref<ClassGroupOption[]>([]);
const referenceLoading = ref(false);
const publishDialogVisible = ref(false);
const publishing = ref(false);
const publishError = ref('');
const publishFormRef = ref<FormInstance>();
const publishForm = reactive<PublishForm>({ title: '', summary: '' });
const detailVisible = ref(false);
const detailLoading = ref(false);
const detailError = ref('');
const detailPublication = ref<Publication | null>(null);
const withdrawingId = ref<number | null>(null);
let requestSequence = 0;
let detailSequence = 0;

const isLeader = computed(() => auth.activeRole === 'LEADER');
const isTeacher = computed(() => auth.activeRole === 'TEACHER');
const canAccess = computed(() => isLeader.value || isTeacher.value);
const roleLabel = computed(() => isLeader.value ? '教研负责人' : isTeacher.value ? '教师' : '学生');
const pageDescription = computed(() => isLeader.value
  ? '选择已通过审批的固定版本，发布到对应课程班级，并管理你发布的内容。'
  : '查看自己教学项目已经发布到班级的内容，发布记录仅供查看。');
const statusOptions: { value: StatusFilter; label: string }[] = [
  { value: '', label: '全部状态' },
  { value: 'PUBLISHED', label: '已发布' },
  { value: 'WITHDRAWN', label: '已撤回' },
];
const projectById = computed(() => new Map(projects.value.map((project) => [project.id, project])));
const eligibleApprovals = computed(() => approvedRequests.value.filter((request) => {
  const project = projectById.value.get(request.projectId);
  return Boolean(project && classes.value.some((item) => sameCourse(item.courseName, project.courseName)));
}));
const selectedApproval = computed(() => approvedRequests.value.find((item) => item.id === publishForm.approvalRequestId));
const availableClasses = computed(() => {
  const project = selectedApproval.value ? projectById.value.get(selectedApproval.value.projectId) : undefined;
  return project ? classes.value.filter((item) => sameCourse(item.courseName, project.courseName)) : [];
});
const publishRules: FormRules<PublishForm> = {
  approvalRequestId: [{ required: true, message: '请选择已通过的审批申请', trigger: 'change' }],
  classId: [{ required: true, message: '请选择目标班级', trigger: 'change' }],
  title: [{ required: true, message: '请输入发布标题', trigger: 'blur' }, { max: 200, message: '发布标题不能超过 200 个字符', trigger: 'blur' }],
  summary: [{ max: 5000, message: '发布说明不能超过 5000 个字符', trigger: 'blur' }],
};

watch(() => auth.activeRole, () => {
  requestSequence += 1;
  publications.value = [];
  errorMessage.value = '';
  statusFilter.value = '';
  closeOverlays();
  if (canAccess.value) void loadPublications();
}, { immediate: true });

async function loadPublications() {
  if (!canAccess.value) return;
  const sequence = ++requestSequence;
  loading.value = true;
  errorMessage.value = '';
  try {
    const result = await listPublications(statusFilter.value || undefined);
    if (sequence === requestSequence) publications.value = result;
  } catch (error) {
    if (sequence === requestSequence) errorMessage.value = resolveError(error, '暂时无法读取发布记录，请稍后重试。');
  } finally {
    if (sequence === requestSequence) loading.value = false;
  }
}

async function openPublishDialog() {
  publishDialogVisible.value = true;
  publishError.value = '';
  if (approvedRequests.value.length && classes.value.length && projects.value.length) return;
  referenceLoading.value = true;
  try {
    const [requests, reference, projectItems] = await Promise.all([
      listApprovalRequests('APPROVED'),
      getCollaborationReferenceData(),
      listProjects(),
    ]);
    approvedRequests.value = requests;
    classes.value = reference.classes;
    projects.value = projectItems;
    if (eligibleApprovals.value.length === 1) {
      publishForm.approvalRequestId = eligibleApprovals.value[0].id;
      publishForm.title = eligibleApprovals.value[0].projectName || '';
    }
  } catch (error) {
    publishError.value = resolveError(error, '暂时无法读取已通过审批或班级信息，请稍后重试。');
  } finally {
    referenceLoading.value = false;
  }
}

async function handlePublish() {
  if (!publishFormRef.value || publishing.value) return;
  const valid = await publishFormRef.value.validate().catch(() => false);
  if (!valid || !publishForm.approvalRequestId || !publishForm.classId) return;
  publishing.value = true;
  publishError.value = '';
  try {
    const created = await createPublication({
      approvalRequestId: publishForm.approvalRequestId,
      classId: publishForm.classId,
      title: publishForm.title.trim(),
      ...(publishForm.summary.trim() ? { summary: publishForm.summary.trim() } : {}),
    });
    publications.value = [created, ...publications.value.filter((item) => item.id !== created.id)];
    publishDialogVisible.value = false;
    ElMessage.success('成果已发布到目标班级');
    void loadPublications();
  } catch (error) {
    publishError.value = resolveError(error, '发布失败，请核对审批申请、课程和班级后重试。');
  } finally {
    publishing.value = false;
  }
}

async function handleWithdraw(publication: Publication) {
  if (withdrawingId.value !== null) return;
  try {
    await ElMessageBox.confirm(`确认撤回“${publication.title}”在${publication.className}的发布吗？撤回后学生将无法继续打开该学习内容。`, '撤回发布', {
      confirmButtonText: '确认撤回', cancelButtonText: '保留发布', type: 'warning',
    });
  } catch { return; }
  withdrawingId.value = publication.id;
  try {
    const updated = await withdrawPublication(publication.id);
    replacePublication(updated);
    if (detailPublication.value?.id === updated.id) detailPublication.value = updated;
    ElMessage.success('发布已撤回');
  } catch (error) {
    ElMessage.error(resolveError(error, '撤回失败，发布状态可能已经变化，请刷新后重试。'));
  } finally {
    withdrawingId.value = null;
  }
}

function openDetail(publication: Publication) {
  detailVisible.value = true;
  detailPublication.value = publication;
  void loadDetail();
}

async function loadDetail() {
  const publicationId = detailPublication.value?.id;
  if (!publicationId) return;
  const sequence = ++detailSequence;
  detailLoading.value = true;
  detailError.value = '';
  try {
    const result = await getPublication(publicationId);
    if (sequence === detailSequence) detailPublication.value = result;
  } catch (error) {
    if (sequence === detailSequence) detailError.value = resolveError(error, '暂时无法读取发布详情，请稍后重试。');
  } finally {
    if (sequence === detailSequence) detailLoading.value = false;
  }
}

function replacePublication(updated: Publication) {
  publications.value = publications.value.map((item) => item.id === updated.id ? updated : item);
}

function resetPublishForm() {
  publishForm.approvalRequestId = undefined;
  publishForm.classId = undefined;
  publishForm.title = '';
  publishForm.summary = '';
  publishError.value = '';
  publishFormRef.value?.clearValidate();
}

function resetDetail() {
  detailPublication.value = null;
  detailError.value = '';
  detailLoading.value = false;
}

function closeOverlays() {
  publishDialogVisible.value = false;
  detailVisible.value = false;
  resetPublishForm();
  resetDetail();
}

function approvalLabel(request: ApprovalRequest) {
  const version = request.artifactVersionNumber ? `版本 ${request.artifactVersionNumber}` : `版本记录 #${request.artifactVersionId}`;
  return `${request.projectName || `项目 #${request.projectId}`} · ${version} · ${request.submittedByName || '提交教师'}`;
}

function classLabel(item: ClassGroupOption) {
  return `${item.className} · ${item.courseName}（${item.studentCount} 名学生）`;
}

function sameCourse(left: string | null | undefined, right: string | null | undefined) {
  return Boolean(left && right && left.trim().toLocaleLowerCase() === right.trim().toLocaleLowerCase());
}

function statusLabel(status: PublicationStatus) { return status === 'PUBLISHED' ? '已发布' : '已撤回'; }
function statusTagType(status: PublicationStatus): TagType { return status === 'PUBLISHED' ? 'success' : 'info'; }
function resolveError(error: unknown, fallback: string) {
  const message = (error as { response?: { data?: { message?: string } } }).response?.data?.message;
  return message && !/(Exception|java\.|Axios)/i.test(message) ? message : fallback;
}
</script>

<style scoped>
.publication-panel { overflow: hidden; }
.publication-toolbar { display: flex; align-items: end; justify-content: space-between; gap: 16px; padding: 16px 20px; border-bottom: 1px solid var(--color-border); }
.publication-filter { display: grid; gap: 6px; width: 180px; color: var(--color-text-secondary); font-size: 12px; font-weight: 700; }
.publication-toolbar__count { padding-bottom: 10px; color: var(--color-text-muted); font-size: 12px; }
.publication-panel__alert { margin: 16px 20px 0; }
.identity-cell, .id-cell { display: grid; min-width: 0; gap: 4px; }
.identity-cell strong, .id-cell strong { overflow: hidden; color: var(--color-text); font-size: 14px; line-height: 1.4; text-overflow: ellipsis; white-space: nowrap; }
.identity-cell span, .id-cell span { overflow: hidden; color: var(--color-text-muted); font-size: 12px; line-height: 1.4; text-overflow: ellipsis; white-space: nowrap; }
.row-actions, .mobile-actions { display: flex; flex-wrap: wrap; justify-content: flex-end; gap: 8px; }
.publication-mobile-list { display: none; margin: 0; padding: 0; list-style: none; }
.publication-mobile-row { padding: 16px 20px; border-bottom: 1px solid var(--color-border); }
.publication-mobile-row__heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; }
.publication-mobile-row__facts, .detail-list { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px 18px; padding: 0; margin: 18px 0 0; }
.publication-mobile-row__facts div, .detail-list div { min-width: 0; }
.publication-mobile-row__facts dt, .detail-list dt { color: var(--color-text-muted); font-size: 12px; }
.publication-mobile-row__facts dd, .detail-list dd { margin: 4px 0 0; color: var(--color-text); font-size: 13px; line-height: 1.5; overflow-wrap: anywhere; }
.mobile-actions { justify-content: flex-start; margin-top: 16px; }
.full-width { width: 100%; }
.detail-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; }
.detail-heading span { color: var(--color-text-muted); font-size: 12px; }
.detail-heading h2 { margin: 5px 0 0; color: var(--color-text); font-size: 20px; line-height: 1.35; overflow-wrap: anywhere; }
.detail-summary { padding: 12px; margin: 18px 0 0; border-left: 3px solid var(--color-primary); background: var(--color-primary-soft); color: var(--color-text-secondary); font-size: 13px; line-height: 1.7; white-space: pre-wrap; }
.detail-list__wide { grid-column: 1 / -1; }
.detail-actions { display: flex; justify-content: flex-end; margin-top: 22px; }
.role-context { display: inline-flex; align-items: center; gap: 6px; color: var(--color-text-secondary); font-size: 13px; font-weight: 600; }

@media (max-width: 760px) {
  .publication-table { display: none; }
  .publication-mobile-list { display: block; }
}
@media (max-width: 560px) {
  .publication-toolbar { align-items: stretch; flex-direction: column; }
  .publication-filter { width: 100%; }
  .publication-toolbar__count { align-self: flex-end; padding-bottom: 0; }
  .publication-mobile-row__facts, .detail-list { grid-template-columns: 1fr; }
  .detail-list__wide { grid-column: auto; }
  .detail-heading { flex-direction: column; }
  .detail-actions, .detail-actions .el-button { width: 100%; }
}
</style>
