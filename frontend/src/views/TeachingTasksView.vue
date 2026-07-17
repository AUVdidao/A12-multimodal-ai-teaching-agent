<template>
  <section class="page teaching-tasks-page">
    <PageHeader
      eyebrow="协同备课"
      :title="isLeader ? '教学任务管理' : '我的教学任务'"
      :description="pageDescription"
    >
      <template #meta>
        <span class="role-context">
          <el-icon><User /></el-icon>
          当前身份：{{ roleLabel }}
        </span>
      </template>
      <template #actions>
        <el-tooltip content="刷新任务" placement="bottom">
          <el-button
            circle
            :icon="Refresh"
            :loading="loading"
            aria-label="刷新教学任务"
            @click="loadTasks"
          />
        </el-tooltip>
        <el-button v-if="isLeader" type="primary" :icon="Plus" @click="createDialogVisible = true">
          新建并分配
        </el-button>
      </template>
    </PageHeader>

    <StatePanel
      v-if="loading && tasks.length === 0"
      type="loading"
      title="正在读取教学任务"
      :description="isLeader ? '正在汇总授权课程内的任务。' : '正在读取分配给你的任务。'"
    />
    <StatePanel
      v-else-if="!canAccess"
      type="error"
      title="当前身份无法访问教学任务"
      description="请切换为教师或教研负责人身份后再试。"
    />
    <StatePanel
      v-else-if="errorMessage && tasks.length === 0"
      type="error"
      title="教学任务读取失败"
      :description="errorMessage"
    >
      <template #action>
        <el-button type="primary" :icon="Refresh" @click="loadTasks">重新加载</el-button>
      </template>
    </StatePanel>

    <section v-else class="surface-panel task-panel" v-loading="loading">
      <div class="task-toolbar">
        <div class="task-toolbar__filters">
          <el-input
            v-model="keyword"
            clearable
            :prefix-icon="Search"
            placeholder="搜索任务、课程、章节或负责人"
            aria-label="搜索教学任务"
          />
          <el-select v-model="statusFilter" aria-label="按任务状态筛选">
            <el-option
              v-for="option in statusFilterOptions"
              :key="option.value"
              :label="option.label"
              :value="option.value"
            />
          </el-select>
        </div>
        <span class="task-toolbar__count">共 {{ filteredTasks.length }} 项<template v-if="filteredTasks.length !== tasks.length">（全部 {{ tasks.length }} 项）</template></span>
      </div>

      <el-alert
        v-if="errorMessage"
        class="task-panel__alert"
        type="error"
        :title="errorMessage"
        show-icon
        :closable="false"
      />

      <el-table v-if="filteredTasks.length > 0" :data="pagedTasks" row-key="id" table-layout="fixed">
        <el-table-column type="expand" width="44">
          <template #default="{ row }">
            <div class="task-detail">
              <div class="task-detail__wide">
                <span>任务要求</span>
                <p>{{ row.requirements || '未填写任务要求' }}</p>
              </div>
              <div>
                <span>关联项目</span>
                <strong>{{ row.linkedProjectId ? `#${row.linkedProjectId}` : '尚未关联' }}</strong>
              </div>
              <div>
                <span>任务编号</span>
                <strong>#{{ row.id }}</strong>
              </div>
              <div v-if="row.submissionNote" class="task-detail__wide">
                <span>提交说明</span>
                <p>{{ row.submissionNote }}</p>
              </div>
              <div v-if="row.reviewNote" class="task-detail__wide">
                <span>负责人反馈</span>
                <p>{{ row.reviewNote }}</p>
              </div>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="教学任务" min-width="230">
          <template #default="{ row }">
            <div class="task-identity">
              <strong>{{ row.taskName }}</strong>
              <span>#{{ row.id }}</span>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="课程与章节" min-width="190">
          <template #default="{ row }">
            <div class="course-cell">
              <strong>{{ row.courseName }}</strong>
              <span>{{ row.chapterTitle }}</span>
            </div>
          </template>
        </el-table-column>

        <el-table-column v-if="isLeader" label="负责人" min-width="130">
          <template #default="{ row }">
            <div class="assignee-cell">
              <el-icon><User /></el-icon>
              <span>{{ row.assigneeName }}</span>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="优先级" width="92" align="center">
          <template #default="{ row }">
            <el-tag :type="priorityTagType(row.priority)" effect="light">
              {{ priorityLabel(row.priority) }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column label="状态" width="118" align="center">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.taskStatus)" effect="light">
              {{ statusLabel(row.taskStatus) }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column label="截止时间" width="170">
          <template #default="{ row }">
            <div class="due-cell">
              <time :datetime="row.dueAt">{{ formatFullDateTime(row.dueAt) }}</time>
              <el-tag v-if="row.overdue" type="danger" effect="dark" size="small">已逾期</el-tag>
            </div>
          </template>
        </el-table-column>

        <el-table-column v-if="isTeacher" label="操作" width="224" align="right" fixed="right">
          <template #default="{ row }">
            <div v-if="canUpdate(row) || canSubmit(row)" class="task-row-actions">
              <el-button
                v-if="canUpdate(row)"
                plain
                :icon="EditPen"
                @click="openProgressDialog(row)"
              >
                编辑进度
              </el-button>
              <el-button
                v-if="canSubmit(row)"
                type="primary"
                :icon="Upload"
                @click="openSubmissionDialog(row)"
              >
                提交
              </el-button>
            </div>
            <span v-else class="muted">当前不可操作</span>
          </template>
        </el-table-column>
      </el-table>

      <div v-if="filteredTasks.length > pageSize" class="task-pagination">
        <el-pagination
          v-model:current-page="currentPage"
          :page-size="pageSize"
          :total="filteredTasks.length"
          layout="prev, pager, next"
        />
      </div>

      <el-empty v-else-if="filteredTasks.length === 0" :description="emptyDescription" :image-size="72">
        <el-button v-if="tasks.length > 0" :icon="Refresh" @click="clearFilters">清除筛选</el-button>
        <el-button v-else-if="isLeader" type="primary" :icon="Plus" @click="createDialogVisible = true">创建第一项任务</el-button>
      </el-empty>
    </section>

    <el-dialog
      v-model="createDialogVisible"
      title="创建并分配教学任务"
      width="680px"
      destroy-on-close
      :close-on-click-modal="false"
      @closed="resetCreateForm"
    >
      <el-form ref="createFormRef" :model="createForm" :rules="createRules" label-position="top">
        <el-form-item label="任务名称" prop="taskName">
          <el-input
            v-model="createForm.taskName"
            maxlength="160"
            show-word-limit
            placeholder="输入清晰、可识别的备课任务名称"
          />
        </el-form-item>

        <div class="task-form-grid">
          <el-form-item label="课程" prop="courseId">
            <el-select v-model="createForm.courseId" filterable placeholder="选择课程">
              <el-option
                v-for="course in referenceData.courses"
                :key="course.id"
                :label="`${course.courseName}（${course.courseCode}）`"
                :value="course.id"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="负责人" prop="assigneeId">
            <el-select v-model="createForm.assigneeId" filterable placeholder="选择教师">
              <el-option
                v-for="teacher in referenceData.teachers"
                :key="teacher.id"
                :label="`${teacher.displayName}（${teacher.username}）`"
                :value="teacher.id"
              />
            </el-select>
          </el-form-item>
        </div>

        <el-form-item label="授课班级（可选）" prop="classId">
          <el-select
            v-model="createForm.classId"
            clearable
            filterable
            :disabled="!createForm.courseId"
            placeholder="选择课程后可指定班级"
          >
            <el-option
              v-for="classGroup in availableClasses"
              :key="classGroup.id"
              :label="`${classGroup.className}${classGroup.cohort ? ` · ${classGroup.cohort}` : ''}`"
              :value="classGroup.id"
            />
          </el-select>
        </el-form-item>

        <el-form-item label="章节主题" prop="chapterTitle">
          <el-input
            v-model="createForm.chapterTitle"
            maxlength="160"
            show-word-limit
            placeholder="输入本次任务对应的章节或主题"
          />
        </el-form-item>

        <el-form-item label="任务要求" prop="requirements">
          <el-input
            v-model="createForm.requirements"
            type="textarea"
            :rows="4"
            maxlength="5000"
            show-word-limit
            resize="vertical"
            placeholder="说明交付范围、内容要求和验收重点"
          />
        </el-form-item>

        <div class="task-form-grid">
          <el-form-item label="优先级" prop="priority">
            <el-select v-model="createForm.priority">
              <el-option v-for="option in priorityOptions" :key="option.value" :label="option.label" :value="option.value" />
            </el-select>
          </el-form-item>
          <el-form-item label="截止时间" prop="dueAt">
            <el-date-picker
              v-model="createForm.dueAt"
              type="datetime"
              format="YYYY-MM-DD HH:mm"
              value-format="YYYY-MM-DDTHH:mm:ss"
              placeholder="选择截止日期与时间"
              :disabled-date="disablePastDates"
            />
          </el-form-item>
        </div>

        <el-alert
          v-if="createError"
          type="error"
          :title="createError"
          show-icon
          :closable="false"
        />
      </el-form>

      <template #footer>
        <el-button :disabled="creating" @click="createDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="handleCreateTask">创建并分配</el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="progressDialogVisible"
      title="更新任务进度"
      width="520px"
      :close-on-click-modal="false"
      @closed="progressError = ''"
    >
      <p v-if="selectedTask" class="dialog-task-name">{{ selectedTask.taskName }}</p>
      <el-form label-position="top" :model="progressForm">
        <el-form-item label="任务状态">
          <el-select v-model="progressForm.taskStatus">
            <el-option
              v-for="option in teacherStatusOptions"
              :key="option.value"
              :label="option.label"
              :value="option.value"
            />
          </el-select>
        </el-form-item>
        <el-alert
          v-if="progressError"
          type="error"
          :title="progressError"
          show-icon
          :closable="false"
        />
      </el-form>
      <template #footer>
        <el-button :disabled="updating" @click="progressDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="updating" @click="handleProgressUpdate">保存更新</el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="submissionDialogVisible"
      title="提交教学任务"
      width="560px"
      :close-on-click-modal="false"
      @closed="resetSubmissionForm"
    >
      <p v-if="selectedTask" class="dialog-task-name">{{ selectedTask.taskName }}</p>
      <el-form ref="submissionFormRef" :model="submissionForm" :rules="submissionRules" label-position="top">
        <el-form-item label="提交说明" prop="note">
          <el-input
            v-model="submissionForm.note"
            type="textarea"
            :rows="6"
            maxlength="5000"
            show-word-limit
            resize="vertical"
            placeholder="说明已完成内容、交付位置及需要负责人关注的事项"
          />
        </el-form-item>
        <el-form-item label="关联教学项目（可选）" prop="linkedProjectId">
          <el-select
            v-model="submissionForm.linkedProjectId"
            :loading="projectsLoading"
            clearable
            filterable
            placeholder="选择用于交付本任务的教学项目"
          >
            <el-option
              v-for="project in projects"
              :key="project.id"
              :label="project.projectName"
              :value="project.id"
            />
          </el-select>
        </el-form-item>
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
        <el-button type="success" :loading="submitting" :icon="Upload" @click="handleSubmission">
          确认提交
        </el-button>
      </template>
    </el-dialog>
  </section>
</template>

<script setup lang="ts">
import {
  createTeachingTask,
  listTeachingTasks,
  submitTeachingTask,
  updateTeachingTaskStatus,
  type CreateTeachingTaskPayload,
  type TeachingTask,
  type TeachingTaskPriority,
  type TeachingTaskStatus,
} from '@/api/teachingTasks';
import {
  getCollaborationReferenceData,
  type CollaborationReferenceData,
} from '@/api/collaboration';
import { listProjects, type TeachingProject } from '@/api/projects';
import PageHeader from '@/components/PageHeader.vue';
import StatePanel from '@/components/StatePanel.vue';
import { useAuthStore } from '@/stores/auth';
import { formatFullDateTime } from '@/utils/presentation';
import { EditPen, Plus, Refresh, Search, Upload, User } from '@element-plus/icons-vue';
import { ElMessage, type FormInstance, type FormRules, type TagProps } from 'element-plus';
import { computed, onMounted, reactive, ref, watch } from 'vue';

type StatusFilter = TeachingTaskStatus | 'ALL';
type TagType = TagProps['type'];

interface CreateTaskForm {
  taskName: string;
  courseId?: number;
  classId?: number;
  chapterTitle: string;
  assigneeId?: number;
  requirements: string;
  priority: TeachingTaskPriority;
  dueAt: string;
}

const auth = useAuthStore();
const tasks = ref<TeachingTask[]>([]);
const loading = ref(true);
const errorMessage = ref('');
const keyword = ref('');
const statusFilter = ref<StatusFilter>('ALL');
const currentPage = ref(1);
const pageSize = 10;
const referenceData = ref<CollaborationReferenceData>({ teachers: [], leaders: [], students: [], courses: [], classes: [] });
const projects = ref<TeachingProject[]>([]);
const projectsLoading = ref(false);

const createDialogVisible = ref(false);
const creating = ref(false);
const createError = ref('');
const createFormRef = ref<FormInstance>();
const createForm = reactive<CreateTaskForm>(emptyCreateForm());

const selectedTask = ref<TeachingTask | null>(null);
const progressDialogVisible = ref(false);
const updating = ref(false);
const progressError = ref('');
const progressForm = reactive<{ taskStatus: TeachingTaskStatus }>({
  taskStatus: 'IN_PROGRESS',
});

const submissionDialogVisible = ref(false);
const submitting = ref(false);
const submissionError = ref('');
const submissionFormRef = ref<FormInstance>();
const submissionForm = reactive<{ note: string; linkedProjectId?: number }>({
  note: '',
  linkedProjectId: undefined,
});

const statusLabels: Record<TeachingTaskStatus, string> = {
  DRAFT: '草稿',
  ASSIGNED: '已分配',
  IN_PROGRESS: '进行中',
  SUBMITTED: '已提交',
  REVISION_REQUIRED: '需修改',
  COMPLETED: '已完成',
  CANCELLED: '已取消',
};

const priorityLabels: Record<TeachingTaskPriority, string> = {
  URGENT: '紧急',
  HIGH: '高',
  MEDIUM: '中',
  LOW: '低',
};

const statusOptions = (Object.entries(statusLabels) as [TeachingTaskStatus, string][]).map(([value, label]) => ({
  value,
  label,
}));
const statusFilterOptions: { value: StatusFilter; label: string }[] = [
  { value: 'ALL', label: '全部状态' },
  ...statusOptions,
];
const priorityOptions: { value: TeachingTaskPriority; label: string }[] = [
  { value: 'URGENT', label: '紧急' },
  { value: 'HIGH', label: '高优先级' },
  { value: 'MEDIUM', label: '中优先级' },
  { value: 'LOW', label: '低优先级' },
];

const createRules: FormRules<CreateTaskForm> = {
  taskName: [
    { required: true, message: '请输入任务名称', trigger: 'blur' },
    { min: 2, max: 160, message: '任务名称应为 2 到 160 个字符', trigger: 'blur' },
  ],
  courseId: [{ required: true, message: '请选择课程', trigger: 'change' }],
  chapterTitle: [{ required: true, message: '请输入章节主题', trigger: 'blur' }],
  assigneeId: [{ required: true, message: '请选择负责人', trigger: 'change' }],
  requirements: [{ required: true, message: '请输入任务要求', trigger: 'blur' }],
  priority: [{ required: true, message: '请选择优先级', trigger: 'change' }],
  dueAt: [{ required: true, message: '请选择截止时间', trigger: 'change' }],
};

const submissionRules: FormRules = {
  note: [
    { required: true, message: '请输入提交说明', trigger: 'blur' },
    { max: 5000, message: '提交说明不能超过 5000 个字符', trigger: 'blur' },
  ],
};

const isLeader = computed(() => auth.activeRole === 'LEADER');
const isTeacher = computed(() => auth.activeRole === 'TEACHER');
const canAccess = computed(() => isLeader.value || isTeacher.value);
const roleLabel = computed(() => {
  if (isLeader.value) return '教研负责人';
  if (isTeacher.value) return '教师';
  return '学生';
});
const pageDescription = computed(() => isLeader.value
  ? '查看授权课程内的教学任务，创建任务并分配给教师。'
  : '跟进分配给你的备课任务，更新状态与进度并提交完成情况。');

const availableClasses = computed(() => referenceData.value.classes.filter(
  (item) => item.courseId === createForm.courseId,
));

const filteredTasks = computed(() => {
  const query = keyword.value.trim().toLocaleLowerCase();
  return tasks.value.filter((task) => {
    if (statusFilter.value !== 'ALL' && task.taskStatus !== statusFilter.value) return false;
    if (!query) return true;
    return [
      task.taskName,
      task.courseName,
      task.chapterTitle,
      task.assigneeName,
      task.requirements,
      String(task.id),
    ].some((value) => value?.toLocaleLowerCase().includes(query));
  });
});
const pagedTasks = computed(() => {
  const start = (currentPage.value - 1) * pageSize;
  return filteredTasks.value.slice(start, start + pageSize);
});

const emptyDescription = computed(() => {
  if (tasks.value.length === 0) {
    return isLeader.value ? '暂无教学任务，可创建并分配第一项任务' : '当前没有分配给你的教学任务，请等待教研负责人分配';
  }
  return '没有符合当前筛选条件的教学任务';
});

const teacherStatusOptions = statusOptions.filter((option) => option.value === 'IN_PROGRESS');

let viewMounted = false;

onMounted(async () => {
  try {
    await auth.ensureInitialized();
  } catch (error) {
    errorMessage.value = resolveError(error, '身份信息读取失败，请重新登录后再试。');
  }
  viewMounted = true;
  await Promise.all([
    loadTasks(),
    isLeader.value ? loadReferenceData() : loadProjects(),
  ]);
});

watch(() => auth.activeRole, () => {
  if (!viewMounted) return;
  closeDialogs();
  statusFilter.value = 'ALL';
  keyword.value = '';
  void loadTasks();
  if (isLeader.value) void loadReferenceData();
  if (isTeacher.value) void loadProjects();
});

watch([keyword, statusFilter], () => {
  currentPage.value = 1;
});

watch(() => createForm.courseId, () => {
  if (createForm.classId && !availableClasses.value.some((item) => item.id === createForm.classId)) {
    createForm.classId = undefined;
  }
});

async function loadTasks() {
  if (!canAccess.value) {
    tasks.value = [];
    loading.value = false;
    return;
  }
  loading.value = true;
  errorMessage.value = '';
  try {
    tasks.value = await listTeachingTasks();
    currentPage.value = 1;
  } catch (error) {
    errorMessage.value = resolveError(error, '暂时无法读取教学任务，请稍后重试。');
  } finally {
    loading.value = false;
  }
}

function clearFilters() {
  keyword.value = '';
  statusFilter.value = 'ALL';
}

async function loadReferenceData() {
  try {
    referenceData.value = await getCollaborationReferenceData();
  } catch (error) {
    createError.value = resolveError(error, '课程、班级或教师列表读取失败，请刷新后重试。');
  }
}

function emptyCreateForm(): CreateTaskForm {
  return {
    taskName: '',
    courseId: undefined,
    classId: undefined,
    chapterTitle: '',
    assigneeId: undefined,
    requirements: '',
    priority: 'MEDIUM',
    dueAt: '',
  };
}

function resetCreateForm() {
  Object.assign(createForm, emptyCreateForm());
  createError.value = '';
  createFormRef.value?.clearValidate();
}

async function handleCreateTask() {
  if (!createFormRef.value || creating.value) return;
  const valid = await createFormRef.value.validate().catch(() => false);
  if (!valid || !createForm.courseId || !createForm.assigneeId) return;

  const payload: CreateTeachingTaskPayload = {
    taskName: createForm.taskName.trim(),
    courseId: createForm.courseId,
    ...(createForm.classId ? { classId: createForm.classId } : {}),
    chapterTitle: createForm.chapterTitle.trim(),
    assigneeId: createForm.assigneeId,
    requirements: createForm.requirements.trim(),
    priority: createForm.priority,
    dueAt: createForm.dueAt,
  };

  creating.value = true;
  createError.value = '';
  try {
    const created = await createTeachingTask(payload);
    tasks.value = [created, ...tasks.value.filter((task) => task.id !== created.id)];
    createDialogVisible.value = false;
    ElMessage.success('教学任务已创建并分配');
  } catch (error) {
    createError.value = resolveError(error, '任务创建失败，请检查填写内容后重试。');
  } finally {
    creating.value = false;
  }
}

function openProgressDialog(task: TeachingTask) {
  selectedTask.value = task;
  progressForm.taskStatus = 'IN_PROGRESS';
  progressError.value = '';
  progressDialogVisible.value = true;
}

async function handleProgressUpdate() {
  if (!selectedTask.value || updating.value) return;
  updating.value = true;
  progressError.value = '';
  try {
    const updated = await updateTeachingTaskStatus(selectedTask.value.id, {
      status: progressForm.taskStatus,
    });
    replaceTask(updated);
    progressDialogVisible.value = false;
    ElMessage.success('任务状态与进度已更新');
  } catch (error) {
    progressError.value = resolveError(error, '任务更新失败，请稍后重试。');
  } finally {
    updating.value = false;
  }
}

function openSubmissionDialog(task: TeachingTask) {
  selectedTask.value = task;
  submissionForm.note = task.submissionNote || '';
  submissionForm.linkedProjectId = task.linkedProjectId || undefined;
  submissionError.value = '';
  submissionDialogVisible.value = true;
  if (projects.value.length === 0) void loadProjects();
}

async function loadProjects() {
  if (!isTeacher.value || projectsLoading.value) return;
  projectsLoading.value = true;
  try {
    projects.value = await listProjects();
  } catch (error) {
    submissionError.value = resolveError(error, '教学项目列表读取失败，请稍后重试。');
  } finally {
    projectsLoading.value = false;
  }
}

function resetSubmissionForm() {
  submissionForm.note = '';
  submissionForm.linkedProjectId = undefined;
  submissionError.value = '';
  submissionFormRef.value?.clearValidate();
}

async function handleSubmission() {
  if (!selectedTask.value || !submissionFormRef.value || submitting.value) return;
  const valid = await submissionFormRef.value.validate().catch(() => false);
  if (!valid) return;

  submitting.value = true;
  submissionError.value = '';
  try {
    const updated = await submitTeachingTask(selectedTask.value.id, {
      note: submissionForm.note.trim(),
      ...(submissionForm.linkedProjectId ? { linkedProjectId: submissionForm.linkedProjectId } : {}),
    });
    replaceTask(updated);
    submissionDialogVisible.value = false;
    ElMessage.success('教学任务已提交');
  } catch (error) {
    submissionError.value = resolveError(error, '任务提交失败，请稍后重试。');
  } finally {
    submitting.value = false;
  }
}

function replaceTask(updated: TeachingTask) {
  const index = tasks.value.findIndex((task) => task.id === updated.id);
  if (index >= 0) tasks.value.splice(index, 1, updated);
  else tasks.value.unshift(updated);
  selectedTask.value = updated;
}

function closeDialogs() {
  createDialogVisible.value = false;
  progressDialogVisible.value = false;
  submissionDialogVisible.value = false;
  selectedTask.value = null;
}

function canUpdate(task: TeachingTask) {
  return ['ASSIGNED', 'REVISION_REQUIRED'].includes(task.taskStatus);
}

function canSubmit(task: TeachingTask) {
  return ['ASSIGNED', 'IN_PROGRESS', 'REVISION_REQUIRED'].includes(task.taskStatus);
}

function statusTagType(status: TeachingTaskStatus): TagType {
  if (status === 'COMPLETED' || status === 'SUBMITTED') return 'success';
  if (status === 'REVISION_REQUIRED') return 'danger';
  if (status === 'IN_PROGRESS') return 'warning';
  if (status === 'ASSIGNED') return 'primary';
  return 'info';
}

function statusLabel(status: TeachingTaskStatus) {
  return statusLabels[status];
}

function priorityTagType(priority: TeachingTaskPriority): TagType {
  if (priority === 'URGENT') return 'danger';
  if (priority === 'HIGH') return 'danger';
  if (priority === 'MEDIUM') return 'warning';
  return 'info';
}

function priorityLabel(priority: TeachingTaskPriority) {
  return priorityLabels[priority];
}

function disablePastDates(date: Date) {
  const today = new Date();
  today.setHours(0, 0, 0, 0);
  return date.getTime() < today.getTime();
}

function resolveError(error: unknown, fallback: string) {
  const message = (error as { response?: { data?: { message?: string } } }).response?.data?.message;
  return message && !/(Exception|java\.|Axios)/i.test(message) ? message : fallback;
}
</script>

<style scoped>
.teaching-tasks-page {
  min-width: 0;
}

.role-context {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: var(--color-text-secondary);
  font-size: 13px;
}

.task-panel {
  overflow: hidden;
}

.task-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 16px 18px;
  border-bottom: 1px solid var(--color-border);
}

