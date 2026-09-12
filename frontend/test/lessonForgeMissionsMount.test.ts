import assert from 'node:assert/strict';
import { after, before, test } from 'node:test';
import { JSDOM } from 'jsdom';
import { readFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { createServer, type ViteDevServer } from 'vite';
import { compileScript, parse } from '@vue/compiler-sfc';
import axios from 'axios';
import AxiosMockAdapter from 'axios-mock-adapter';

const frontendRoot = resolve(process.cwd());
const teacherOne = { id: 2, username: 'teacher-one', displayName: 'Teacher One', roles: ['TEACHER'], activeRole: 'TEACHER' };
const teacherTwo = { id: 3, username: 'teacher-two', displayName: 'Teacher Two', roles: ['TEACHER'], activeRole: 'TEACHER' };
const researcher = { id: 4, username: 'reviewer', displayName: 'Reviewer', roles: ['RESEARCHER'], activeRole: 'RESEARCHER' };
let dom: JSDOM;
let viteServer: ViteDevServer;
let mount: typeof import('@vue/test-utils')['mount'];
let flushPromises: typeof import('@vue/test-utils')['flushPromises'];
let createPinia: typeof import('pinia')['createPinia'];
let setActivePinia: typeof import('pinia')['setActivePinia'];
let createRouter: typeof import('vue-router')['createRouter'];
let createMemoryHistory: typeof import('vue-router')['createMemoryHistory'];
let LessonForgeNewMissionView: any;
let LessonForgeMissionsView: any;
let LessonForgeFrame: any;
let useAuthStore: typeof import('../src/stores/auth')['useAuthStore'];
let useLessonForgeStore: typeof import('../src/stores/lessonForge')['useLessonForgeStore'];
let mapLessonForgeMission: typeof import('../src/stores/lessonForge')['mapLessonForgeMission'];
let httpMock: AxiosMockAdapter;

function setGlobal(name: string, value: unknown) {
  Object.defineProperty(globalThis, name, { configurable: true, writable: true, value });
}

function installDom() {
  dom = new JSDOM('<!doctype html><html><body></body></html>', { url: 'http://localhost/lessonforge/new' });
  for (const [name, value] of Object.entries({
    window: dom.window,
    history: dom.window.history,
    location: dom.window.location,
    document: dom.window.document,
    navigator: dom.window.navigator,
    localStorage: dom.window.localStorage,
    HTMLElement: dom.window.HTMLElement,
    SVGElement: dom.window.SVGElement,
    Element: dom.window.Element,
    Node: dom.window.Node,
    Event: dom.window.Event,
    CustomEvent: dom.window.CustomEvent,
    MutationObserver: dom.window.MutationObserver,
  })) setGlobal(name, value);
}

function mission(id: number, title: string, assignedTeacherId: number, status: string) {
  return {
    id, title, description: `${title} description`, assignedTeacherId, createdByLeaderId: 9, deadline: null,
    status, rejectionReason: null, selectedModelConnectionId: null, conversationId: null,
    messages: [], contextFiles: [], submissions: [], teacherName: assignedTeacherId === 2 ? 'Teacher One' : 'Teacher Two', leaderName: 'Leader',
  };
}

function router() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/login', name: 'lessonforge-login', component: { template: '<div />' } },
      { path: '/lessonforge/new', name: 'lessonforge-new', component: LessonForgeNewMissionView },
      { path: '/lessonforge/missions', name: 'lessonforge-missions', component: LessonForgeMissionsView },
      { path: '/lessonforge/missions/:missionId', name: 'lessonforge-mission', component: { template: '<div />' } },
      { path: '/reviewer/missions', name: 'lessonforge-researcher-reviews', component: { template: '<div />' } },
      { path: '/reviewer/missions/:missionId', name: 'lessonforge-researcher-review', component: { template: '<div />' } },
      { path: '/lessonforge/settings/models', name: 'ai-credentials', component: { template: '<div />' } },
    ],
  });
}

async function settle() {
  await flushPromises();
  await flushPromises();
}

