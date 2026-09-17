import type { UserRole } from '@/api/auth';
import { useAuthStore } from '@/stores/auth';
import { isGoBackend } from '@/config/runtime';
import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router';

const legacyMissionsRoute: RouteRecordRaw = {
  path: '/assistant',
  name: 'lessonforge-missions',
  component: () => import('@/views/LessonForgeMissionsView.vue'),
  meta: { title: 'LessonForge', roles: ['TEACHER', 'LEADER', 'STUDENT'], lessonForge: true },
};

const goMissionsRoute: RouteRecordRaw = {
  path: '/assistant',
  name: 'lessonforge-missions',
  component: () => import('@/views/GoMissionsView.vue'),
  meta: { title: 'LessonForge', roles: ['TEACHER', 'LEADER', 'STUDENT'], lessonForge: true },
};

const legacyNewMissionRoute: RouteRecordRaw = {
  path: '/lessonforge/new',
  name: 'lessonforge-new',
  component: () => import('@/views/LessonForgeNewMissionView.vue'),
  meta: { title: 'New Mission', roles: ['TEACHER', 'LEADER'], lessonForge: true },
};

const goNewMissionRoute: RouteRecordRaw = {
  path: '/lessonforge/new',
  name: 'lessonforge-new',
  component: () => import('@/views/GoNewMissionView.vue'),
  meta: { title: 'New Mission', roles: ['TEACHER', 'LEADER'], lessonForge: true },
};

const legacyMissionWorkspaceRoute: RouteRecordRaw = {
  path: '/lessonforge/missions/:missionId',
  name: 'lessonforge-mission',
  component: () => import('@/views/LessonForgeMissionWorkspaceView.vue'),
  meta: { title: 'Mission Workspace', roles: ['TEACHER', 'LEADER'], lessonForge: true },
};

const goMissionWorkspaceRoute: RouteRecordRaw = {
  path: '/lessonforge/missions/:missionId',
  name: 'lessonforge-mission',
  component: () => import('@/views/GoMissionWorkspaceView.vue'),
  meta: { title: 'Mission Workspace', roles: ['TEACHER', 'LEADER'], lessonForge: true },
};

const goResearcherReviewsRoute: RouteRecordRaw = {
  path: '/reviewer/missions',
  name: 'lessonforge-researcher-reviews',
  component: () => import('@/views/GoResearcherMissionsView.vue'),
  meta: { title: '教研审核', roles: ['RESEARCHER'], lessonForge: true },
};

const goResearcherReviewRoute: RouteRecordRaw = {
  path: '/reviewer/missions/:missionId',
  name: 'lessonforge-researcher-review',
  component: () => import('@/views/GoResearcherReviewView.vue'),
  meta: { title: 'Mission 审核', roles: ['RESEARCHER'], lessonForge: true },
};

const modelSettingsRoute: RouteRecordRaw = {
  path: '/lessonforge/settings/models',
  name: 'ai-credentials',
  component: () => import('@/views/AiCredentialsView.vue'),
  meta: { title: '模型配置', roles: ['TEACHER', 'LEADER'], lessonForge: true },
};

const routes: RouteRecordRaw[] = [
  {
    path: '/',
    redirect: () => useAuthStore().isAuthenticated ? roleHome(useAuthStore().activeRole) : { name: 'lessonforge-login' },
    meta: { title: 'LessonForge', lessonForge: true },
  },
  {
    path: '/login',
    name: 'lessonforge-login',
    component: () => import('@/views/LoginView.vue'),
    meta: { title: '登录 · LessonForge', public: true, lessonForge: true },
  },
  {
    path: '/register',
    name: 'lessonforge-register',
    component: () => import('@/views/RegisterView.vue'),
    meta: { title: '创建账号 · LessonForge', public: true, lessonForge: true },
  },
  isGoBackend ? goMissionsRoute : legacyMissionsRoute,
  {
    path: '/lessonforge/missions',
    redirect: { name: 'lessonforge-missions' },
    meta: { title: 'Missions', roles: ['TEACHER', 'LEADER', 'STUDENT'], lessonForge: true },
  },
  isGoBackend ? goNewMissionRoute : legacyNewMissionRoute,
  isGoBackend ? goMissionWorkspaceRoute : legacyMissionWorkspaceRoute,
  modelSettingsRoute,
  goResearcherReviewsRoute,
  goResearcherReviewRoute,
  {
    path: '/:pathMatch(.*)*',
    name: 'not-found',
    component: () => import('@/views/NotFoundView.vue'),
    meta: { title: '页面不存在', roles: ['TEACHER', 'LEADER', 'STUDENT'], lessonForge: true },
  },
];

const router = createRouter({
  history: createWebHistory(),
  routes,
});

export function roleHome(_role?: UserRole) {
  if (_role === 'RESEARCHER') return '/reviewer/missions';
  if (_role === 'TEACHER' || _role === 'LEADER') return '/lessonforge/new';
  return '/assistant';
}

router.beforeEach(async (to) => {
  const auth = useAuthStore();
  if (to.meta.public) {
    if (to.name === 'lessonforge-login') {
      try {
        await auth.ensureInitialized();
        if (auth.isAuthenticated) return roleHome(auth.activeRole);
      } catch {
        return true;
      }
    }
    return true;
  }
  try {
    await auth.ensureInitialized();
  } catch {
    return { name: 'lessonforge-login', query: { redirect: to.fullPath } };
  }
  if (!auth.isAuthenticated) return { name: 'lessonforge-login', query: { redirect: to.fullPath } };

  const allowedRoles = (to.meta.roles || []) as UserRole[];
  if (allowedRoles.length > 0 && (!auth.activeRole || !allowedRoles.includes(auth.activeRole))) {
    return roleHome(auth.activeRole);
  }
  return true;
});

export default router;