.task-toolbar__filters {
  display: grid;
  grid-template-columns: minmax(260px, 420px) 160px;
  gap: 10px;
  min-width: 0;
}

.task-toolbar__count {
  flex: 0 0 auto;
  color: var(--color-text-muted);
  font-size: 13px;
  white-space: nowrap;
}

.task-panel__alert {
  margin: 14px 18px 0;
}

.task-identity,
.course-cell {
  min-width: 0;
}

.task-identity strong,
.task-identity span,
.course-cell strong,
.course-cell span {
  display: block;
  overflow-wrap: anywhere;
  white-space: normal;
}

.task-identity strong,
.course-cell strong {
  color: var(--color-text);
  line-height: 20px;
}

.task-identity span,
.course-cell span {
  margin-top: 3px;
  color: var(--color-text-muted);
  font-size: 12px;
  line-height: 17px;
}

.assignee-cell {
  display: flex;
  align-items: center;
  gap: 7px;
  min-width: 0;
}

.assignee-cell .el-icon {
  flex: 0 0 auto;
  color: var(--color-primary);
}

.assignee-cell span {
  overflow-wrap: anywhere;
  white-space: normal;
}

.due-cell {
  display: flex;
  align-items: flex-start;
  flex-direction: column;
  gap: 7px;
}

