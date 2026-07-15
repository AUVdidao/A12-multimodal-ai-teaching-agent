<template>
  <section class="page course-management-page">
    <PageHeader
      eyebrow="教学组织"
      title="课程与班级"
      description="维护可用于教学任务、成果审批和班级发布的基础教学组织数据。"
    >
      <template #actions>
        <el-button :icon="Refresh" :loading="loading" @click="loadData">刷新</el-button>
        <el-button type="primary" :icon="Plus" @click="courseDialogVisible = true">新建课程</el-button>
      </template>
    </PageHeader>

    <StatePanel
      v-if="loading && courses.length === 0"
      type="loading"
      title="正在读取课程与班级"
      description="稍候即可开始维护教学组织。"
    />
    <StatePanel
      v-else-if="errorMessage && courses.length === 0"
      type="error"
      title="课程数据读取失败"
      :description="errorMessage"
    >
      <template #action>
        <el-button type="primary" @click="loadData">重新加载</el-button>
      </template>
    </StatePanel>

    <div v-else class="course-management-grid" v-loading="loading">
      <section class="surface-panel course-list-panel">
        <header class="panel-heading">
          <div>
            <h3>课程目录</h3>
            <p>共 {{ courses.length }} 门课程</p>
          </div>
        </header>
        <button
          v-for="course in courses"
          :key="course.id"
          type="button"
          class="course-row"
          :class="{ 'is-active': selectedCourseId === course.id }"
          @click="selectedCourseId = course.id"
        >
          <span class="course-row__code">{{ course.courseCode }}</span>
          <span class="course-row__content">
            <strong>{{ course.courseName }}</strong>
            <small>{{ course.description || '暂无课程说明' }}</small>
          </span>
          <el-icon><ArrowRight /></el-icon>
        </button>
        <el-empty v-if="courses.length === 0" description="尚未创建课程" :image-size="64" />
      </section>

      <section class="surface-panel class-list-panel">
        <header class="panel-heading">
          <div>
            <h3>{{ selectedCourse?.courseName || '授课班级' }}</h3>
            <p>{{ selectedCourse ? `${filteredClasses.length} 个班级` : '请先选择一门课程' }}</p>
          </div>
          <el-button
            type="primary"
            plain
            :icon="Plus"
            :disabled="!selectedCourseId"
            @click="classDialogVisible = true"
          >
            新建班级
          </el-button>
        </header>

        <el-table v-if="selectedCourseId && filteredClasses.length" :data="filteredClasses" row-key="id">
          <el-table-column prop="className" label="班级名称" min-width="160" />
          <el-table-column prop="cohort" label="年级/届次" min-width="110">
            <template #default="{ row }">{{ row.cohort || '未设置' }}</template>
          </el-table-column>
          <el-table-column prop="studentCount" label="学生人数" width="110" align="center" />
          <el-table-column label="成员管理" width="130" align="right">
            <template #default="{ row }">
              <el-button link type="primary" :icon="User" @click="openMemberDialog(row)">管理成员</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-empty
          v-else
          :description="selectedCourseId ? '当前课程尚未创建班级' : '选择左侧课程查看班级'"
          :image-size="72"
        />
      </section>
    </div>

    <el-dialog v-model="courseDialogVisible" title="新建课程" width="520px" destroy-on-close>
      <el-form ref="courseFormRef" :model="courseForm" :rules="courseRules" label-position="top">
        <el-form-item label="课程编码" prop="courseCode">
          <el-input v-model="courseForm.courseCode" maxlength="40" placeholder="例如 AI-101" />
        </el-form-item>
        <el-form-item label="课程名称" prop="courseName">
          <el-input v-model="courseForm.courseName" maxlength="120" placeholder="输入课程名称" />
        </el-form-item>
        <el-form-item label="课程说明" prop="description">
          <el-input v-model="courseForm.description" type="textarea" :rows="3" maxlength="500" show-word-limit />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="courseDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="savingCourse" @click="saveCourse">创建课程</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="classDialogVisible" title="新建班级" width="520px" destroy-on-close>
      <el-form ref="classFormRef" :model="classForm" :rules="classRules" label-position="top">
        <el-form-item label="班级名称" prop="className">
          <el-input v-model="classForm.className" maxlength="120" placeholder="例如 计算机科学一班" />
        </el-form-item>
        <el-form-item label="年级/届次" prop="cohort">
          <el-input v-model="classForm.cohort" maxlength="80" placeholder="例如 2026 级" />
        </el-form-item>
        <el-form-item label="学生人数" prop="studentCount">
          <el-input-number v-model="classForm.studentCount" :min="0" :max="1000" controls-position="right" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="classDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="savingClass" @click="saveClassGroup">创建班级</el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="memberDialogVisible"
      :title="selectedClass ? `班级成员 · ${selectedClass.className}` : '班级成员'"
      width="640px"
      destroy-on-close
      @closed="resetMemberDialog"
    >
      <StatePanel
        v-if="membersLoading"
        type="loading"
        title="正在读取班级成员"
        description="正在核对当前班级的真实学生成员关系。"
      />
      <StatePanel v-else-if="membersError" type="error" title="班级成员读取失败" :description="membersError">
        <template #action><el-button type="primary" @click="loadMembers">重新加载</el-button></template>
      </StatePanel>
      <template v-else>
        <div class="member-picker">
          <el-select v-model="selectedStudentId" filterable clearable placeholder="选择尚未加入该班的学生">
            <el-option
              v-for="student in availableStudents"
              :key="student.id"
              :label="`${student.displayName}（${student.username}）`"
              :value="student.id"
            />
          </el-select>
          <el-button type="primary" :icon="Plus" :disabled="!selectedStudentId" :loading="addingMember" @click="saveMember">
            加入班级
          </el-button>
        </div>
        <el-table v-if="members.length" :data="members" row-key="id">
          <el-table-column prop="displayName" label="学生姓名" min-width="160" />
          <el-table-column prop="username" label="账号" min-width="150" />
          <el-table-column label="操作" width="100" align="right">
            <template #default="{ row }">
              <el-button link type="danger" :icon="Delete" :loading="removingStudentId === row.studentId" @click="deleteMember(row)">
                移除
              </el-button>
            </template>
          </el-table-column>
        </el-table>
        <StatePanel v-else type="empty" title="当前班级还没有学生成员" description="从上方选择已注册的学生账号加入班级。" />
      </template>
      <template #footer><el-button @click="memberDialogVisible = false">完成</el-button></template>
    </el-dialog>
  </section>
