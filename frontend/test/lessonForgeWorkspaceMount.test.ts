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
const demoUser = { id: 2, username: 'teacher-demo', displayName: 'Demo Teacher', roles: ['TEACHER'], activeRole: 'TEACHER' };
let dom: JSDOM;
let viteServer: ViteDevServer;
let mount: typeof import('@vue/test-utils')['mount'];
let flushPromises: typeof import('@vue/test-utils')['flushPromises'];
let createPinia: typeof import('pinia')['createPinia'];
let setActivePinia: typeof import('pinia')['setActivePinia'];
let createRouter: typeof import('vue-router')['createRouter'];
let createMemoryHistory: typeof import('vue-router')['createMemoryHistory'];
let LessonForgeMissionWorkspaceView: any;
let useAuthStore: typeof import('../src/stores/auth')['useAuthStore'];
let useLessonForgeStore: typeof import('../src/stores/lessonForge')['useLessonForgeStore'];
let httpMock: AxiosMockAdapter;

function setGlobal(name: string, value: unknown) {
  Object.defineProperty(globalThis, name, { configurable: true, writable: true, value });
}

function installDom() {
  dom = new JSDOM('<!doctype html><html><body></body></html>', { url: 'http://localhost/lessonforge/missions/7' });
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

function stubs() {
  return {
    LessonForgeFrame: { template: '<div><slot /></div>' },
    LessonForgeMissionRail: { template: '<aside />' },
    LessonForgeBoard: { template: '<aside />' },
    LessonForgeComposer: { props: ['disabled'], template: '<button data-test="workspace-stub-send" :disabled="disabled" @click="$emit(\'send\', { text: \'blocked\', files: [{ name: \'context.pdf\', file: {} }] })" />' },
    LessonForgeSubmissionCard: { template: '<section />' },
  };
}

function productionStageStubs() {
  return {
    LessonForgeFrame: { template: '<div><slot /></div>' },
    LessonForgeComposer: { template: '<div data-test="composer-stub" />' },
  };
}

function stageMission(fields: Record<string, unknown>) {
  return {
    id: 7, title: 'Stage Mission', description: 'A stage test Mission', assignedTeacherId: 2, createdByLeaderId: 9,
    status: 'ACCEPTED', rejectionReason: null, selectedModelConnectionId: null, conversationId: 7,
    messages: [], contextFiles: [], submissions: [], teacherName: 'Demo Teacher', leaderName: 'Leader', ...fields,
  };
}

const validQuestion = { current: 1, total: 4, prompt: '选择课堂重点', options: ['重点一', '重点二'] };
const validPlan = { version: 'v1', pages: 4, status: 'DRAFT', summary: '课程方案', sections: ['导入'] };
const validLockedPlan = { ...validPlan, status: 'LOCKED' };
const validGeneration = { status: 'GENERATING', detail: 'queued' };
const validOutput = { name: 'stage.pptx', pages: 4, version: 'v1', sizeLabel: '1 MB' };
const validReviewedSubmission = { id: 11, fileName: 'stage-reviewed.pptx', size: 10, status: 'REVIEWED', reviewerId: 9, rating: 4, reviewNote: 'Looks good', submittedAt: '2026-09-01T01:00:00', reviewedAt: '2026-09-01T02:00:00', downloadUrl: '/download' };

async function settle() {
  await flushPromises();
  await flushPromises();
}

before(async () => {
  installDom();
  httpMock = new AxiosMockAdapter(axios);
  const mission = (id: number, withReview = false) => ({
    id, title: id === 7 ? 'Chemistry' : 'Newton', description: 'A real Mission', assignedTeacherId: 2, createdByLeaderId: 9,
    status: 'ACCEPTED', rejectionReason: null, selectedModelConnectionId: null, conversationId: id,
    messages: [], contextFiles: [], submissions: withReview ? [{ id: 11, fileName: 'chemistry.pptx', contentType: 'application/vnd.openxmlformats-officedocument.presentationml.presentation', size: 10, sha256: 'a'.repeat(64), status: 'REVIEWED', reviewerId: 9, rating: 4, reviewNote: 'Looks good', submittedAt: '2026-09-01T01:00:00', reviewedAt: '2026-09-01T02:00:00', downloadUrl: '/download' }] : [], ...(withReview ? { generation: { status: 'SUCCEEDED' }, output: { name: 'chemistry.pptx', pages: 12, version: 'v1', sizeLabel: '1 MB' } } : {}), teacherName: 'Demo Teacher', leaderName: 'Leader',
  });
  httpMock.onGet('/api/v1/lessonforge/missions/7').reply(200, { code: 0, data: mission(7, true) });
  httpMock.onGet('/api/v1/lessonforge/missions/8').reply(200, { code: 0, data: mission(8) });
  httpMock.onGet('/api/v1/lessonforge/missions/9').reply(200, { code: 0, data: { ...mission(9), status: 'ASSIGNED' } });
  httpMock.onGet('/api/v1/ai-credentials/connections').reply(200, { code: 0, data: [] });
  const [source, railSource, boardSource, previewSource, submissionSource] = await Promise.all([
    readFile(resolve(frontendRoot, 'src/views/LessonForgeMissionWorkspaceView.vue'), 'utf8'),
    readFile(resolve(frontendRoot, 'src/components/lessonForge/LessonForgeMissionRail.vue'), 'utf8'),
    readFile(resolve(frontendRoot, 'src/components/lessonForge/LessonForgeBoard.vue'), 'utf8'),
    readFile(resolve(frontendRoot, 'src/components/lessonForge/LessonForgePptPreview.vue'), 'utf8'),
    readFile(resolve(frontendRoot, 'src/components/lessonForge/LessonForgeSubmissionCard.vue'), 'utf8'),
  ]);
  const compiled = compileScript(parse(source, { filename: 'src/views/LessonForgeMissionWorkspaceView.vue' }).descriptor, { id: 'data-v-lessonforge-workspace-mount', inlineTemplate: true });
  const railCompiled = compileScript(parse(railSource, { filename: 'src/components/lessonForge/LessonForgeMissionRail.vue' }).descriptor, { id: 'data-v-lessonforge-workspace-rail', inlineTemplate: true });
  const boardCompiled = compileScript(parse(boardSource, { filename: 'src/components/lessonForge/LessonForgeBoard.vue' }).descriptor, { id: 'data-v-lessonforge-workspace-board', inlineTemplate: true });
  const previewCompiled = compileScript(parse(previewSource, { filename: 'src/components/lessonForge/LessonForgePptPreview.vue' }).descriptor, { id: 'data-v-lessonforge-workspace-preview', inlineTemplate: true });
  const submissionCompiled = compileScript(parse(submissionSource, { filename: 'src/components/lessonForge/LessonForgeSubmissionCard.vue' }).descriptor, { id: 'data-v-lessonforge-workspace-submission', inlineTemplate: true });
  const workspaceContent = compiled.content
    .replace('@/components/lessonForge/LessonForgeMissionRail.vue', '/@lessonforge-workspace-rail.ts')
    .replace('@/components/lessonForge/LessonForgeBoard.vue', '/@lessonforge-workspace-board.ts')
    .replace('@/components/lessonForge/LessonForgePptPreview.vue', '/@lessonforge-workspace-preview.ts')
    .replace('@/components/lessonForge/LessonForgeSubmissionCard.vue', '/@lessonforge-workspace-submission.ts');
  viteServer = await createServer({
    root: frontendRoot,
    configFile: resolve(frontendRoot, 'vite.config.ts'),
    server: { middlewareMode: true },
    logLevel: 'error',
    plugins: [{
      name: 'lessonforge-workspace-mount-entry',
      resolveId(id) {
        return ['/@lessonforge-workspace-mount-entry.ts', '/@lessonforge-workspace-rail.ts', '/@lessonforge-workspace-board.ts', '/@lessonforge-workspace-preview.ts', '/@lessonforge-workspace-submission.ts'].includes(id) ? id : undefined;
      },
      load(id) {
        if (id === '/@lessonforge-workspace-mount-entry.ts') return workspaceContent;
        if (id === '/@lessonforge-workspace-rail.ts') return railCompiled.content;
        if (id === '/@lessonforge-workspace-board.ts') return boardCompiled.content;
        if (id === '/@lessonforge-workspace-preview.ts') return previewCompiled.content;
        if (id === '/@lessonforge-workspace-submission.ts') return submissionCompiled.content;
        return undefined;
      },
    }],
  });
  const [vueTestUtils, pinia, router, viewModule, authModule, storeModule] = await Promise.all([
    import('@vue/test-utils'),
    import('pinia'),
    import('vue-router'),
    viteServer.ssrLoadModule('/@lessonforge-workspace-mount-entry.ts'),
    viteServer.ssrLoadModule('/src/stores/auth.ts'),
    viteServer.ssrLoadModule('/src/stores/lessonForge.ts'),
  ]);
  mount = vueTestUtils.mount;
  flushPromises = vueTestUtils.flushPromises;
  createPinia = pinia.createPinia;
  setActivePinia = pinia.setActivePinia;
  createRouter = router.createRouter;
  createMemoryHistory = router.createMemoryHistory;
  LessonForgeMissionWorkspaceView = viewModule.default;
  useAuthStore = authModule.useAuthStore;
  useLessonForgeStore = storeModule.useLessonForgeStore;
});

after(async () => {
  httpMock?.restore();
  await viteServer?.close();
  dom?.window.close();
});

test('feedback disclosure resets when the same workspace changes Mission', async () => {
  localStorage.clear();
  const pinia = createPinia();
  setActivePinia(pinia);
  const auth = useAuthStore(pinia);
  auth.applySession('lessonforge-demo', demoUser as any);
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/lessonforge/missions/:missionId', component: LessonForgeMissionWorkspaceView }],
  });
  await router.push('/lessonforge/missions/7');
  await router.isReady();
  const wrapper = mount(LessonForgeMissionWorkspaceView, { global: { plugins: [pinia, router], stubs: stubs() } });
  await settle();

  assert.equal(wrapper.find('[data-test="review-card"]').exists(), true);
  assert.equal(wrapper.find('[data-test="review-details"]').exists(), false);
  await wrapper.get('[data-test="review-toggle"]').trigger('click');
  assert.equal(wrapper.find('[data-test="review-details"]').exists(), true);

  await router.push('/lessonforge/missions/8');
  await settle();
  await router.push('/lessonforge/missions/7');
  await settle();
  assert.equal(wrapper.find('[data-test="review-card"]').exists(), true);
  assert.equal(wrapper.find('[data-test="review-details"]').exists(), false);
  await wrapper.unmount();
});