.due-cell time {
  color: var(--color-text-secondary);
  font-size: 12px;
  line-height: 1.45;
  white-space: normal;
}

.task-row-actions {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 8px;
}

.task-row-actions .el-button + .el-button {
  margin-left: 0;
}

.task-row-actions :deep(.el-button) {
  min-width: 88px;
  margin: 0;
}

.task-row-actions :deep(.el-button .el-icon) {
  flex: 0 0 auto;
}

.task-pagination {
  display: flex;
  justify-content: flex-end;
  padding: 14px 18px 16px;
  border-top: 1px solid var(--color-border);
}

.task-detail {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 18px 28px;
  padding: 18px 54px 20px;
  background: var(--color-surface-subtle);
}

.task-detail__wide {
  grid-column: 1 / -1;
}

.task-detail span,
.task-detail strong,
.task-detail p {
  display: block;
  margin: 0;
}

.task-detail span {
  margin-bottom: 5px;
  color: var(--color-text-muted);
  font-size: 12px;
  font-weight: 600;
}

.task-detail strong,
.task-detail p {
  color: var(--color-text);
  font-size: 13px;
  line-height: 1.65;
  overflow-wrap: anywhere;
  white-space: pre-wrap;
}

.task-form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
}

.task-form-grid :deep(.el-input-number),
.task-form-grid :deep(.el-select),
.task-form-grid :deep(.el-date-editor),
:deep(.el-form-item > .el-form-item__content > .el-input-number) {
  width: 100%;
}

.dialog-task-name {
  margin: -4px 0 18px;
  padding: 10px 12px;
  border-left: 3px solid var(--color-primary);
  background: var(--color-primary-soft);
  color: var(--color-text);
  font-size: 14px;
  font-weight: 700;
  line-height: 1.5;
}

:deep(.el-table__expanded-cell) {
  padding: 0 !important;
}

:deep(.el-table .el-tag) {
  max-width: 100%;
}

@media (max-width: 820px) {
  .task-toolbar {
    align-items: stretch;
    flex-direction: column;
  }

  .task-toolbar__filters {
    grid-template-columns: minmax(0, 1fr) 150px;
  }

  .task-toolbar__count {
    align-self: flex-end;
  }
}

@media (max-width: 560px) {
  .task-toolbar__filters,
  .task-form-grid,
  .task-detail {
    grid-template-columns: 1fr;
  }

  .task-detail {
    padding: 16px 20px;
  }

  .task-detail__wide {
    grid-column: auto;
  }
}
</style>
