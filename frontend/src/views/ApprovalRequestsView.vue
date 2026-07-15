<template>
  <section class="page approval-requests-page">
    <PageHeader
      eyebrow="成果流转"
      :title="pageTitle"
      :description="pageDescription"
    >
      <template #meta>
        <span class="role-context">
          <el-icon><User /></el-icon>
          当前身份：{{ roleLabel }}
        </span>
      </template>
      <template v-if="canAccess" #actions>
        <el-tooltip content="刷新审批申请" placement="bottom">
          <el-button
            circle
            :icon="Refresh"
            :loading="loading"
            aria-label="刷新审批申请"
            @click="loadRequests"
          />
        </el-tooltip>
        <el-button
          v-if="isTeacher"
          type="primary"
          :icon="Upload"
          @click="openSubmissionDialog"
        >
          提交定稿版本
        </el-button>
      </template>
    </PageHeader>

    <StatePanel
      v-if="!canAccess"
      type="info"
      title="当前身份无法访问成果审批"
      description="学生身份不参与成果提交与审批，请返回学习空间查看已发布内容。"
    />
    <StatePanel
      v-else-if="loading && requests.length === 0"
      type="loading"
      title="正在读取审批申请"
      :description="isLeader ? '正在读取分配给你的待审与历史申请。' : '正在读取你提交的成果审批记录。'"
    />
    <StatePanel
      v-else-if="errorMessage && requests.length === 0"
      type="error"
      title="审批申请读取失败"
      :description="errorMessage"
    >
      <template #action>
        <el-button type="primary" :icon="Refresh" @click="loadRequests">重新加载</el-button>
      </template>
    </StatePanel>

    <section v-else class="surface-panel approval-panel" v-loading="loading">
      <div class="approval-toolbar">
        <label class="approval-filter">
          <span>申请状态</span>
          <el-select
            v-model="statusFilter"
            aria-label="按审批状态筛选"
            @change="handleFilterChange"
          >
            <el-option
              v-for="option in statusFilterOptions"
              :key="option.value"
              :label="option.label"
              :value="option.value"
            />
          </el-select>
        </label>
        <span class="approval-toolbar__count">{{ requests.length }} 项申请</span>
      </div>

      <el-alert
        v-if="errorMessage"
        class="approval-panel__alert"
        type="error"
        :title="errorMessage"
        show-icon
        :closable="false"
      />

      <el-table
        v-if="requests.length > 0"
        class="approval-table"
        :data="requests"
        row-key="id"
      >
        <el-table-column label="项目" min-width="190">
          <template #default="{ row }">
            <div class="identity-cell">
              <strong>{{ projectTitle(row) }}</strong>
              <span>项目 #{{ row.projectId }}</span>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="定稿成果版本" min-width="156">
          <template #default="{ row }">
            <div class="identity-cell">
              <strong>{{ versionTitle(row) }}</strong>
              <span>版本记录 #{{ row.artifactVersionId }}</span>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="提交教师" min-width="140">
          <template #default="{ row }">
            <div class="person-cell">
              <strong>{{ personName(row.submittedByName, '未知教师') }}</strong>
              <span>用户 #{{ row.submittedBy }}</span>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="审批负责人" min-width="140">
          <template #default="{ row }">
            <div class="person-cell">
              <strong>{{ personName(row.reviewerName, '未知负责人') }}</strong>
              <span>用户 #{{ row.reviewerId }}</span>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="状态" width="112" align="center">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)" effect="light">
              {{ statusLabel(row.status) }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column label="流转时间" min-width="182">
          <template #default="{ row }">
            <div class="time-cell">
              <span>提交 {{ displayDate(row.submittedAt) }}</span>
              <span v-if="row.reviewedAt">处理 {{ displayDate(row.reviewedAt) }}</span>
              <span v-else>尚未处理</span>
            </div>
          </template>
        </el-table-column>

        <el-table-column
          label="操作"
          :width="isLeader ? 292 : 190"
          fixed="right"
          align="right"
        >
          <template #default="{ row }">
            <div class="row-actions">
              <el-button link type="primary" :icon="ViewIcon" @click="openDetail(row)">
                详情
              </el-button>
              <el-button
                v-if="isTeacher && row.status === 'SUBMITTED'"
                link
                type="danger"
                :icon="CloseBold"
                :loading="cancellingId === row.id"
                @click="handleCancel(row)"
              >
                撤回
              </el-button>
              <template v-if="isLeader && row.status === 'SUBMITTED'">
                <el-button
                  type="success"
                  plain
                  :icon="CircleCheck"
                  @click="openReviewDialog(row, 'APPROVED')"
                >
                  通过
                </el-button>
                <el-button
                  type="warning"
                  plain
                  :icon="RefreshLeft"
                  @click="openReviewDialog(row, 'REVISION_REQUIRED')"
                >
                  退回修改
                </el-button>
              </template>
            </div>
          </template>
        </el-table-column>
      </el-table>

      <ul v-if="requests.length > 0" class="approval-mobile-list">
        <li v-for="request in requests" :key="request.id" class="approval-mobile-row">
          <div class="approval-mobile-row__heading">
            <div class="identity-cell">
              <strong>{{ projectTitle(request) }}</strong>
              <span>项目 #{{ request.projectId }} · 申请 #{{ request.id }}</span>
            </div>
            <el-tag :type="statusTagType(request.status)" effect="light">
              {{ statusLabel(request.status) }}
            </el-tag>
          </div>

          <dl class="approval-mobile-row__facts">
            <div>
              <dt>定稿版本</dt>
              <dd>{{ versionTitle(request) }} · 记录 #{{ request.artifactVersionId }}</dd>
            </div>
            <div>
              <dt>提交教师</dt>
              <dd>{{ personName(request.submittedByName, '未知教师') }} · #{{ request.submittedBy }}</dd>
            </div>
            <div>
              <dt>审批负责人</dt>
              <dd>{{ personName(request.reviewerName, '未知负责人') }} · #{{ request.reviewerId }}</dd>
            </div>
            <div>
              <dt>提交时间</dt>
              <dd>{{ displayDate(request.submittedAt) }}</dd>
            </div>
          </dl>

          <div class="mobile-actions">
            <el-button plain :icon="ViewIcon" @click="openDetail(request)">查看详情</el-button>
            <el-button
              v-if="isTeacher && request.status === 'SUBMITTED'"
              type="danger"
              plain
              :icon="CloseBold"
              :loading="cancellingId === request.id"
              @click="handleCancel(request)"
            >
              撤回申请
            </el-button>
            <template v-if="isLeader && request.status === 'SUBMITTED'">
              <el-button
                type="success"
                plain
                :icon="CircleCheck"
                @click="openReviewDialog(request, 'APPROVED')"
              >
                通过
              </el-button>
              <el-button
                type="warning"
                plain
                :icon="RefreshLeft"
                @click="openReviewDialog(request, 'REVISION_REQUIRED')"
              >
                退回修改
              </el-button>
            </template>
          </div>
        </li>
      </ul>

      <el-empty v-if="requests.length === 0" :description="emptyDescription" :image-size="72" />
    </section>

    <el-dialog
      v-model="submissionDialogVisible"
      title="提交定稿成果审批"
      width="560px"
      :close-on-click-modal="false"
      @closed="resetSubmissionForm"
    >
      <el-form
        ref="submissionFormRef"
        :model="submissionForm"
        :rules="submissionRules"
        label-position="top"
      >
        <div class="submission-id-grid">
          <el-form-item label="教学项目" prop="projectId">
            <el-select
              v-model="submissionForm.projectId"
              :loading="referenceLoading"
              placeholder="选择本人教学项目"
              filterable
            >
              <el-option
                v-for="project in projects"
                :key="project.id"
                :label="project.projectName"
                :value="project.id"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="成果版本" prop="artifactVersionId">
            <div class="artifact-version-picker">
              <el-select
                v-model="submissionForm.artifactVersionId"
                :disabled="!submissionForm.projectId || artifactVersionsLoading"
                :loading="artifactVersionsLoading"
                placeholder="选择成果版本"
                filterable
              >
                <el-option
                  v-for="version in availableArtifactVersions"
                  :key="version.id"
                  :label="artifactVersionLabel(version)"
                  :value="version.id"
                >
                  <div class="artifact-version-option">
                    <span>{{ artifactVersionLabel(version) }}</span>
                    <el-tag
                      size="small"
                      :type="version.finalVersion ? 'success' : 'warning'"
                    >
                      {{ version.finalVersion ? '已定稿' : '可定稿' }}
                    </el-tag>
                  </div>
                </el-option>
              </el-select>
              <el-button
                v-if="selectedArtifactVersion && !selectedArtifactVersion.finalVersion"
                type="warning"
                plain
                :loading="finalizingVersionId === selectedArtifactVersion.id"
                :disabled="finalizingVersionId !== null"
                @click="handleFinalizeVersion"
              >
                定稿
              </el-button>
            </div>
            <el-alert
              v-if="artifactVersionsError"
              class="artifact-version-state"
              type="error"
              :title="artifactVersionsError"
              show-icon
              :closable="false"
            >
              <template #default>
                <el-button link type="primary" @click="reloadArtifactVersions">
                  重新加载版本
                </el-button>
              </template>
            </el-alert>
            <span
              v-else-if="submissionForm.projectId && !artifactVersionsLoading && availableArtifactVersions.length === 0"
              class="artifact-version-state artifact-version-state--empty"
            >
              该项目暂无可提交的生成成果版本
            </span>
          </el-form-item>
          <el-form-item class="submission-id-grid__wide" label="审批负责人" prop="reviewerId">
            <el-select
              v-model="submissionForm.reviewerId"
              :loading="referenceLoading"
              placeholder="选择教研负责人"
              filterable
            >
              <el-option
                v-for="reviewer in reviewers"
                :key="reviewer.id"
                :label="reviewer.displayName"
                :value="reviewer.id"
              />
            </el-select>
          </el-form-item>
        </div>

        <el-alert
          v-if="submissionError"
          type="error"
          :title="submissionError"
          show-icon
          :closable="false"
        />
      </el-form>

      <template #footer>
        <el-button :disabled="submitting" @click="submissionDialogVisible = false">取消</el-button>
        <el-button type="primary" :icon="Upload" :loading="submitting" @click="handleSubmission">
          提交审批
        </el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="reviewDialogVisible"
      :title="reviewDialogTitle"
      width="580px"
      :close-on-click-modal="false"
      @closed="resetReviewForm"
    >
      <div v-if="selectedRequest" class="dialog-request-context">
        <strong>{{ projectTitle(selectedRequest) }}</strong>
        <span>
          项目 #{{ selectedRequest.projectId }} · {{ versionTitle(selectedRequest) }} ·
          版本记录 #{{ selectedRequest.artifactVersionId }}
        </span>
      </div>

      <el-form ref="reviewFormRef" :model="reviewForm" :rules="reviewRules" label-position="top">
        <el-form-item :label="reviewNoteLabel" prop="note">
          <el-input
            v-model="reviewForm.note"
            type="textarea"
            :rows="6"
            maxlength="5000"
            show-word-limit
            resize="vertical"
            :placeholder="reviewNotePlaceholder"
          />
        </el-form-item>

        <el-alert
          v-if="reviewError"
          type="error"
          :title="reviewError"
          show-icon
          :closable="false"
        />
      </el-form>

      <template #footer>
        <el-button :disabled="reviewing" @click="reviewDialogVisible = false">取消</el-button>
        <el-button
          :type="reviewForm.status === 'APPROVED' ? 'success' : 'warning'"
          :icon="reviewForm.status === 'APPROVED' ? CircleCheck : RefreshLeft"
          :loading="reviewing"
          @click="handleReview"
        >
          {{ reviewForm.status === 'APPROVED' ? '确认通过' : '确认退回' }}
        </el-button>
      </template>
    </el-dialog>

    <el-drawer
      v-model="detailDrawerVisible"
      title="审批申请详情"
      size="min(520px, 100%)"
      @closed="resetDetail"
    >
      <StatePanel
        v-if="detailLoading"
        type="loading"
        title="正在读取申请详情"
        description="正在核对这条审批申请的最新状态。"
      />
      <StatePanel
        v-else-if="detailError"
        type="error"
        title="申请详情读取失败"
        :description="detailError"
      >
        <template #action>
          <el-button type="primary" :icon="Refresh" @click="loadDetail">重新加载</el-button>
        </template>
      </StatePanel>

      <template v-else-if="detailRequest">
        <div class="detail-heading">
          <div>
            <span>申请 #{{ detailRequest.id }}</span>
            <h2>{{ projectTitle(detailRequest) }}</h2>
          </div>
          <el-tag :type="statusTagType(detailRequest.status)" effect="light">
            {{ statusLabel(detailRequest.status) }}
          </el-tag>
        </div>

        <dl class="detail-list">
          <div>
            <dt>项目</dt>
            <dd>{{ projectTitle(detailRequest) }}（#{{ detailRequest.projectId }}）</dd>
          </div>
          <div>
            <dt>定稿成果版本</dt>
            <dd>{{ versionTitle(detailRequest) }}（记录 #{{ detailRequest.artifactVersionId }}）</dd>
          </div>
          <div>
            <dt>提交教师</dt>
            <dd>{{ personName(detailRequest.submittedByName, '未知教师') }}（#{{ detailRequest.submittedBy }}）</dd>
          </div>
          <div>
            <dt>审批负责人</dt>
            <dd>{{ personName(detailRequest.reviewerName, '未知负责人') }}（#{{ detailRequest.reviewerId }}）</dd>
          </div>
          <div>
            <dt>提交时间</dt>
            <dd>{{ displayDate(detailRequest.submittedAt) }}</dd>
          </div>
          <div>
            <dt>处理时间</dt>
            <dd>{{ displayDate(detailRequest.reviewedAt) }}</dd>
          </div>
          <div>
            <dt>创建时间</dt>
            <dd>{{ displayDate(detailRequest.createdAt) }}</dd>
          </div>
          <div>
            <dt>最近更新</dt>
            <dd>{{ displayDate(detailRequest.updatedAt) }}</dd>
          </div>
          <div class="detail-list__wide">
            <dt>审批意见</dt>
            <dd class="detail-note">{{ detailRequest.reviewNote || '暂无审批意见' }}</dd>
          </div>
        </dl>

        <div v-if="isLeader && detailRequest.status === 'SUBMITTED'" class="detail-actions">
          <el-button
            type="success"
            plain
            :icon="CircleCheck"
            @click="openReviewDialog(detailRequest, 'APPROVED')"
          >
            通过申请
          </el-button>
          <el-button
            type="warning"
            plain
            :icon="RefreshLeft"
            @click="openReviewDialog(detailRequest, 'REVISION_REQUIRED')"
          >
            退回修改
          </el-button>
        </div>
        <div v-else-if="isTeacher && detailRequest.status === 'SUBMITTED'" class="detail-actions">
          <el-button
            type="danger"
            plain
            :icon="CloseBold"
            :loading="cancellingId === detailRequest.id"
            @click="handleCancel(detailRequest)"
          >
            撤回申请
          </el-button>
        </div>
      </template>
    </el-drawer>
  </section>
</template>

<script setup lang="ts">
import {
  cancelApprovalRequest,
  getApprovalRequest,
  listApprovalRequests,
  reviewApprovalRequest,
  submitApprovalRequest,
  type ApprovalRequest,
  type ApprovalReviewStatus,
  type ApprovalStatus,
} from '@/api/approvals';
import {
  getCollaborationReferenceData,
  type TeacherOption,
} from '@/api/collaboration';
import {
  finalizeArtifactVersion,
  listArtifactVersions,
  type ArtifactVersion,
} from '@/api/artifactVersions';
import { listProjects, type TeachingProject } from '@/api/projects';
import PageHeader from '@/components/PageHeader.vue';
import StatePanel from '@/components/StatePanel.vue';
import { useAuthStore } from '@/stores/auth';
import { formatFullDateTime } from '@/utils/presentation';
import {
  CircleCheck,
  CloseBold,
  Refresh,
  RefreshLeft,
  Upload,
  User,
  View as ViewIcon,
} from '@element-plus/icons-vue';
import {
  ElMessage,
  ElMessageBox,
  type FormInstance,
  type FormRules,
  type TagProps,
} from 'element-plus';
import { computed, reactive, ref, watch } from 'vue';

type StatusFilter = ApprovalStatus | 'ALL';
type TagType = TagProps['type'];

interface SubmissionForm {
  projectId?: number;
  artifactVersionId?: number;
  reviewerId?: number;
}

interface ReviewForm {
  status: ApprovalReviewStatus;
  note: string;
}

const auth = useAuthStore();
const requests = ref<ApprovalRequest[]>([]);
const loading = ref(false);
const errorMessage = ref('');
const statusFilter = ref<StatusFilter>('ALL');
let requestSequence = 0;

const submissionDialogVisible = ref(false);
const submitting = ref(false);
const cancellingId = ref<number | null>(null);
const submissionError = ref('');
const referenceLoading = ref(false);
const reviewers = ref<TeacherOption[]>([]);
const projects = ref<TeachingProject[]>([]);
const artifactVersions = ref<ArtifactVersion[]>([]);
const artifactVersionsLoading = ref(false);
const artifactVersionsError = ref('');
const finalizingVersionId = ref<number | null>(null);
let artifactVersionSequence = 0;
const submissionFormRef = ref<FormInstance>();
const submissionForm = reactive<SubmissionForm>({
  projectId: undefined,
  artifactVersionId: undefined,
  reviewerId: undefined,
});

const reviewDialogVisible = ref(false);
const reviewing = ref(false);
const reviewError = ref('');
const reviewFormRef = ref<FormInstance>();
const reviewForm = reactive<ReviewForm>({
  status: 'APPROVED',
  note: '',
});
const selectedRequest = ref<ApprovalRequest | null>(null);

const availableArtifactVersions = computed(() => artifactVersions.value.filter(
  (version) => version.artifactCount > 0,
));
const selectedArtifactVersion = computed(() => availableArtifactVersions.value.find(
  (version) => version.id === submissionForm.artifactVersionId,
));

const detailDrawerVisible = ref(false);
const detailLoading = ref(false);
const detailError = ref('');
const detailRequest = ref<ApprovalRequest | null>(null);
let detailRequestSequence = 0;

const statusLabels: Record<ApprovalStatus, string> = {
  SUBMITTED: '待审批',
  APPROVED: '已通过',
  REVISION_REQUIRED: '需修改',
  CANCELLED: '已取消',
};

const statusFilterOptions: { value: StatusFilter; label: string }[] = [
  { value: 'ALL', label: '全部状态' },
  { value: 'SUBMITTED', label: statusLabels.SUBMITTED },
  { value: 'APPROVED', label: statusLabels.APPROVED },
  { value: 'REVISION_REQUIRED', label: statusLabels.REVISION_REQUIRED },
  { value: 'CANCELLED', label: statusLabels.CANCELLED },
];

const submissionRules: FormRules<SubmissionForm> = {
  projectId: [
    {
      validator: positiveIntegerValidator('请输入项目 ID', '项目 ID 必须是正整数'),
      trigger: ['blur', 'change'],
    },
  ],
  artifactVersionId: [
    {
      validator: positiveIntegerValidator('请选择成果版本', '成果版本必须是有效版本'),
      trigger: ['blur', 'change'],
    },
  ],
  reviewerId: [
    {
      validator: positiveIntegerValidator('请输入审批负责人用户 ID', '审批负责人用户 ID 必须是正整数'),
      trigger: ['blur', 'change'],
    },
  ],
};

const reviewRules: FormRules<ReviewForm> = {
  note: [
    {
      validator: (_rule, value, callback) => {
        const note = typeof value === 'string' ? value.trim() : '';
        if (reviewForm.status === 'REVISION_REQUIRED' && !note) {
          callback(new Error('退回修改时必须填写修改说明'));
          return;
        }
        if (note.length > 5000) {
          callback(new Error('审批意见不能超过 5000 个字符'));
          return;
        }
        callback();
      },
      trigger: ['blur', 'change'],
    },
  ],
};

const isTeacher = computed(() => auth.activeRole === 'TEACHER');
const isLeader = computed(() => auth.activeRole === 'LEADER');
const canAccess = computed(() => isTeacher.value || isLeader.value);
const roleLabel = computed(() => {
  if (isTeacher.value) return '教师';
  if (isLeader.value) return '教研负责人';
  return '学生';
});
const pageTitle = computed(() => {
  if (isTeacher.value) return '我的成果审批';
  if (isLeader.value) return '成果审批';
  return '成果审批';
});
const pageDescription = computed(() => {
  if (isTeacher.value) return '提交已定稿的固定成果版本，并跟踪负责人给出的审批结果。';
  if (isLeader.value) return '审核分配给你的定稿成果申请，确认通过或退回教师修改。';
  return '成果审批仅对教师与教研负责人开放。';
});
const emptyDescription = computed(() => {
  if (statusFilter.value !== 'ALL') return `没有${statusLabel(statusFilter.value)}的申请`;
  return isLeader.value ? '当前没有分配给你的审批申请' : '尚未提交成果审批申请';
});
const reviewDialogTitle = computed(() => (
  reviewForm.status === 'APPROVED' ? '通过审批申请' : '退回修改'
));
const reviewNoteLabel = computed(() => (
  reviewForm.status === 'APPROVED' ? '审批意见（可选）' : '修改说明'
));
const reviewNotePlaceholder = computed(() => (
  reviewForm.status === 'APPROVED'
    ? '可填写通过意见或后续发布注意事项'
    : '请明确说明需要修改的内容与验收要求'
));

watch(
  () => auth.activeRole,
  () => {
    requestSequence += 1;
    requests.value = [];
    errorMessage.value = '';
    statusFilter.value = 'ALL';
    closeOverlays();
    if (canAccess.value) void loadRequests();
    else loading.value = false;
  },
  { immediate: true },
);

watch(
  () => submissionForm.projectId,
  (projectId) => {
    clearArtifactVersionState();
    if (projectId && submissionDialogVisible.value) {
      void loadArtifactVersions(projectId);
    }
  },
);

async function loadRequests() {
  if (!canAccess.value) return;
  const sequence = ++requestSequence;
  loading.value = true;
  errorMessage.value = '';
  try {
    const status = statusFilter.value === 'ALL' ? undefined : statusFilter.value;
    const data = await listApprovalRequests(status);
    if (sequence === requestSequence) requests.value = data;
  } catch (error) {
    if (sequence === requestSequence) {
      errorMessage.value = resolveError(error, '暂时无法读取审批申请，请稍后重试。');
    }
  } finally {
    if (sequence === requestSequence) loading.value = false;
  }
}

function handleFilterChange() {
  void loadRequests();
}

async function handleSubmission() {
  if (!submissionFormRef.value || submitting.value) return;
  const valid = await submissionFormRef.value.validate().catch(() => false);
  if (!valid) return;

  const { projectId, artifactVersionId, reviewerId } = submissionForm;
  if (!projectId || !artifactVersionId || !reviewerId) return;

  submitting.value = true;
  submissionError.value = '';
  try {
    const created = await submitApprovalRequest({ projectId, artifactVersionId, reviewerId });
    statusFilter.value = 'SUBMITTED';
    requests.value = [created];
    submissionDialogVisible.value = false;
    ElMessage.success('定稿成果已提交审批');
    void loadRequests();
  } catch (error) {
    submissionError.value = resolveError(error, '提交失败，请核对项目、版本和审批负责人后重试。');
  } finally {
    submitting.value = false;
  }
}

async function loadArtifactVersions(projectId: number) {
  const sequence = ++artifactVersionSequence;
  artifactVersionsLoading.value = true;
  artifactVersionsError.value = '';
  try {
    const data = await listArtifactVersions(projectId);
    if (sequence === artifactVersionSequence) {
      artifactVersions.value = data;
      if (!data.some((version) => version.id === submissionForm.artifactVersionId)) {
        submissionForm.artifactVersionId = undefined;
      }
    }
  } catch (error) {
    if (sequence === artifactVersionSequence) {
      artifactVersionsError.value = resolveError(error, '暂时无法读取该项目的成果版本，请稍后重试。');
    }
  } finally {
    if (sequence === artifactVersionSequence) artifactVersionsLoading.value = false;
  }
}

async function handleFinalizeVersion() {
  const projectId = submissionForm.projectId;
  const version = selectedArtifactVersion.value;
  if (!projectId || !version || version.finalVersion || finalizingVersionId.value !== null) return;

  finalizingVersionId.value = version.id;
  artifactVersionsError.value = '';
  try {
    const finalized = await finalizeArtifactVersion(projectId, version.id);
    artifactVersions.value = artifactVersions.value.map((item) => (
      item.projectId === finalized.projectId
        ? { ...item, finalVersion: item.id === finalized.id ? true : false }
        : item
    ));
    ElMessage.success(`v${finalized.versionNumber} 已定稿`);
  } catch (error) {
    artifactVersionsError.value = resolveError(error, '版本定稿失败，请稍后重试。');
  } finally {
    finalizingVersionId.value = null;
  }
}

async function openSubmissionDialog() {
  submissionDialogVisible.value = true;
  if ((reviewers.value.length > 0 && projects.value.length > 0) || referenceLoading.value) {
    if (!submissionForm.projectId && projects.value.length === 1) {
      submissionForm.projectId = projects.value[0].id;
    }
    return;
  }
  referenceLoading.value = true;
  submissionError.value = '';
  try {
    const [referenceData, projectItems] = await Promise.all([
      getCollaborationReferenceData(),
      listProjects(),
    ]);
    reviewers.value = referenceData.leaders;
    projects.value = projectItems;
    if (reviewers.value.length === 1) submissionForm.reviewerId = reviewers.value[0].id;
    if (projects.value.length === 1) submissionForm.projectId = projects.value[0].id;
  } catch (error) {
    submissionError.value = resolveError(error, '暂时无法读取项目或审批负责人，请稍后重试。');
  } finally {
    referenceLoading.value = false;
  }
}

async function handleCancel(request: ApprovalRequest) {
  if (cancellingId.value !== null) return;
  try {
    await ElMessageBox.confirm(
      `确认撤回“${projectTitle(request)}”的审批申请吗？`,
      '撤回审批申请',
      { confirmButtonText: '确认撤回', cancelButtonText: '保留申请', type: 'warning' },
    );
  } catch {
    return;
  }

  cancellingId.value = request.id;
  try {
    const updated = await cancelApprovalRequest(request.id);
    replaceRequest(updated);
    if (detailRequest.value?.id === request.id) detailDrawerVisible.value = false;
    ElMessage.success('审批申请已撤回');
  } catch (error) {
    ElMessage.error(resolveError(error, '撤回失败，申请状态可能已变化，请刷新后重试。'));
  } finally {
    cancellingId.value = null;
  }
}

function resetSubmissionForm() {
  clearArtifactVersionState();
  Object.assign(submissionForm, {
    projectId: undefined,
    artifactVersionId: undefined,
    reviewerId: undefined,
  });
  submissionError.value = '';
  submissionFormRef.value?.clearValidate();
}

function clearArtifactVersionState() {
  artifactVersionSequence += 1;
  artifactVersions.value = [];
  artifactVersionsLoading.value = false;
  artifactVersionsError.value = '';
  finalizingVersionId.value = null;
  submissionForm.artifactVersionId = undefined;
}

function reloadArtifactVersions() {
  if (submissionForm.projectId) void loadArtifactVersions(submissionForm.projectId);
}

function openReviewDialog(request: ApprovalRequest, status: ApprovalReviewStatus) {
  selectedRequest.value = request;
  reviewForm.status = status;
  reviewForm.note = '';
  reviewError.value = '';
  detailDrawerVisible.value = false;
  reviewDialogVisible.value = true;
}

async function handleReview() {
  if (!selectedRequest.value || !reviewFormRef.value || reviewing.value) return;
  const valid = await reviewFormRef.value.validate().catch(() => false);
  if (!valid) return;

  reviewing.value = true;
  reviewError.value = '';
  try {
    const updated = await reviewApprovalRequest(selectedRequest.value.id, {
      status: reviewForm.status,
      note: reviewForm.note.trim(),
    });
    replaceRequest(updated);
    reviewDialogVisible.value = false;
    ElMessage.success(updated.status === 'APPROVED' ? '审批申请已通过' : '审批申请已退回修改');
  } catch (error) {
    reviewError.value = resolveError(error, '审批处理失败，申请状态可能已变化，请刷新后重试。');
  } finally {
    reviewing.value = false;
  }
}

function resetReviewForm() {
  selectedRequest.value = null;
  reviewForm.status = 'APPROVED';
  reviewForm.note = '';
  reviewError.value = '';
  reviewFormRef.value?.clearValidate();
}

function replaceRequest(updated: ApprovalRequest) {
  if (statusFilter.value !== 'ALL' && statusFilter.value !== updated.status) {
    requests.value = requests.value.filter((request) => request.id !== updated.id);
  } else {
    const index = requests.value.findIndex((request) => request.id === updated.id);
    if (index >= 0) requests.value.splice(index, 1, updated);
    else requests.value.unshift(updated);
  }
  if (detailRequest.value?.id === updated.id) detailRequest.value = updated;
}

function openDetail(request: ApprovalRequest) {
  detailRequest.value = request;
  detailError.value = '';
  detailDrawerVisible.value = true;
  void loadDetail();
}

async function loadDetail() {
  const approvalRequestId = detailRequest.value?.id;
  if (!approvalRequestId) return;
  const sequence = ++detailRequestSequence;
  detailLoading.value = true;
  detailError.value = '';
  try {
    const data = await getApprovalRequest(approvalRequestId);
    if (sequence === detailRequestSequence) detailRequest.value = data;
  } catch (error) {
    if (sequence === detailRequestSequence) {
      detailError.value = resolveError(error, '暂时无法读取这条审批申请的详情。');
    }
  } finally {
    if (sequence === detailRequestSequence) detailLoading.value = false;
  }
}

function resetDetail() {
  detailRequestSequence += 1;
  detailRequest.value = null;
  detailError.value = '';
  detailLoading.value = false;
}

function closeOverlays() {
  submissionDialogVisible.value = false;
  reviewDialogVisible.value = false;
  detailDrawerVisible.value = false;
}

function positiveIntegerValidator(requiredMessage: string, invalidMessage: string) {
  return (_rule: unknown, value: unknown, callback: (error?: Error) => void) => {
    if (value === undefined || value === null || value === '') {
      callback(new Error(requiredMessage));
      return;
    }
    if (typeof value !== 'number' || !Number.isSafeInteger(value) || value <= 0) {
      callback(new Error(invalidMessage));
      return;
    }
    callback();
  };
}

function statusLabel(status: ApprovalStatus) {
  return statusLabels[status];
}

function statusTagType(status: ApprovalStatus): TagType {
  if (status === 'APPROVED') return 'success';
  if (status === 'REVISION_REQUIRED') return 'danger';
  if (status === 'SUBMITTED') return 'warning';
  return 'info';
}

function projectTitle(request: ApprovalRequest) {
  return request.projectName?.trim() || `项目 #${request.projectId}`;
}

function artifactVersionLabel(version: ArtifactVersion) {
  const description = version.description?.trim();
  return description ? `v${version.versionNumber} · ${description}` : `v${version.versionNumber}`;
}

function versionTitle(request: ApprovalRequest) {
  return request.artifactVersionNumber == null
    ? '固定版本'
    : `v${request.artifactVersionNumber}`;
}

function personName(name: string | null | undefined, fallback: string) {
  return name?.trim() || fallback;
}

function displayDate(value?: string | null) {
  return formatFullDateTime(value || undefined);
}

function resolveError(error: unknown, fallback: string) {
  const message = (error as { response?: { data?: { message?: string } } }).response?.data?.message;
  return message && !/(Exception|java\.|Axios)/i.test(message) ? message : fallback;
}
</script>

<style scoped>
.approval-requests-page {
  min-width: 0;
}

.role-context {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: var(--color-text-secondary);
  font-size: 13px;
}

.approval-panel {
  min-width: 0;
  overflow: hidden;
}

.approval-toolbar {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 16px;
  min-height: 72px;
  padding: 14px 18px;
  border-bottom: 1px solid var(--color-border);
}

.approval-filter {
  display: grid;
  gap: 6px;
  color: var(--color-text-secondary);
  font-size: 12px;
  font-weight: 650;
}

.approval-filter :deep(.el-select) {
  width: 180px;
}

.approval-toolbar__count {
  padding-bottom: 11px;
  color: var(--color-text-muted);
  font-size: 13px;
  white-space: nowrap;
}

.approval-panel__alert {
  margin: 14px 18px 0;
}

.identity-cell,
.person-cell,
.time-cell {
  min-width: 0;
}

.identity-cell strong,
.identity-cell span,
.person-cell strong,
.person-cell span,
.time-cell span {
  display: block;
}

.identity-cell strong,
.person-cell strong {
  overflow: hidden;
  color: var(--color-text);
  line-height: 20px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.identity-cell span,
.person-cell span,
.time-cell span {
  margin-top: 3px;
  color: var(--color-text-muted);
  font-size: 12px;
  line-height: 17px;
}

.time-cell span:first-child {
  margin-top: 0;
}

.row-actions,
.mobile-actions,
.detail-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.row-actions {
  justify-content: flex-end;
}

.row-actions .el-button + .el-button,
.mobile-actions .el-button + .el-button,
.detail-actions .el-button + .el-button {
  margin-left: 0;
}

.approval-mobile-list {
  display: none;
  margin: 0;
  padding: 0;
  list-style: none;
}

.submission-id-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 14px;
}

.submission-id-grid__wide {
  grid-column: 1 / -1;
}

.submission-id-grid :deep(.el-input-number) {
  width: 100%;
}

.artifact-version-picker {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  width: 100%;
}

.artifact-version-picker :deep(.el-select) {
  min-width: 0;
  flex: 1;
}

.artifact-version-option {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  width: 100%;
}

.artifact-version-state {
  margin-top: 8px;
}

.artifact-version-state--empty {
  display: block;
  color: var(--color-text-muted);
  font-size: 12px;
  line-height: 1.5;
}

.dialog-request-context {
  margin: -4px 0 18px;
  padding: 11px 13px;
  border-left: 3px solid var(--color-primary);
  background: var(--color-primary-soft);
}

.dialog-request-context strong,
.dialog-request-context span {
  display: block;
}

.dialog-request-context strong {
  color: var(--color-text);
  font-size: 14px;
}

.dialog-request-context span {
  margin-top: 4px;
  color: var(--color-text-secondary);
  font-size: 12px;
  line-height: 1.55;
}

.detail-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  padding-bottom: 18px;
  border-bottom: 1px solid var(--color-border);
}