before(async () => {
  installDom();
  httpMock = new AxiosMockAdapter(axios);
  const [newSource, missionsSource] = await Promise.all([
    readFile(resolve(frontendRoot, 'src/views/LessonForgeNewMissionView.vue'), 'utf8'),
    readFile(resolve(frontendRoot, 'src/views/LessonForgeMissionsView.vue'), 'utf8'),
  ]);
  const frameSource = await readFile(resolve(frontendRoot, 'src/components/lessonForge/LessonForgeFrame.vue'), 'utf8');
  const newCompiled = compileScript(parse(newSource, { filename: 'src/views/LessonForgeNewMissionView.vue' }).descriptor, { id: 'data-v-lessonforge-new-mount', inlineTemplate: true });
  const missionsCompiled = compileScript(parse(missionsSource, { filename: 'src/views/LessonForgeMissionsView.vue' }).descriptor, { id: 'data-v-lessonforge-missions-mount', inlineTemplate: true });
  const frameCompiled = compileScript(parse(frameSource, { filename: 'src/components/lessonForge/LessonForgeFrame.vue' }).descriptor, { id: 'data-v-lessonforge-frame-mount', inlineTemplate: true });
  viteServer = await createServer({
    root: frontendRoot,
    configFile: resolve(frontendRoot, 'vite.config.ts'),
    server: { middlewareMode: true },
    logLevel: 'error',
    plugins: [{
      name: 'lessonforge-missions-mount-entry',
      resolveId(id) {
        if (id === '/@lessonforge-new-mount-entry.ts' || id === '/@lessonforge-missions-mount-entry.ts' || id === '/@lessonforge-frame-mount-entry.ts') return id;
        return undefined;
      },
      load(id) {
        if (id === '/@lessonforge-new-mount-entry.ts') return newCompiled.content;
        if (id === '/@lessonforge-missions-mount-entry.ts') return missionsCompiled.content;
        if (id === '/@lessonforge-frame-mount-entry.ts') return frameCompiled.content;
        return undefined;
      },
    }],
  });
  const [vueTestUtils, pinia, routerModule, newModule, missionsModule, frameModule, authModule, storeModule] = await Promise.all([
    import('@vue/test-utils'),
    import('pinia'),
    import('vue-router'),
    viteServer.ssrLoadModule('/@lessonforge-new-mount-entry.ts'),
    viteServer.ssrLoadModule('/@lessonforge-missions-mount-entry.ts'),
    viteServer.ssrLoadModule('/@lessonforge-frame-mount-entry.ts'),
    viteServer.ssrLoadModule('/src/stores/auth.ts'),
    viteServer.ssrLoadModule('/src/stores/lessonForge.ts'),
  ]);
  mount = vueTestUtils.mount;
  flushPromises = vueTestUtils.flushPromises;
  createPinia = pinia.createPinia;
  setActivePinia = pinia.setActivePinia;
  createRouter = routerModule.createRouter;
  createMemoryHistory = routerModule.createMemoryHistory;
  LessonForgeNewMissionView = newModule.default;
  LessonForgeMissionsView = missionsModule.default;
  LessonForgeFrame = frameModule.default;
  useAuthStore = authModule.useAuthStore;
  useLessonForgeStore = storeModule.useLessonForgeStore;
  mapLessonForgeMission = storeModule.mapLessonForgeMission;
});

after(async () => {
  httpMock?.restore();
  await viteServer?.close();
  dom?.window.close();
});

test('New Mission Recent and Search stay within the loaded current teacher collection across account changes', async () => {
  localStorage.clear();
  httpMock.reset();
  httpMock.onGet('/api/v1/lessonforge/missions').reply(200, {
    code: 0,
    data: [
      mission(1, 'Teacher One Newton', 2, 'ACCEPTED'),
      mission(2, 'Teacher Two Algebra', 3, 'ACCEPTED'),
      mission(3, 'Teacher One Pending', 2, 'ASSIGNED'),
    ],
  });
  const pinia = createPinia();
  setActivePinia(pinia);
  const auth = useAuthStore(pinia);
  auth.applySession('token-one', teacherOne as any);
  const appRouter = router();
  await appRouter.push('/lessonforge/new');
  await appRouter.isReady();
  const wrapper = mount(LessonForgeNewMissionView, {
    global: {
      plugins: [pinia, appRouter],
      stubs: {
        LessonForgeComposer: { template: '<div />' },
        'lesson-forge-frame': {
          props: ['recentMissions', 'searchMissions'],
          template: '<div><button v-for="mission in recentMissions" :key="mission.id" class="lf-recent-item">{{ mission.title }}</button><span v-for="mission in searchMissions" :key="`search-${mission.id}`" class="lf-search-source">{{ mission.title }}</span><div v-if="!recentMissions.length" class="lf-nav-empty">No missions</div></div>',
        },
        LessonForgeFrame: {
          props: ['recentMissions', 'searchMissions'],
          template: '<div><button v-for="mission in recentMissions" :key="mission.id" class="lf-recent-item">{{ mission.title }}</button><span v-for="mission in searchMissions" :key="`search-${mission.id}`" class="lf-search-source">{{ mission.title }}</span><div v-if="!recentMissions.length" class="lf-nav-empty">No missions</div></div>',
        },
      },
    },
  });
  await settle();

  assert.deepEqual(wrapper.findAll('.lf-recent-item').map((item) => item.text()), ['Teacher One Newton']);
  assert.deepEqual(wrapper.findAll('.lf-search-source').map((item) => item.text()), ['Teacher One Newton', 'Teacher One Pending']);

  const frame = mount(LessonForgeFrame, {
    props: { recentMissions: [], searchMissions: [mapLessonForgeMission(mission(1, 'Teacher One Newton', 2, 'ACCEPTED') as any)] },
    global: { plugins: [pinia, appRouter] },
  });
  await frame.findAll('.lf-nav-action')[2].trigger('click');
  await frame.get('.lf-search-input').setValue('Teacher One Newton');
  assert.deepEqual(frame.findAll('.lf-search-result').map((item) => item.text()), ['Teacher One NewtonTeacher One Newton description']);
  await frame.get('.lf-search-input').setValue('Teacher Two Algebra');
  assert.equal(frame.find('.lf-search-empty').exists(), true);

  await wrapper.unmount();
  auth.applySession('token-two', teacherTwo as any);
  const lessonForge = useLessonForgeStore(pinia);
  assert.equal(lessonForge.missions.length, 0);
  assert.equal(lessonForge.recentMissionsForViewer(teacherTwo.id, teacherTwo.activeRole).length, 0);
  auth.clearSession();
  assert.equal(lessonForge.missions.length, 0);
  assert.equal(localStorage.getItem('a12-auth-token'), null);
  await frame.unmount();
});

