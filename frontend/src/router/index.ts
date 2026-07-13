import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router';

const PageHost = { template: '<div />' };

const routes: RouteRecordRaw[] = [
  { path: '/', redirect: '/summary' },
  { path: '/workspace', component: PageHost, meta: { title: '教师工作台' } },
  { path: '/projects', component: PageHost, meta: { title: '教学项目' } },
  { path: '/summary', component: PageHost, meta: { title: '教师工作台' } },
  { path: '/materials', component: PageHost, meta: { title: '教师工作台' } },
  { path: '/knowledge', component: PageHost, meta: { title: '本地知识检索' } },
  { path: '/intent', component: PageHost, meta: { title: '教学意图确认' } },
  { path: '/:pathMatch(.*)*', redirect: '/summary' },
];

export default createRouter({
  history: createWebHistory(),
  routes,
});