.detail-heading span,
.detail-heading h2 {
  display: block;
  margin: 0;
}

.detail-heading span {
  color: var(--color-text-muted);
  font-size: 12px;
}

.detail-heading h2 {
  margin-top: 5px;
  color: var(--color-text);
  font-size: 19px;
  line-height: 1.4;
  overflow-wrap: anywhere;
}

.detail-list {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0;
  margin: 0;
}

.detail-list > div {
  min-width: 0;
  padding: 16px 0;
  border-bottom: 1px solid var(--color-border);
}

.detail-list > div:nth-child(odd):not(.detail-list__wide) {
  padding-right: 18px;
}

.detail-list__wide {
  grid-column: 1 / -1;
}

.detail-list dt,
.detail-list dd {
  margin: 0;
}

.detail-list dt {
  margin-bottom: 5px;
  color: var(--color-text-muted);
  font-size: 12px;
  font-weight: 650;
}

.detail-list dd {
  color: var(--color-text);
  font-size: 13px;
  line-height: 1.6;
  overflow-wrap: anywhere;
}

.detail-note {
  white-space: pre-wrap;
}

.detail-actions {
  justify-content: flex-end;
  padding-top: 20px;
}

@media (max-width: 960px) {
  .approval-table {
    display: none;
  }

  .approval-mobile-list {
    display: block;
  }

  .approval-mobile-row {
    padding: 18px;
    border-bottom: 1px solid var(--color-border);
  }

  .approval-mobile-row:last-child {
    border-bottom: 0;
  }

  .approval-mobile-row__heading {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    gap: 14px;
  }

  .approval-mobile-row__facts {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 14px 20px;
    margin: 16px 0;
  }

  .approval-mobile-row__facts div,
  .approval-mobile-row__facts dt,
  .approval-mobile-row__facts dd {
    min-width: 0;
    margin: 0;
  }

  .approval-mobile-row__facts dt {
    margin-bottom: 4px;
    color: var(--color-text-muted);
    font-size: 12px;
    font-weight: 650;
  }

  .approval-mobile-row__facts dd {
    color: var(--color-text-secondary);
    font-size: 13px;
    line-height: 1.5;
    overflow-wrap: anywhere;
  }

  .mobile-actions {
    flex-wrap: wrap;
  }
}

@media (max-width: 560px) {
  .approval-toolbar {
    align-items: stretch;
    flex-direction: column;
  }

  .approval-filter :deep(.el-select) {
    width: 100%;
  }

  .approval-toolbar__count {
    align-self: flex-end;
    padding-bottom: 0;
  }

  .submission-id-grid,
  .detail-list,
  .approval-mobile-row__facts {
    grid-template-columns: 1fr;
  }

  .submission-id-grid__wide,
  .detail-list__wide {
    grid-column: auto;
  }

  .artifact-version-picker {
    align-items: stretch;
    flex-direction: column;
  }

  .artifact-version-picker .el-button {
    width: 100%;
  }

  .detail-list > div:nth-child(odd):not(.detail-list__wide) {
    padding-right: 0;
  }

  .mobile-actions,
  .detail-actions {
    align-items: stretch;
    flex-direction: column;
  }

  .mobile-actions .el-button,
  .detail-actions .el-button {
    width: 100%;
  }
}
</style>