test('ASSIGNED Mission blocks Composer writes for text and files before acceptance', async () => {
  localStorage.clear();
  httpMock.resetHistory();
  const pinia = createPinia();
  setActivePinia(pinia);
  const auth = useAuthStore(pinia);
  auth.applySession('lessonforge-demo', demoUser as any);
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/lessonforge/missions/:missionId', component: LessonForgeMissionWorkspaceView }],
  });
  await router.push('/lessonforge/missions/9');
  await router.isReady();
  const wrapper = mount(LessonForgeMissionWorkspaceView, { global: { plugins: [pinia, router], stubs: stubs() } });
  await settle();

  const send = wrapper.get('[data-test="workspace-stub-send"]');
  assert.equal((send.element as HTMLButtonElement).disabled, true);
  await send.trigger('click');
  assert.equal(httpMock.history.post.filter(request => request.url?.includes('/context-files')).length, 0);
  assert.equal(httpMock.history.post.filter(request => request.url?.includes('/conversation/messages')).length, 0);
  await wrapper.unmount();
});

test('production LessonForge workspace renders exactly one legal phase across Central, Rail, Board and Preview', async () => {
  const legalStages = [
    { phase: 'QUESTION', fields: { question: validQuestion }, visible: 'question-card' },
    { phase: 'DRAFT', fields: { plan: validPlan }, visible: 'plan-card' },
    { phase: 'LOCKED', fields: { plan: validLockedPlan }, visible: 'plan-card' },
    { phase: 'GENERATING', fields: { generation: validGeneration }, visible: 'generation-card' },
    { phase: 'SUCCEEDED', fields: { generation: { status: 'SUCCEEDED' }, output: validOutput, submissions: [validReviewedSubmission] }, visible: 'output-card' },
  ];
  const phaseSelectors = ['question-card', 'plan-card', 'generation-card', 'output-card', 'ppt-preview-layer', 'phase-conflict', 'phase-unavailable', 'submission-card', 'rail-submission', 'review-card'];
  for (const stage of legalStages) {
    httpMock.reset();
    httpMock.onGet('/api/v1/lessonforge/missions/7').reply(200, { code: 0, data: stageMission(stage.fields) });
    httpMock.onGet('/api/v1/ai-credentials/connections').reply(200, { code: 0, data: [] });
    const pinia = createPinia();
    setActivePinia(pinia);
    const auth = useAuthStore(pinia);
    auth.applySession('lessonforge-stage', demoUser as any);
    const appRouter = createRouter({ history: createMemoryHistory(), routes: [{ path: '/lessonforge/missions/:missionId', component: LessonForgeMissionWorkspaceView }] });
    await appRouter.push('/lessonforge/missions/7');
    await appRouter.isReady();
    const wrapper = mount(LessonForgeMissionWorkspaceView, { global: { plugins: [pinia, appRouter], stubs: productionStageStubs() } });
    await settle();
    const store = useLessonForgeStore(pinia);
    assert.equal(store.currentMission?.currentPhase, stage.phase);
    for (const selector of phaseSelectors) assert.equal(wrapper.find(`[data-test="${selector}"]`).exists(), selector === stage.visible || (stage.phase === 'SUCCEEDED' && ['ppt-preview-layer', 'submission-card', 'rail-submission', 'review-card'].includes(selector)), `${stage.phase}:${selector}`);
    assert.equal(wrapper.find('[data-test="board-courseware"]').exists(), stage.phase === 'DRAFT' || stage.phase === 'LOCKED');
    assert.equal(wrapper.find('[data-test="rail-courseware"]').exists(), stage.phase === 'DRAFT' || stage.phase === 'LOCKED');
    assert.equal(wrapper.find('[data-test="rail-outputs"]').exists(), stage.phase === 'SUCCEEDED');
    await wrapper.unmount();
  }
});