</template>

<script setup lang="ts">
import {
  addClassMember,
  createClassGroup,
  createCourse,
  getCollaborationReferenceData,
  listClassMembers,
  removeClassMember,
  type ClassMembership,
  type ClassGroupOption,
  type CourseOption,
  type TeacherOption,
} from '@/api/collaboration';
import PageHeader from '@/components/PageHeader.vue';
import StatePanel from '@/components/StatePanel.vue';
import { ArrowRight, Delete, Plus, Refresh, User } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus';
import { computed, onMounted, reactive, ref, watch } from 'vue';

const loading = ref(true);
const errorMessage = ref('');
const courses = ref<CourseOption[]>([]);
const classes = ref<ClassGroupOption[]>([]);
const students = ref<TeacherOption[]>([]);
const selectedCourseId = ref<number>();

const courseDialogVisible = ref(false);
const classDialogVisible = ref(false);
const savingCourse = ref(false);
const savingClass = ref(false);
const memberDialogVisible = ref(false);
const selectedClass = ref<ClassGroupOption>();
const members = ref<ClassMembership[]>([]);
const membersLoading = ref(false);
const membersError = ref('');
const selectedStudentId = ref<number>();
const addingMember = ref(false);
const removingStudentId = ref<number>();
const courseFormRef = ref<FormInstance>();
const classFormRef = ref<FormInstance>();

const courseForm = reactive({ courseCode: '', courseName: '', description: '' });
const classForm = reactive({ className: '', cohort: '', studentCount: 0 });

const courseRules: FormRules = {
  courseCode: [{ required: true, message: '请输入课程编码', trigger: 'blur' }],
  courseName: [{ required: true, message: '请输入课程名称', trigger: 'blur' }],
};
const classRules: FormRules = {
  className: [{ required: true, message: '请输入班级名称', trigger: 'blur' }],
};

const selectedCourse = computed(() => courses.value.find((item) => item.id === selectedCourseId.value));
const filteredClasses = computed(() => classes.value.filter((item) => item.courseId === selectedCourseId.value));
const memberStudentIds = computed(() => new Set(members.value.map((item) => item.studentId)));
const availableStudents = computed(() => students.value.filter((student) => !memberStudentIds.value.has(student.id)));

onMounted(loadData);

watch(courseDialogVisible, (visible) => {
  if (!visible) Object.assign(courseForm, { courseCode: '', courseName: '', description: '' });
});
watch(classDialogVisible, (visible) => {
  if (!visible) Object.assign(classForm, { className: '', cohort: '', studentCount: 0 });
});

async function loadData() {
  loading.value = true;
  errorMessage.value = '';
  try {
    const data = await getCollaborationReferenceData();
    courses.value = data.courses;
    classes.value = data.classes;
    students.value = data.students || [];
    if (!selectedCourseId.value || !courses.value.some((item) => item.id === selectedCourseId.value)) {
      selectedCourseId.value = courses.value[0]?.id;
    }
  } catch (error) {
    errorMessage.value = resolveError(error, '暂时无法读取课程与班级，请稍后重试。');
  } finally {
    loading.value = false;
  }
}

async function openMemberDialog(classGroup: ClassGroupOption) {
  selectedClass.value = classGroup;
  memberDialogVisible.value = true;
  await loadMembers();
}

async function loadMembers() {
  if (!selectedClass.value) return;
  membersLoading.value = true;
  membersError.value = '';
  try {
    members.value = await listClassMembers(selectedClass.value.id);
  } catch (error) {
    membersError.value = resolveError(error, '暂时无法读取班级成员，请稍后重试。');
  } finally {
    membersLoading.value = false;
  }
}