test('Missions filters use the backend status contract and do not advertise unsupported Completed', async () => {
  localStorage.clear();
  httpMock.reset();
  httpMock.onGet('/api/v1/lessonforge/missions').reply(200, {
    code: 0,
    data: [
      mission(1, 'Assigned', 2, 'ASSIGNED'),
      mission(2, 'Accepted', 2, 'ACCEPTED'),
      mission(3, 'Rejected', 2, 'REJECTED'),
      mission(4, 'Submitted', 2, 'SUBMITTED'),
    ],
  });
  const pinia = createPinia();
  setActivePinia(pinia);
  const auth = useAuthStore(pinia);
  auth.applySession('token-one', teacherOne as any);
  const appRouter = router();
  await appRouter.push('/lessonforge/missions');
  await appRouter.isReady();
  const wrapper = mount(LessonForgeMissionsView, {
    global: {
      plugins: [pinia, appRouter],
      stubs: { LessonForgeFrame: { template: '<div><slot /></div>' } },
    },
  });
  await settle();

  const filterLabels = wrapper.findAll('.lf-filter-tabs button').map((button) => button.text());
  assert.deepEqual(filterLabels, ['All', 'Pending', 'In progress', 'Feedback', 'Submitted']);
  assert.match(wrapper.text(), /待开始/);
  assert.match(wrapper.text(), /进行中/);
  assert.match(wrapper.text(), /待修改/);
  assert.match(wrapper.text(), /已提交/);
  await wrapper.findAll('.lf-filter-tabs button')[4].trigger('click');
  assert.deepEqual(wrapper.findAll('.lf-mission-row').map((row) => row.find('strong').text()), ['Submitted']);
  await wrapper.unmount();
});

test('top-level LessonForge routes expose exactly one active app navigation item', async () => {
  localStorage.clear();
  httpMock.reset();
  httpMock.onGet('/api/v1/lessonforge/missions').reply(200, { code: 0, data: [] });
  const [newViewSource, missionsViewSource] = await Promise.all([
    readFile(resolve(frontendRoot, 'src/views/LessonForgeNewMissionView.vue'), 'utf8'),
    readFile(resolve(frontendRoot, 'src/views/LessonForgeMissionsView.vue'), 'utf8'),
  ]);
  assert.match(newViewSource, /<LessonForgeFrame[^>]*active="new"/s);
  assert.match(missionsViewSource, /<LessonForgeFrame[^>]*active="missions"/s);
  const pinia = createPinia();
  setActivePinia(pinia);
  const auth = useAuthStore(pinia);
  auth.applySession('token-one', teacherOne as any);
  const appRouter = router();

  await appRouter.push('/lessonforge/missions');
  await appRouter.isReady();
  const missionsWrapper = mount(LessonForgeFrame, {
    props: { active: 'missions', showNewMission: true },
    global: { plugins: [pinia, appRouter] },
  });
  await settle();
  const missionsActive = missionsWrapper.findAll('.lf-nav-action.is-active');
  assert.equal(missionsActive.length, 1);
  assert.equal(missionsActive[0].find('.lf-nav-icon').text(), '⚑');
  assert.equal(missionsWrapper.get('.lf-nav-action--primary').classes('is-active'), false);
  await missionsWrapper.unmount();

  await appRouter.push('/lessonforge/new');
  const newMissionWrapper = mount(LessonForgeFrame, {
    props: { active: 'new', showNewMission: true },
    global: { plugins: [pinia, appRouter] },
  });
  await settle();
  const newMissionActive = newMissionWrapper.findAll('.lf-nav-action.is-active');
  assert.equal(newMissionActive.length, 1);
  assert.equal(newMissionActive[0].text(), '＋New Mission');
  assert.equal(newMissionWrapper.get('.lf-nav-action--primary').classes('is-active'), true);
  assert.equal(newMissionWrapper.findAll('.lf-nav-action')[1].classes('is-active'), false);
  await newMissionWrapper.unmount();
});

