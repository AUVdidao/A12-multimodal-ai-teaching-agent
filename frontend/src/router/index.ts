import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router';

const routes: RouteRecordRaw[] = [
  {
    path: '/',
    name: 'home',
    component: () => import('@/views/HomeView.vue'),
    meta: { title: '首页' },
  },
  {
    path: '/projects',
    name: 'projects',
    component: () => import('@/views/ProjectListView.vue'),
    meta: { title: '项目列表' },
  },
  {
    path: '/projects/new',
    name: 'project-create',
    component: () => import('@/views/ProjectCreateView.vue'),
    meta: { title: '新建课件项目' },
  },
  {
    path: '/requirements',
    name: 'requirements',
    component: () => import('@/views/RequirementInputView.vue'),
    meta: { title: '教学需求输入' },
  },
  {
    path: '/dialog',
    name: 'dialog',
    component: () => import('@/views/DialogClarificationView.vue'),
    meta: { title: '智能澄清对话' },
  },
  {
    path: '/summary',
    name: 'summary',
    component: () => import('@/views/RequirementSummaryView.vue'),
    meta: { title: '需求摘要确认' },
  },
  {
    path: '/materials',
    name: 'materials',
    component: () => import('@/views/MaterialUploadView.vue'),
    meta: { title: '资料上传与用途绑定' },
  },
  {
    path: '/intent',
    name: 'intent',
    component: () => import('@/views/IntentConfirmView.vue'),
    meta: { title: '教学意图确认' },
  },
  {
    path: '/plan',
    name: 'plan',
    component: () => import('@/views/GenerationPlanView.vue'),
    meta: { title: '课件生成方案' },
  },
  {
    path: '/preview',
    name: 'preview',
    component: () => import('@/views/ArtifactPreviewView.vue'),
    meta: { title: '生成结果预览' },
  },
  {
    path: '/export',
    name: 'export',
    component: () => import('@/views/ExportView.vue'),
    meta: { title: '文件导出' },
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'not-found',
    component: () => import('@/views/NotFoundView.vue'),
    meta: { title: '页面不存在' },
  },
];

export default createRouter({
  history: createWebHistory(),
  routes,
});