async function saveMember() {
  if (!selectedClass.value || !selectedStudentId.value || addingMember.value) return;
  addingMember.value = true;
  try {
    const created = await addClassMember(selectedClass.value.id, selectedStudentId.value);
    members.value.push(created);
    members.value.sort((left, right) => left.displayName.localeCompare(right.displayName, 'zh-CN'));
    selectedStudentId.value = undefined;
    ElMessage.success('学生已加入班级');
  } catch (error) {
    ElMessage.error(resolveError(error, '加入班级失败，请稍后重试。'));
  } finally {
    addingMember.value = false;
  }
}

async function deleteMember(member: ClassMembership) {
  if (!selectedClass.value || removingStudentId.value) return;
  try {
    await ElMessageBox.confirm(`确认将“${member.displayName}”移出${selectedClass.value.className}吗？`, '移除班级成员', {
      confirmButtonText: '确认移除',
      cancelButtonText: '取消',
      type: 'warning',
    });
  } catch {
    return;
  }
  removingStudentId.value = member.studentId;
  try {
    await removeClassMember(selectedClass.value.id, member.studentId);
    members.value = members.value.filter((item) => item.studentId !== member.studentId);
    ElMessage.success('学生已移出班级');
  } catch (error) {
    ElMessage.error(resolveError(error, '移除班级成员失败，请稍后重试。'));
  } finally {
    removingStudentId.value = undefined;
  }
}

function resetMemberDialog() {
  selectedClass.value = undefined;
  members.value = [];
  membersError.value = '';
  selectedStudentId.value = undefined;
}

async function saveCourse() {
  if (!courseFormRef.value || savingCourse.value) return;
  const valid = await courseFormRef.value.validate().catch(() => false);
  if (!valid) return;
  savingCourse.value = true;
  try {
    const created = await createCourse({
      courseCode: courseForm.courseCode.trim(),
      courseName: courseForm.courseName.trim(),
      description: courseForm.description.trim() || undefined,
    });
    courses.value.push(created);
    courses.value.sort((a, b) => a.courseName.localeCompare(b.courseName, 'zh-CN'));
    selectedCourseId.value = created.id;
    courseDialogVisible.value = false;
    ElMessage.success('课程已创建');
  } catch (error) {
    ElMessage.error(resolveError(error, '课程创建失败，请检查课程编码是否重复。'));
  } finally {
    savingCourse.value = false;
  }
}

async function saveClassGroup() {
  if (!selectedCourseId.value || !classFormRef.value || savingClass.value) return;
  const valid = await classFormRef.value.validate().catch(() => false);
  if (!valid) return;
  savingClass.value = true;
  try {
    const created = await createClassGroup(selectedCourseId.value, {
      className: classForm.className.trim(),
      cohort: classForm.cohort.trim() || undefined,
      studentCount: classForm.studentCount,
    });
    classes.value.push(created);
    classDialogVisible.value = false;
    ElMessage.success('班级已创建');
  } catch (error) {
    ElMessage.error(resolveError(error, '班级创建失败，请检查名称是否重复。'));
  } finally {
    savingClass.value = false;
  }
}

function resolveError(error: unknown, fallback: string) {
  const message = (error as { response?: { data?: { message?: string } } }).response?.data?.message;
  return message && !/(Exception|java\.|Axios)/i.test(message) ? message : fallback;
}
</script>

<style scoped>
.course-management-grid {
  display: grid;
  grid-template-columns: minmax(280px, 0.8fr) minmax(0, 1.7fr);
  gap: 16px;
}

.course-list-panel,
.class-list-panel {
  min-width: 0;
  overflow: hidden;
}

.panel-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 18px 20px;
  border-bottom: 1px solid var(--color-border);
}

.panel-heading h3,
.panel-heading p {
  margin: 0;
}

.panel-heading h3 {
  color: var(--color-text);
  font-size: 16px;
}

.panel-heading p {
  margin-top: 4px;
  color: var(--color-text-muted);
  font-size: 12px;
}

.course-row {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  align-items: center;
  gap: 12px;
  width: 100%;
  min-height: 72px;
  padding: 12px 16px;
  border: 0;
  border-bottom: 1px solid var(--color-border);
  background: transparent;
  color: var(--color-text);
  cursor: pointer;
  text-align: left;
}

.course-row:hover,
.course-row.is-active {
  background: var(--color-primary-soft);
}

.course-row.is-active {
  box-shadow: inset 3px 0 0 var(--color-primary);
}

.course-row__code {
  min-width: 66px;
  color: var(--color-primary);
  font-size: 12px;
  font-weight: 700;
}

.course-row__content strong,
.course-row__content small {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.member-picker {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 12px;
  margin-bottom: 18px;
}

.course-row__content small {
  margin-top: 4px;
  color: var(--color-text-muted);
}

@media (max-width: 900px) {
  .course-management-grid {
    grid-template-columns: 1fr;
  }
}


@media (max-width: 560px) {
  .member-picker { grid-template-columns: 1fr; }
  .member-picker .el-button { width: 100%; }
}
</style>