test('top-level app navigation can be resized horizontally and persists per account', async () => {
  localStorage.clear();
  const pinia = createPinia();
  setActivePinia(pinia);
  const auth = useAuthStore(pinia);
  auth.applySession('token-one', teacherOne as any);
  const appRouter = router();
  await appRouter.push('/lessonforge/missions');
  await appRouter.isReady();

  const wrapper = mount(LessonForgeFrame, {
    props: { recentMissions: [], searchMissions: [] },
    global: { plugins: [pinia, appRouter] },
  });
  await settle();

  const handle = wrapper.get('[data-test="resize-app-nav"]');
  assert.equal(handle.attributes('aria-label'), '调整左侧导航栏宽度');
  assert.equal(handle.attributes('aria-valuenow'), '192');
  await handle.trigger('pointerdown', { button: 0, clientX: 192 });
  const move = new dom.window.Event('pointermove');
  Object.defineProperty(move, 'clientX', { value: 252 });
  dom.window.dispatchEvent(move);
  await settle();
  assert.match(wrapper.get('.lf-frame-body').attributes('style') || '', /--lf-app-nav-width:\s*252px/);
  assert.equal(handle.attributes('aria-valuenow'), '252');

  const up = new dom.window.Event('pointerup');
  dom.window.dispatchEvent(up);
  assert.equal(localStorage.getItem('lessonforge:app-navigation-width:2'), '252');

  await wrapper.unmount();
  const restored = mount(LessonForgeFrame, {
    props: { recentMissions: [], searchMissions: [] },
    global: { plugins: [pinia, appRouter] },
  });
  await settle();
  assert.match(restored.get('.lf-frame-body').attributes('style') || '', /--lf-app-nav-width:\s*252px/);
  await restored.unmount();
});

test('researcher shared navigation opens researcher queue and review detail routes', async () => {
  localStorage.clear();
  const pinia = createPinia();
  setActivePinia(pinia);
  const auth = useAuthStore(pinia);
  auth.applySession('token-researcher', researcher as any);
  const appRouter = router();
  await appRouter.push('/reviewer/missions');
  await appRouter.isReady();

  const wrapper = mount(LessonForgeFrame, {
    props: {
      recentMissions: [mapLessonForgeMission(mission(21, 'Review mission', 2, 'SUBMITTED'))],
      searchMissions: [mapLessonForgeMission(mission(21, 'Review mission', 2, 'SUBMITTED'))],
    },
    global: { plugins: [pinia, appRouter] },
  });
  await settle();

  await wrapper.findAll('.lf-nav-action')[1].trigger('click');
  await settle();
  assert.equal(appRouter.currentRoute.value.name, 'lessonforge-researcher-reviews');

  await wrapper.get('.lf-recent-item').trigger('click');
  await settle();
  assert.equal(appRouter.currentRoute.value.name, 'lessonforge-researcher-review');
  assert.equal(appRouter.currentRoute.value.params.missionId, '21');

  await appRouter.push('/reviewer/missions');
  await wrapper.findAll('.lf-nav-action')[2].trigger('click');
  await wrapper.get('.lf-search-input').setValue('Review mission');
  await wrapper.get('.lf-search-result').trigger('click');
  await settle();
  assert.equal(appRouter.currentRoute.value.name, 'lessonforge-researcher-review');
  assert.equal(appRouter.currentRoute.value.params.missionId, '21');

  await wrapper.unmount();
});