test('production LessonForge workspace fails closed for the six main invalid phase combinations', async () => {
  const invalidCombinations = [
    { name: 'question+plan', fields: { question: validQuestion, plan: validPlan } },
    { name: 'question+generation', fields: { question: validQuestion, generation: validGeneration } },
    { name: 'question+output', fields: { question: validQuestion, output: validOutput } },
    { name: 'plan+generation', fields: { plan: validPlan, generation: validGeneration } },
    { name: 'plan+output', fields: { plan: validPlan, output: validOutput } },
    { name: 'generation+output', fields: { generation: validGeneration, output: validOutput } },
  ];
  for (const combination of invalidCombinations) {
    httpMock.reset();
    httpMock.onGet('/api/v1/lessonforge/missions/7').reply(200, { code: 0, data: stageMission({ ...combination.fields, submissions: [validReviewedSubmission] }) });
    httpMock.onGet('/api/v1/ai-credentials/connections').reply(200, { code: 0, data: [] });
    const pinia = createPinia();
    setActivePinia(pinia);
    const auth = useAuthStore(pinia);
    auth.applySession('lessonforge-conflict', demoUser as any);
    const appRouter = createRouter({ history: createMemoryHistory(), routes: [{ path: '/lessonforge/missions/:missionId', component: LessonForgeMissionWorkspaceView }] });
    await appRouter.push('/lessonforge/missions/7');
    await appRouter.isReady();
    const wrapper = mount(LessonForgeMissionWorkspaceView, { global: { plugins: [pinia, appRouter], stubs: productionStageStubs() } });
    await settle();
    const store = useLessonForgeStore(pinia);
    assert.equal(store.currentMission?.currentPhase, 'CONFLICT', combination.name);
    assert.equal(wrapper.find('[data-test="phase-conflict"]').exists(), true, combination.name);
    for (const selector of ['question-card', 'plan-card', 'generation-card', 'output-card', 'ppt-preview-layer', 'board-courseware', 'rail-courseware', 'rail-outputs', 'submission-card', 'rail-submission', 'review-card']) assert.equal(wrapper.find(`[data-test="${selector}"]`).exists(), false, `${combination.name}:${selector}`);
    await wrapper.unmount();
  }
});

