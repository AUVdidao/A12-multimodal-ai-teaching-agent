import type { UserRole } from '@/api/auth';
import { useAuthStore } from '@/stores/auth';
import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router';

const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/LoginView.vue'),
    meta: { title: '登录', public: true },
  },
  {
    path: '/leader',
    name: 'leader-workspace',
    component: () => import('@/views/RoleWorkspaceView.vue'),
    meta: { title: '教研管理工作台', roles: ['LEADER'] },
  },
  {
    path: '/leader/tasks',
    name: 'leader-teaching-tasks',
    component: () => import('@/views/TeachingTasksView.vue'),
    meta: { title: '教学任务管理', roles: ['LEADER'] },
  },
  {
    path: '/leader/courses',
    name: 'leader-courses',
    component: () => import('@/views/CourseManagementView.vue'),
    meta: { title: '课程与班级', roles: ['LEADER'] },
  },
  {
    path: '/leader/approvals',
    name: 'leader-approvals',
    component: () => import('@/views/ApprovalRequestsView.vue'),
    meta: { title: '成果审批', roles: ['LEADER'] },
  },
  {
    path: '/leader/publications',
    name: 'leader-publications',
    component: () => import('@/views/PublicationManagementView.vue'),
    meta: { title: '班级成果发布', roles: ['LEADER'] },
  },
  {
    path: '/leader/questions',
    name: 'leader-questions',
    component: () => import('@/views/QuestionCenterView.vue'),
    meta: { title: '学生问答巡查', roles: ['LEADER'] },
  },
  {
    path: '/student',
    name: 'student-workspace',
    component: () => import('@/views/RoleWorkspaceView.vue'),
    meta: { title: '学习空间', roles: ['STUDENT'] },
  },
  {
    path: '/student/learning',
    name: 'student-learning',
    component: () => import('@/views/StudentLearningView.vue'),
    meta: { title: '我的学习内容', roles: ['STUDENT'] },
  },
  {
    path: '/student/questions',
    name: 'student-questions',
    component: () => import('@/views/QuestionCenterView.vue'),
    meta: { title: '我的学习问答', roles: ['STUDENT'] },
  },
  {
    path: '/',
    name: 'home',
    component: () => import('@/views/HomeView.vue'),
    meta: { title: '教师工作台', roles: ['TEACHER'] },
  },
  {
    path: '/search',
    name: 'global-search',
    component: () => import('@/views/GlobalSearchView.vue'),
    meta: { title: '全局搜索', roles: ['TEACHER', 'LEADER', 'STUDENT'] },
  },
  {
    path: '/tasks',
    name: 'teacher-teaching-tasks',
    component: () => import('@/views/TeachingTasksView.vue'),
    meta: { title: '我的教学任务', roles: ['TEACHER'] },
  },
  {
    path: '/recent',
    name: 'recent-projects',
    component: () => import('@/views/RecentProjectsView.vue'),
    meta: { title: '最近访问', roles: ['TEACHER'] },
  },
  {
    path: '/recycle-bin',
    name: 'recycle-bin',
    component: () => import('@/views/RecycleBinView.vue'),
    meta: { title: '回收站', roles: ['TEACHER'] },
  },
  {
    path: '/approvals',
    name: 'teacher-approvals',
    component: () => import('@/views/ApprovalRequestsView.vue'),
    meta: { title: '我的成果审批', roles: ['TEACHER'] },
  },
  {
    path: '/publications',
    name: 'teacher-publications',
    component: () => import('@/views/PublicationManagementView.vue'),
    meta: { title: '项目发布记录', roles: ['TEACHER'] },
  },
  {
    path: '/questions',
    name: 'teacher-questions',
    component: () => import('@/views/QuestionCenterView.vue'),
    meta: { title: '学生问答', roles: ['TEACHER'] },
  },
  {
    path: '/analytics',
    name: 'teaching-analytics',
    component: () => import('@/views/TeachingAnalyticsView.vue'),
    meta: { title: '教学分析', roles: ['TEACHER'] },
  },
  {
    path: '/insights',
    name: 'student-insights',
    component: () => import('@/views/StudentInsightsView.vue'),
    meta: { title: '学情洞察', roles: ['TEACHER', 'LEADER'] },
  },
  {
    path: '/resources/materials',
    name: 'resource-library',
    component: () => import('@/views/ResourceLibraryView.vue'),
    meta: { title: '资料库', roles: ['TEACHER'] },
  },
  {
    path: '/resources/knowledge',
    name: 'knowledge-library',
    component: () => import('@/views/KnowledgeLibraryView.vue'),
    meta: { title: '知识库', roles: ['TEACHER'] },
  },
  {
    path: '/templates',
    name: 'template-center',
    component: () => import('@/views/TemplateCenterView.vue'),
    meta: { title: '模板中心', roles: ['TEACHER'] },
  },
  {
    path: '/assistant',
    name: 'ai-assistant',
    component: () => import('@/views/AiAssistantView.vue'),
    meta: { title: 'AI 教学工作副驾驶', roles: ['TEACHER'] },
  },
  {
    path: '/projects',
    name: 'projects',
    component: () => import('@/views/ProjectListView.vue'),
    meta: { title: '教学项目', roles: ['TEACHER'] },
  },
  {
    path: '/projects/new',
    name: 'project-create',
    component: () => import('@/views/ProjectCreateView.vue'),
    meta: { title: '新建教学项目', roles: ['TEACHER'] },
  },
  {
    path: '/projects/:projectId',
    name: 'project-overview',
    component: () => import('@/views/ProjectOverviewView.vue'),
    meta: { title: '项目概览', roles: ['TEACHER'] },
  },
  {
    path: '/projects/:projectId/mode',
    name: 'project-mode',
    component: () => import('@/views/ProjectModeView.vue'),
    meta: { title: '生成模式选择', roles: ['TEACHER'] },
  },
  {
    path: '/projects/:projectId/requirements',
    name: 'project-requirements',
    component: () => import('@/views/RequirementInputView.vue'),
    meta: { title: '教学需求与澄清', roles: ['TEACHER'] },
  },
  {
    path: '/projects/:projectId/summary',
    name: 'project-summary',
    component: () => import('@/views/RequirementSummaryView.vue'),
    meta: { title: '需求摘要确认', roles: ['TEACHER'] },
  },
  {
    path: '/projects/:projectId/materials',
    name: 'project-materials',
    component: () => import('@/views/MaterialUploadView.vue'),
    meta: { title: '参考资料与解析', roles: ['TEACHER'] },
  },
  {
    path: '/projects/:projectId/knowledge',
    name: 'project-knowledge',
    component: () => import('@/views/KnowledgeRetrievalView.vue'),
    meta: { title: '本地知识检索', roles: ['TEACHER'] },
  },
  {
    path: '/projects/:projectId/intent',
    name: 'project-intent',
    component: () => import('@/views/IntentConfirmView.vue'),
    meta: { title: '教学意图确认', roles: ['TEACHER'] },
  },
  {
    path: '/projects/:projectId/plan',
    name: 'project-plan',
    component: () => import('@/views/GenerationPlanView.vue'),
    meta: { title: '教学内容生成', roles: ['TEACHER'] },
  },
  {
    path: '/projects/:projectId/preview',
    name: 'project-preview',
    component: () => import('@/views/ArtifactPreviewView.vue'),
    meta: { title: '方案预览与修改', roles: ['TEACHER'] },
  },
  {
    path: '/projects/:projectId/export',
    name: 'project-export',
    component: () => import('@/views/ExportView.vue'),
    meta: { title: '版本与导出', roles: ['TEACHER'] },
  },
  { path: '/requirements', redirect: '/projects' },
  { path: '/dialog', redirect: '/projects' },
  { path: '/summary', redirect: '/projects' },
  { path: '/materials', redirect: '/projects' },
  { path: '/intent', redirect: '/projects' },
  { path: '/plan', redirect: '/projects' },
  { path: '/preview', redirect: '/projects' },
  { path: '/export', redirect: '/projects' },
  {
    path: '/:pathMatch(.*)*',
    name: 'not-found',
    component: () => import('@/views/NotFoundView.vue'),
    meta: { title: '页面不存在', roles: ['TEACHER', 'LEADER', 'STUDENT'] },
  },
];

const router = createRouter({
  history: createWebHistory(),
  routes,
});

export function roleHome(role?: UserRole) {
  if (role === 'LEADER') return '/leader';
  if (role === 'STUDENT') return '/student';
  return '/';
}

router.beforeEach(async (to) => {
  const auth = useAuthStore();
  if (to.meta.public) {
    if (!auth.token) return true;
    try {
      await auth.ensureInitialized();
      return roleHome(auth.activeRole);
    } catch {
      return true;
    }
  }

  if (!auth.token) {
    return { name: 'login', query: { redirect: to.fullPath } };
  }
  try {
    await auth.ensureInitialized();
  } catch {
    return { name: 'login', query: { redirect: to.fullPath } };
  }

  const allowedRoles = (to.meta.roles || []) as UserRole[];
  if (allowedRoles.length > 0 && (!auth.activeRole || !allowedRoles.includes(auth.activeRole))) {
    return roleHome(auth.activeRole);
  }
  return true;
});

export default router;