test('LessonForge account control logs out from both navigation surfaces', async () => {
  localStorage.clear();
  httpMock.reset();
  httpMock.onPost('/api/v1/auth/logout').reply(204);
  const pinia = createPinia();
  setActivePinia(pinia);
  const auth = useAuthStore(pinia);
  auth.applySession('token-one', teacherOne as any);
  const appRouter = router();
  await appRouter.push('/lessonforge/missions');
  await appRouter.isReady();

  const navWrapper = mount(LessonForgeFrame, { props: { workspace: false }, global: { plugins: [pinia, appRouter] } });
  await navWrapper.get('.lf-nav-user').trigger('click');
  assert.equal(navWrapper.get('[role="menu"]').text(), 'Teacher One教师设置退出登录');
  assert.equal(navWrapper.get('[data-test="account-settings"]').text(), '设置');
  await navWrapper.get('[data-test="account-settings"]').trigger('click');
  await settle();
  assert.equal(appRouter.currentRoute.value.name, 'ai-credentials');
  await appRouter.push('/lessonforge/missions');
  await navWrapper.get('.lf-nav-user').trigger('click');
  await navWrapper.get('[data-test="account-logout"]').trigger('click');
  await settle();
  assert.equal(auth.isAuthenticated, false);
  assert.equal(appRouter.currentRoute.value.name, 'lessonforge-login');
  await navWrapper.unmount();

  auth.applySession('token-one', teacherOne as any);
  await appRouter.push('/lessonforge/missions/7');
  const workspaceWrapper = mount(LessonForgeFrame, { props: { workspace: true }, global: { plugins: [pinia, appRouter] } });
  assert.equal(workspaceWrapper.find('.lf-topbar__account').exists(), false);
  await workspaceWrapper.unmount();
});

test('model settings is a standalone full-window surface with a return path', async () => {
  const source = await readFile(resolve(frontendRoot, 'src/views/AiCredentialsView.vue'), 'utf8');
  assert.match(source, /class="model-settings-screen"/);
  assert.match(source, /class="model-settings-sidebar"/);
  assert.equal((source.match(/class="settings-nav__item/g) || []).length, 1);
  assert.match(source, /模型配置/);
  assert.match(source, /router\.push\(\{ name: 'lessonforge-missions' \}\)/);
  assert.doesNotMatch(source, /LessonForgeFrame/);
});

test('unknown Mission status fails closed instead of falling back to ASSIGNED', () => {
  assert.throws(() => mapLessonForgeMission(mission(9, 'Unknown', 2, 'COMPLETED') as any), /UNSUPPORTED_MISSION_STATUS:COMPLETED/);
});

test('Mission mapper does not invent Board data and only maps valid server extensions', () => {
  const base = mission(10, 'No Board Data', 2, 'ACCEPTED') as any;
  const mapped = mapLessonForgeMission(base);
  assert.equal(mapped.currentPhase, 'UNAVAILABLE');
  assert.equal(mapped.plan, undefined);
  assert.equal(mapped.output, undefined);
  assert.deepEqual(mapped.activities, [{ label: '展示回退', detail: '暂无服务端 Activity（非真实业务事件）', time: '—', tone: 'default' }]);

  const draft = mapLessonForgeMission({
    ...base,
    plan: { version: 'v3', pages: 24, status: 'DRAFT', summary: 'Server plan', sections: ['Opening'] },
  });
  assert.equal(draft.currentPhase, 'DRAFT');
  assert.equal(draft.plan?.summary, 'Server plan');

  const locked = mapLessonForgeMission({
    ...base,
    plan: { version: 'v3', pages: 24, status: 'LOCKED', summary: 'Server plan', sections: ['Opening'] },
  });
  assert.equal(locked.currentPhase, 'LOCKED');

  const output = mapLessonForgeMission({
    ...base,
    output: { name: 'server-output.pptx', pages: 24, version: 'v3', sizeLabel: '2 MB' },
    generation: { status: 'SUCCEEDED', detail: 'server' },
    activities: [{ label: 'PPT generated', detail: '24 Slides', time: '10:24', tone: 'success' }],
  });
  assert.equal(output.currentPhase, 'SUCCEEDED');
  assert.equal(output.output?.name, 'server-output.pptx');
  assert.equal(output.generation, undefined);
  assert.deepEqual(output.activities, [{ label: 'PPT generated', detail: '24 Slides', time: '10:24', tone: 'success' }]);

  const malformed = mapLessonForgeMission({
    ...base,
    plan: { version: 'v?', pages: '24', status: 'LOCKED', summary: 'Invalid', sections: [] },
    activities: [{ label: 'Incomplete', detail: '', time: '10:24' }],
  } as any);
  assert.equal(malformed.currentPhase, 'CONFLICT');
  assert.equal(malformed.plan, undefined);
  assert.deepEqual(malformed.activities, [{ label: '展示回退', detail: '暂无服务端 Activity（非真实业务事件）', time: '—', tone: 'default' }]);
});