test('production LessonForge workspace keeps unavailable and incomplete success data safe', async () => {
  const safeCases = [
    { name: 'unavailable', fields: { submissions: [validReviewedSubmission] }, phase: 'UNAVAILABLE', visible: 'phase-unavailable' },
    { name: 'succeeded-without-output', fields: { generation: { status: 'SUCCEEDED' }, submissions: [validReviewedSubmission] }, phase: 'CONFLICT', visible: 'phase-conflict' },
  ];
  for (const safeCase of safeCases) {
    httpMock.reset();
    httpMock.onGet('/api/v1/lessonforge/missions/7').reply(200, { code: 0, data: stageMission(safeCase.fields) });
    httpMock.onGet('/api/v1/ai-credentials/connections').reply(200, { code: 0, data: [] });
    const pinia = createPinia();
    setActivePinia(pinia);
    const auth = useAuthStore(pinia);
    auth.applySession(`lessonforge-${safeCase.name}`, demoUser as any);
    const appRouter = createRouter({ history: createMemoryHistory(), routes: [{ path: '/lessonforge/missions/:missionId', component: LessonForgeMissionWorkspaceView }] });
    await appRouter.push('/lessonforge/missions/7');
    await appRouter.isReady();
    const wrapper = mount(LessonForgeMissionWorkspaceView, { global: { plugins: [pinia, appRouter], stubs: productionStageStubs() } });
    await settle();
    const store = useLessonForgeStore(pinia);
    assert.equal(store.currentMission?.currentPhase, safeCase.phase, safeCase.name);
    assert.equal(wrapper.find(`[data-test="${safeCase.visible}"]`).exists(), true, safeCase.name);
    for (const selector of ['question-card', 'plan-card', 'generation-card', 'output-card', 'ppt-preview-layer', 'board-courseware', 'rail-courseware', 'rail-outputs', 'submission-card', 'rail-submission', 'review-card']) assert.equal(wrapper.find(`[data-test="${selector}"]`).exists(), false, `${safeCase.name}:${selector}`);
    await wrapper.unmount();
  }
});
