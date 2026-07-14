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
    meta: { title: '教研管理工作台' },
  },
  {
    path: '/student',
    name: 'student-workspace',
    component: () => import('@/views/RoleWorkspaceView.vue'),
    meta: { title: '学习空间' },
  },
  {
    path: '/',
    name: 'home',
    component: () => import('@/views/HomeView.vue'),
    meta: { title: '教师工作台' },
  },
  {
    path: '/projects',
    name: 'projects',
    component: () => import('@/views/ProjectListView.vue'),
    meta: { title: '教学项目' },
  },
  {
    path: '/projects/new',
    name: 'project-create',
    component: () => import('@/views/ProjectCreateView.vue'),
    meta: { title: '新建教学项目' },
  },
  {
    path: '/projects/:projectId',
    name: 'project-overview',
    component: () => import('@/views/ProjectOverviewView.vue'),
    meta: { title: '项目概览' },
  },
  {
    path: '/projects/:projectId/mode',
    name: 'project-mode',
    component: () => import('@/views/ProjectModeView.vue'),
    meta: { title: '生成模式选择' },
  },
  {
    path: '/projects/:projectId/requirements',
    name: 'project-requirements',
    component: () => import('@/views/RequirementInputView.vue'),
    meta: { title: '教学需求与澄清' },
  },
  {
    path: '/projects/:projectId/summary',
    name: 'project-summary',
    component: () => import('@/views/RequirementSummaryView.vue'),
    meta: { title: '需求摘要确认' },
  },
  {
    path: '/projects/:projectId/materials',
    name: 'project-materials',
    component: () => import('@/views/MaterialUploadView.vue'),
    meta: { title: '参考资料与解析' },
  },
  {
    path: '/projects/:projectId/knowledge',
    name: 'project-knowledge',
    component: () => import('@/views/KnowledgeRetrievalView.vue'),
    meta: { title: '本地知识检索' },
  },
  {
    path: '/projects/:projectId/intent',
    name: 'project-intent',
    component: () => import('@/views/IntentConfirmView.vue'),
    meta: { title: '教学意图确认' },
  },
  {
    path: '/projects/:projectId/plan',
    name: 'project-plan',
    component: () => import('@/views/GenerationPlanView.vue'),
    meta: { title: '教学内容生成' },
  },
  {
    path: '/projects/:projectId/preview',
    name: 'project-preview',
    component: () => import('@/views/ArtifactPreviewView.vue'),
    meta: { title: '方案预览与修改' },
  },
  {
    path: '/projects/:projectId/export',
    name: 'project-export',
    component: () => import('@/views/ExportView.vue'),
    meta: { title: '版本与导出' },
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
    meta: { title: '页面不存在' },
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

  const requiredRole: UserRole = to.path.startsWith('/leader')
    ? 'LEADER'
    : to.path.startsWith('/student')
      ? 'STUDENT'
      : 'TEACHER';
  if (auth.activeRole !== requiredRole) {
    return roleHome(auth.activeRole);
  }
  return true;
});

export default router;
