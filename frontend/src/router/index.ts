import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router';

const routes: RouteRecordRaw[] = [
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
    meta: { title: '新建课件项目' },
  },
  {
    path: '/projects/:projectId/mode',
    name: 'project-mode',
    component: () => import('@/views/ProjectModeView.vue'),
    meta: { title: '教学需求' },
  },
  {
    path: '/projects/:projectId/overview',
    name: 'project-overview',
    component: () => import('@/views/ProjectOverviewView.vue'),
    meta: { title: '项目概览', layout: 'wide' },
  },
  {
    path: '/projects/:projectId/requirements',
    name: 'project-requirements',
    component: () => import('@/views/RequirementInputView.vue'),
    meta: { title: '教学需求' },
  },
  {
    path: '/projects/:projectId/clarification',
    redirect: (to) => ({ path: `/projects/${to.params.projectId}/requirements`, hash: '#clarification' }),
  },
  {
    path: '/requirements',
    name: 'requirements',
    component: () => import('@/views/RequirementInputView.vue'),
    meta: { title: '教学需求输入' },
  },
  {
    path: '/dialog',
    redirect: (to) => {
      const projectId = Array.isArray(to.query.projectId) ? to.query.projectId[0] : to.query.projectId;
      return projectId ? `/projects/${projectId}/requirements#clarification` : '/projects';
    },
  },
  {
    path: '/projects/:projectId/requirement-summary',
    name: 'project-requirement-summary',
    component: () => import('@/views/RequirementSummaryView.vue'),
    meta: { title: '需求摘要' },
  },
  {
    path: '/projects/:projectId/summary',
    redirect: (to) => ({
      path: `/projects/${to.params.projectId}/requirement-summary`,
      query: to.query,
      hash: to.hash,
    }),
  },
  {
    path: '/summary',
    name: 'summary',
    component: () => import('@/views/RequirementSummaryView.vue'),
    meta: { title: '需求摘要确认' },
  },
  {
    path: '/projects/:projectId/materials',
    name: 'project-materials',
    component: () => import('@/views/MaterialUploadView.vue'),
    meta: { title: '参考资料' },
  },
  {
    path: '/projects/:projectId/knowledge',
    name: 'project-knowledge',
    component: () => import('@/views/KnowledgeView.vue'),
    meta: { title: '知识库' },
  },
  {
    path: '/projects/:projectId/teaching-intent',
    name: 'project-teaching-intent',
    component: () => import('@/views/IntentConfirmView.vue'),
    meta: { title: '教学意图' },
  },
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

router.afterEach((to) => {
  const pageTitle = typeof to.meta.title === 'string' ? to.meta.title : '教师工作台';
  document.title = `${pageTitle} | A12 多模态 AI 教学智能体`;
});

export default router;
